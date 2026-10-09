package de.spahr.ausgaben.db;

import android.content.Context;
import android.os.Handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import de.spahr.ausgaben.db.Repository.Callback;
import de.spahr.ausgaben.db.Repository.DepotHolding;
import de.spahr.ausgaben.db.Repository.DepotMetrics;

/**
 * Depot (Wertpapiere): Import-Ersetzen, Bestände, Bewegungen und Kennzahlen (Wert, Käufe/Verkäufe/Dividenden,
 * Nettoeinsatz, Gewinn/Verlust). Kollaborator hinter der {@link Repository}-Fassade.
 */
class DepotRepository {

    private final AppDatabase db;
    private final SecurityDao securityDao;
    private final Context appContext;
    private final ExecutorService executor;
    private final Handler mainHandler;

    DepotRepository(AppDatabase db, SecurityDao securityDao, Context appContext,
                    ExecutorService executor, Handler mainHandler) {
        this.db = db;
        this.securityDao = securityDao;
        this.appContext = appContext;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    /** Ersetzt die Depotdaten (Wertpapiere + Bewegungen + Kurshistorie) eines Depots. */
    void replaceDepotImport(final String depot, final List<Security> securities,
                            final List<SecurityTx> transactions, final List<SecurityPrice> prices,
                            final Runnable onDone) {
        replaceDepotImport(depot, securities, transactions, prices, null, onDone);
    }

    /**
     * Wie oben, meldet aber den Fortschritt (geschriebene von insgesamt zu schreibenden Zeilen).
     *
     * <p>Der ganze Block läuft in <b>einer</b> Transaktion: vorher bekam jede einzelne Kurszeile ihre
     * eigene (inkl. fsync) – seit der Kurshistorie sind das mehrere tausend, und genau das war die
     * lange Pause in der Anzeige.</p>
     */
    void replaceDepotImport(final String depot, final List<Security> securities,
                            final List<SecurityTx> transactions, final List<SecurityPrice> prices,
                            final de.spahr.ausgaben.util.ProgressListener listener,
                            final Runnable onDone) {
        executor.execute(() -> {
            final int total = securities.size() + transactions.size()
                    + (prices == null ? 0 : prices.size());
            final int[] done = new int[1];
            db.runInTransaction(() -> {
                securityDao.deleteImportedSplitsOf(depot);
                securityDao.deleteImportedTx(depot);
                securityDao.deleteSecurities(depot);
                securityDao.deletePrices(depot);
                for (Security s : securities) {
                    securityDao.insertSecurity(s);
                    report(listener, done, total);
                }
                for (SecurityTx t : transactions) {
                    // Die Kategoriesplits der Datei kommen mit: sie belegen die Erfassungsmaske vor.
                    writeParts(t, securityDao.insertTx(t));
                    report(listener, done, total);
                }
                if (prices != null) {
                    for (SecurityPrice p : prices) {
                        securityDao.insertPrice(p);
                        report(listener, done, total);
                    }
                }
            });
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    private static void report(de.spahr.ausgaben.util.ProgressListener listener, int[] done, int total) {
        done[0]++;
        if (listener != null) {
            listener.onProgress(done[0], total);
        }
    }

    /** Depotübergreifende Bewertung (Zeitreihe) für die Vermögensgrafik. */
    void getDepotValuation(final Callback<DepotValuation> callback) {
        executor.execute(() -> {
            final DepotValuation v = DepotValuation.build(
                    securityDao.getAllSecurities(),
                    securityDao.getAllTxPoints(),
                    securityDao.getAllPricePoints(),
                    securityDao.getAllDividendPoints());
            mainHandler.post(() -> callback.onResult(v));
        });
    }

    /** Depot-Namen mit vorhandenen Wertpapieren. */
    void getDepots(final Callback<List<String>> callback) {
        executor.execute(() -> {
            final List<String> result = securityDao.distinctDepots();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Bestände eines Depots: je Wertpapier Stückzahl × letzter Kurs = Wert. */
    void getDepotHoldings(final String depot, final Callback<List<DepotHolding>> callback) {
        executor.execute(() -> {
            Map<String, Double> shares = new HashMap<>();
            for (SecurityDao.ShareSum ss : securityDao.getShareSums(depot)) {
                shares.put(ss.kmyId, ss.shares);
            }
            List<DepotHolding> result = new ArrayList<>();
            for (Security s : securityDao.getSecurities(depot)) {
                double q = shares.containsKey(s.kmyId) ? shares.get(s.kmyId) : 0.0;
                long value = Math.round(q * s.price * 100.0);
                result.add(new DepotHolding(s.name, s.symbol, s.kmyId, q, s.price, value));
            }
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Bewegungen eines Wertpapiers (neueste zuerst); manuell gesetzte Werte für Ein-/Ausbuchungen eingeblendet. */
    void getSecurityTransactions(final String depot, final String kmyId,
                                 final Callback<List<SecurityTx>> callback) {
        executor.execute(() -> {
            final List<SecurityTx> result = securityDao.getTxBySecurity(depot, kmyId);
            applyValueOverrides(depot, result);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Überschreibt amountCents/netCents der übergebenen Zeilen mit einem passenden manuellen Wert (falls vorhanden). */
    private void applyValueOverrides(String depot, List<SecurityTx> rows) {
        List<SecurityTxValueOverride> overrides = securityDao.getValueOverrides(depot);
        if (overrides.isEmpty()) {
            return;
        }
        for (SecurityTx tx : rows) {
            for (SecurityTxValueOverride o : overrides) {
                if (o.securityKmyId.equals(tx.securityKmyId) && o.date == tx.date
                        && o.action.equals(tx.action) && o.shares == tx.shares) {
                    tx.amountCents = o.amountCents;
                    tx.netCents = o.amountCents;
                    break;
                }
            }
        }
    }

    /**
     * Legt eine in der App erfasste Bewegung samt ihrer Geldbuchung an – beides in <b>einer</b> Transaktion,
     * damit nie eine Bewegung ohne Buchung (oder umgekehrt) übrig bleibt. Die Bewegung ist vorgemerkt
     * ({@code pending}) und wird beim nächsten Export in die KMyMoney-Datei geschrieben.
     *
     * @param booking die Geldbuchung; ihre id landet in {@code tx.bookingId}. {@code null} = keine
     *                (dann bleibt der Kontostand unberührt)
     */
    void saveManualTx(final SecurityTx tx, final Booking booking, final Runnable onDone) {
        executor.execute(() -> {
            db.runInTransaction(() -> {
                tx.pending = true;
                if (booking != null) {
                    db.accountDao().insertIfAbsent(new Account(booking.account));
                    tx.bookingId = db.bookingDao().insert(booking);
                    writeBookingParts(tx.bookingId, booking);
                }
                writeParts(tx, securityDao.insertTx(tx));
            });
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Legt mehrere erfasste Bewegungen samt Geldbuchungen in <b>einem</b> Vorgang an — für die
     * Erkennungsliste, in der ein Stapel Abrechnungen auf einmal gebucht wird. Entweder alle oder keine:
     * ein halb gebuchter Stapel wäre schlimmer als ein gescheiterter, denn welche Hälfte fehlt, sieht man
     * dem Depot nicht an.
     */
    void saveManualTxBatch(final List<SecurityTx> txs, final List<Booking> bookings,
                           final Runnable onDone, final Runnable onError) {
        executor.execute(() -> {
            try {
                db.runInTransaction(() -> {
                    for (int i = 0; i < txs.size(); i++) {
                        SecurityTx tx = txs.get(i);
                        Booking booking = bookings.get(i);
                        tx.pending = true;
                        if (booking != null) {
                            db.accountDao().insertIfAbsent(new Account(booking.account));
                            tx.bookingId = db.bookingDao().insert(booking);
                            writeBookingParts(tx.bookingId, booking);
                        }
                        writeParts(tx, securityDao.insertTx(tx));
                    }
                });
            } catch (RuntimeException e) {
                // Ohne diesen Zweig bliebe die Erkennungsliste stehen: der Speichern-Knopf ist bereits
                // gesperrt, und ohne Rückmeldung kommt er nie wieder frei. Die Transaktion ist
                // zurückgerollt, gebucht wurde also nichts.
                android.util.Log.e("DepotRepository", "Stapel konnte nicht gespeichert werden", e);
                if (onError != null) {
                    mainHandler.post(onError);
                }
                return;
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Ändert eine noch nicht exportierte Bewegung samt Geldbuchung. Exportierte werden hier nicht
     * angefasst – die stehen bereits in der Datei und gehören dort korrigiert.
     */
    void updateManualTx(final SecurityTx tx, final Booking booking, final Runnable onDone) {
        executor.execute(() -> {
            // Kann nur eine Vormerkung treffen, und die ist nie abgeglichen – die Schranke steht hier
            // trotzdem, damit kein Weg an ihr vorbeiführt.
            if (tx.bookingId > 0 && ReconciledGuard.locked(db.bookingDao().getById(tx.bookingId))) {
                Repository.meldeAbgeglichen(appContext, mainHandler);
                return;
            }
            db.runInTransaction(() -> {
                if (booking != null && tx.bookingId > 0) {
                    booking.id = tx.bookingId;
                    db.accountDao().insertIfAbsent(new Account(booking.account));
                    db.bookingDao().update(booking);
                    db.bookingDao().deleteSplits(tx.bookingId);
                    writeBookingParts(tx.bookingId, booking);
                }
                securityDao.updateTx(tx);
                securityDao.deleteSplits(tx.id);
                writeParts(tx, tx.id);
            });
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Entfernt eine noch nicht exportierte Bewegung und die zugehörige Geldbuchung. */
    void deleteManualTx(final long txId, final Runnable onDone) {
        executor.execute(() -> {
            db.runInTransaction(() -> {
                SecurityTx tx = securityDao.getTxById(txId);
                if (tx == null || !tx.pending) {
                    return;   // aus der Datei importiert: dort wird nicht gelöscht
                }
                if (tx.bookingId > 0
                        && ReconciledGuard.locked(db.bookingDao().getById(tx.bookingId))) {
                    return;   // in KMyMoney abgeglichen: nur dort zu ändern
                }
                if (tx.bookingId > 0) {
                    db.bookingDao().deleteSplits(tx.bookingId);
                    db.bookingDao().delete(tx.bookingId);
                }
                securityDao.deleteSplits(txId);
                securityDao.deleteTxById(txId);
            });
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Schreibt die Kategorie-Teile der Geldbuchung – bei einer Dividende mit Ertrag und Steuer ist sie
     * eine Splitbuchung (siehe {@link SecurityTx#toMoneyBooking}). Kauf und Verkauf haben keine.
     */
    private void writeBookingParts(long bookingId, Booking booking) {
        if (booking.parts == null) {
            return;
        }
        for (BookingSplit part : booking.parts) {
            part.bookingId = bookingId;
            db.bookingDao().insertSplit(part);
        }
    }

    /**
     * Schreibt die Kategoriezeilen einer gerade gespeicherten Bewegung. Die Reihenfolge in der Maske
     * wird dabei festgehalten: bei einer fest programmierten Bank ist sie der einzige Schlüssel, über
     * den die Kategorien beim nächsten Mal wieder zu ihren Beträgen finden.
     */
    private void writeParts(SecurityTx tx, long txId) {
        tx.id = txId;
        int sort = 0;
        for (SecurityTxSplit part : tx.parts) {
            part.txId = txId;
            part.sort = sort++;
            securityDao.insertSplit(part);
        }
    }

    /** Eine einzelne Bewegung (Erfassungsmaske im Ansehen-/Ändern-Modus). */
    void getSecurityTx(final long id, final Callback<SecurityTx> callback) {
        executor.execute(() -> {
            final SecurityTx tx = securityDao.getTxById(id);
            if (tx != null) {
                tx.parts = securityDao.getSplits(id);
            }
            mainHandler.post(() -> callback.onResult(tx));
        });
    }

    /**
     * Gegenkonto und Kategorien der jüngsten Bewegung derselben Art – Vorbelegung der Erfassungsmaske.
     * Gibt es die Art an diesem Wertpapier noch nicht, dient die jüngste Bewegung mit Gegenkonto als
     * Rückfall (dann stimmt wenigstens das Konto).
     */
    void getTxDefaults(final String depot, final String kmyId, final String action,
                       final Callback<SecurityTx> callback) {
        executor.execute(() -> {
            final SecurityTx out = txDefaults(depot, kmyId, action);
            mainHandler.post(() -> callback.onResult(out));
        });
    }

    /**
     * Dieselbe Vorbelegung für mehrere Wertpapiere auf einmal — für die Erkennungsliste, die eine ganze
     * Reihe eingelesener Abrechnungen vorbelegt. Ein Durchgang statt einer Abfrage je Eintrag; die
     * Antwort steht an derselben Stelle wie die Frage.
     *
     * @param keys je Eintrag {@code {Depot, Wertpapier-Id, Aktion}}
     */
    void getTxDefaultsBatch(final List<String[]> keys, final Callback<List<SecurityTx>> callback) {
        executor.execute(() -> {
            final List<SecurityTx> out = new ArrayList<>();
            for (String[] key : keys) {
                out.add(key == null ? null : txDefaults(key[0], key[1], key[2]));
            }
            mainHandler.post(() -> callback.onResult(out));
        });
    }

    /**
     * Steht jede dieser Bewegungen schon im Depot? Rückgabe in derselben Reihenfolge wie die Eingabe.
     *
     * <p>Für den Hinweis auf eine doppelt eingelesene Abrechnung. Geprüft wird der Kalendertag der
     * Bewegung; {@code exceptId} nimmt die gerade bearbeitete Bewegung aus, damit sie sich nicht selbst
     * als Dublette meldet (0 = keine Ausnahme).</p>
     */
    void findExisting(final List<SecurityTx> candidates, final long exceptId,
                      final Callback<boolean[]> callback) {
        executor.execute(() -> {
            final boolean[] out = new boolean[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                out[i] = existsAlready(candidates.get(i), exceptId);
            }
            mainHandler.post(() -> callback.onResult(out));
        });
    }

    private boolean existsAlready(SecurityTx tx, long exceptId) {
        if (tx == null || tx.securityKmyId.isEmpty() || tx.action.isEmpty() || tx.date <= 0) {
            return false;
        }
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(tx.date);
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        long from = day.getTimeInMillis();
        day.add(java.util.Calendar.DAY_OF_MONTH, 1);
        for (SecurityTx old : securityDao.getTxOnDay(tx.depot, tx.securityKmyId, tx.action,
                from, day.getTimeInMillis())) {
            if (old.id != exceptId && tx.sameMovement(old)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Von speziell nach allgemein: dasselbe Wertpapier, dann ein beliebiges desselben Depots, zuletzt
     * eines aus einem anderen Depot – immer aber dieselbe Art. Jedes Feld wird einzeln aufgefüllt, denn
     * die speziellere Bewegung kann Konto und Kategorie unterschiedlich gepflegt haben (etwa eine
     * importierte Dividende ohne Ertragskategorie).
     */
    private SecurityTx txDefaults(String depot, String kmyId, String action) {
        SecurityTx result = new SecurityTx();
        fillFrom(result, securityDao.getLastByAction(depot, kmyId, action));
        fillFrom(result, securityDao.getLastByActionInDepot(depot, action));
        fillFrom(result, securityDao.getLastByActionAnywhere(action));
        return result;
    }

    /**
     * Ergänzt nur, was noch leer ist – die speziellere Quelle kommt zuerst und behält damit Vorrang.
     *
     * <p>Die beiden Kategoriearten werden getrennt betrachtet: eine importierte Dividende kann ihre
     * Steuerzeilen mitbringen, aber keine Ertragskategorie, und dann soll die allgemeinere Quelle
     * genau diese eine Lücke füllen. Übernommen werden Kategorie, Herkunftsbeschriftung und
     * Reihenfolge – <b>nicht</b> die Beträge; die stammen aus der Abrechnung, die gerade vorliegt.</p>
     */
    private void fillFrom(SecurityTx target, SecurityTx source) {
        if (source == null) {
            return;
        }
        if (target.moneyAccount.isEmpty()) {
            target.moneyAccount = source.moneyAccount;
        }
        boolean brauchtErtrag = target.partsOf(true).isEmpty();
        boolean brauchtGebuehr = target.partsOf(false).isEmpty();
        if (!brauchtErtrag && !brauchtGebuehr) {
            return;
        }
        for (SecurityTxSplit part : securityDao.getSplits(source.id)) {
            if (part.income ? !brauchtErtrag : !brauchtGebuehr) {
                continue;
            }
            target.parts.add(new SecurityTxSplit(0, part.income, part.category, 0,
                    part.label, part.sort));
        }
    }

    /**
     * Alle Wertpapiere aller Depots, nach Namen sortiert — für die Auswahl, wenn eine eingelesene
     * Abrechnung eine ISIN trägt, die noch keinem Wertpapier zugeordnet ist.
     */
    void getAllSecurities(final Callback<List<Security>> callback) {
        executor.execute(() -> {
            final List<Security> all = new ArrayList<>(securityDao.getAllSecurities());
            java.util.Collections.sort(all, (a, b) ->
                    a.name.compareToIgnoreCase(b.name));
            mainHandler.post(() -> callback.onResult(all));
        });
    }

    /** Wertpapier zu einer ISIN (aus KMyMoney importiert); {@code null}, wenn keines passt. */
    void getSecurityByIsin(final String isin, final Callback<Security> callback) {
        executor.execute(() -> {
            final Security s = isin == null || isin.trim().isEmpty()
                    ? null : securityDao.getByIsin(isin.trim());
            mainHandler.post(() -> callback.onResult(s));
        });
    }

    /** Die an diesem Wertpapier schon verwendeten Kategorien: {Gebühr/Steuer, Ertrag}. */
    void getUsedCategories(final String depot, final String kmyId,
                           final Callback<List<List<String>>> callback) {
        executor.execute(() -> {
            final List<List<String>> result = new ArrayList<>();
            result.add(securityDao.getUsedFeeCategories(depot, kmyId));
            result.add(securityDao.getUsedIncomeCategories(depot, kmyId));
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Setzt den manuellen Wert einer Ein-/Ausbuchung (übersteht einen Depot-Reimport). */
    void saveSecurityTxValue(final String depot, final String kmyId, final long date, final String action,
                             final double shares, final long amountCents, final Runnable onDone) {
        executor.execute(() -> {
            securityDao.upsertValueOverride(
                    new SecurityTxValueOverride(depot, kmyId, date, action, shares, amountCents));
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Entfernt einen zuvor manuell gesetzten Wert wieder. */
    void clearSecurityTxValue(final String depot, final String kmyId, final long date, final String action,
                              final double shares, final Runnable onDone) {
        executor.execute(() -> {
            securityDao.deleteValueOverride(depot, kmyId, date, action, shares);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Kennzahlen für das komplette Depot. */
    void getDepotMetrics(final String depot, final Callback<DepotMetrics> callback) {
        executor.execute(() -> {
            Map<String, Double> shares = new HashMap<>();
            for (SecurityDao.ShareSum ss : securityDao.getShareSums(depot)) {
                shares.put(ss.kmyId, ss.shares);
            }
            long value = 0;
            for (Security s : securityDao.getSecurities(depot)) {
                double q = shares.containsKey(s.kmyId) ? shares.get(s.kmyId) : 0.0;
                value += Math.round(q * s.price * 100.0);
            }
            final DepotMetrics m = metricsFrom(value, securityDao.getActionSums(depot), dividendsGross());
            mainHandler.post(() -> callback.onResult(m));
        });
    }

    /** Kennzahlen für ein einzelnes Wertpapier. */
    void getSecurityMetrics(final String depot, final String kmyId,
                            final Callback<DepotMetrics> callback) {
        executor.execute(() -> {
            double q = 0.0;
            for (SecurityDao.ShareSum ss : securityDao.getShareSums(depot)) {
                if (kmyId != null && kmyId.equals(ss.kmyId)) {
                    q = ss.shares;
                }
            }
            long value = 0;
            for (Security s : securityDao.getSecurities(depot)) {
                if (kmyId != null && kmyId.equals(s.kmyId)) {
                    value = Math.round(q * s.price * 100.0);
                }
            }
            final DepotMetrics m = metricsFrom(value,
                    securityDao.getActionSumsBySecurity(depot, kmyId), dividendsGross());
            mainHandler.post(() -> callback.onResult(m));
        });
    }

    /** Frühester Bewegungszeitpunkt eines Depots (0 wenn leer). */
    void getDepotFirstTx(final String depot, final Callback<Long> callback) {
        executor.execute(() -> {
            final Long first = securityDao.getFirstTxMs(depot);
            final long result = first == null ? 0L : first;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Zeitraumbezogene Auswertungszeilen je Wertpapier. Aktueller Wert = im Zeitraum gekaufte Stücke ×
     * heutiger Kurs (bei vollem Zeitraum stattdessen der aktuelle Depotstand aus Netto-Stück × Kurs).
     */
    void getDepotChartRows(final String depot, final long fromMs, final long toMs,
                           final boolean wholePeriod, final Callback<List<Repository.DepotChartRow>> callback) {
        executor.execute(() -> {
            final boolean gross = dividendsGross();
            // Kurs + Anzeigename je Wertpapier-ID.
            Map<String, Security> secById = new HashMap<>();
            for (Security s : securityDao.getSecurities(depot)) {
                secById.put(s.kmyId, s);
            }
            // Netto-Stück über alles (für den aktuellen Wert bei vollem Zeitraum).
            Map<String, Double> netShares = new HashMap<>();
            for (SecurityDao.ShareSum ss : securityDao.getShareSums(depot)) {
                netShares.put(ss.kmyId, ss.shares);
            }
            // Netto-Stück bis zum Zeitraumende (exklusiv) – für „komplett verkauft am Ende des Zeitraums".
            Map<String, Double> netSharesAtEnd = new HashMap<>();
            for (SecurityDao.ShareSum ss : securityDao.getShareSumsUntil(depot, toMs)) {
                netSharesAtEnd.put(ss.kmyId, ss.shares);
            }
            // Zeitraum-Summen je Wertpapier zusammenfassen.
            Map<String, double[]> boughtShares = new HashMap<>();   // Stück aus buy/reinvest im Zeitraum
            Map<String, long[]> buyAmt = new HashMap<>();
            Map<String, long[]> sellAmt = new HashMap<>();
            Map<String, long[]> divAmt = new HashMap<>();
            for (SecurityDao.PeriodSum ps : securityDao.getPeriodSums(depot, fromMs, toMs)) {
                String a = ps.action == null ? "" : ps.action;
                if ("buy".equals(a) || "reinvest".equals(a)) {
                    boughtShares.computeIfAbsent(ps.kmyId, k -> new double[1])[0] += ps.shares;
                    buyAmt.computeIfAbsent(ps.kmyId, k -> new long[1])[0] += ps.amount;
                } else if ("add".equals(a)) {
                    // Einbuchung: nur der manuell gesetzte Geldwert zählt zum Einstandspreis, die
                    // Stückzahl bleibt bewusst außen vor (läuft bereits korrekt über netShares/-AtEnd).
                    buyAmt.computeIfAbsent(ps.kmyId, k -> new long[1])[0] += ps.amount;
                } else if ("sell".equals(a) || "remove".equals(a)) {
                    sellAmt.computeIfAbsent(ps.kmyId, k -> new long[1])[0] += ps.amount;
                } else if ("dividend".equals(a)) {
                    divAmt.computeIfAbsent(ps.kmyId, k -> new long[1])[0] += gross ? ps.amount : ps.net;
                }
            }
            List<Repository.DepotChartRow> result = new ArrayList<>();
            for (Security s : securityDao.getSecurities(depot)) {
                String id = s.kmyId;
                long div = divAmt.containsKey(id) ? divAmt.get(id)[0] : 0;
                long buy = buyAmt.containsKey(id) ? buyAmt.get(id)[0] : 0;
                long sell = sellAmt.containsKey(id) ? sellAmt.get(id)[0] : 0;
                double sharesForValue = wholePeriod
                        ? (netShares.containsKey(id) ? netShares.get(id) : 0.0)
                        : (boughtShares.containsKey(id) ? boughtShares.get(id)[0] : 0.0);
                if (sharesForValue < 0) {
                    sharesForValue = 0;   // im Zeitraum netto verkauft → keine aufgebaute Position (kein negativer Wert)
                }
                long value = Math.round(sharesForValue * s.price * 100.0);
                long netDeposits = buy - sell - div;
                // Komplett verkauft = Netto-Bestand am Ende des Zeitraums ~ 0 (spätere Verkäufe zählen noch nicht).
                double netHeldAtEnd = netSharesAtEnd.containsKey(id) ? netSharesAtEnd.get(id) : 0.0;
                boolean fullySold = Math.abs(netHeldAtEnd) < de.spahr.ausgaben.util.SecurityAmounts.SHARE_EPSILON;
                result.add(new Repository.DepotChartRow(s.name, value, netDeposits, div, buy, fullySold));
            }
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    private static DepotMetrics metricsFrom(long valueCents, List<SecurityDao.ActionSum> sums,
                                            boolean grossDividends) {
        long buy = 0, sell = 0, dividend = 0;
        for (SecurityDao.ActionSum s : sums) {
            String a = s.action == null ? "" : s.action;
            // Einbuchung = wie Kauf, Ausbuchung = wie Verkauf – zählt nur, wenn ein manueller Wert
            // gesetzt wurde (KMyMoney liefert dafür sonst immer 0, siehe getActionSums/-BySecurity).
            if ("buy".equals(a) || "reinvest".equals(a) || "add".equals(a)) {
                buy += s.amount;
            } else if ("sell".equals(a) || "remove".equals(a)) {
                sell += s.amount;
            } else if ("dividend".equals(a)) {
                // Brutto = amount (Einnahme-Split), Netto = net (gutgeschriebenes Geld).
                dividend += grossDividends ? s.amount : s.net;
            }
        }
        return new DepotMetrics(valueCents, buy, sell, dividend);
    }

    /** Einstellung „Dividenden brutto anzeigen" (Standard true). Auf dem Executor-Thread gelesen. */
    private boolean dividendsGross() {
        return new de.spahr.ausgaben.settings.SettingsStore(appContext).isDividendGross();
    }
}
