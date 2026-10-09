package de.spahr.ausgaben.db;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

import de.spahr.ausgaben.db.Repository.Callback;
import de.spahr.ausgaben.db.Repository.VoiceResolution;

/**
 * Alias-Verwaltung und Sprach-/Standort-Auflösung: gelernte Empfänger-Aliase (CRUD) sowie das Auffinden einer
 * Vorlage-Buchung bzw. eines Alias zu einem gesprochenen Begriff oder einer Position. Reine Lese-Auflösung –
 * das eigentliche Anlegen der Uhr-/Sprachbuchung bleibt in {@link Repository}. Kollaborator hinter der Fassade.
 */
class AliasResolver {

    private final BookingDao bookingDao;
    private final PayeeCorrectionDao correctionDao;
    private final AccountDao accountDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    AliasResolver(BookingDao bookingDao, PayeeCorrectionDao correctionDao, AccountDao accountDao,
                  ExecutorService executor, Handler mainHandler) {
        this.bookingDao = bookingDao;
        this.correctionDao = correctionDao;
        this.accountDao = accountDao;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    /**
     * Sucht einen gelernten Alias zum Begriff – gleiche Logik wie die Buchungssuche: exakter Teilstring
     * ({@code spoken}), sonst unscharf über alle bekannten {@code spoken}. Liefert den vollen Alias oder
     * {@code null}.
     */
    private PayeeCorrection matchAlias(String term, boolean preferred, double[] coords,
                                       java.util.Set<String> closed, String type) {
        String t = term == null ? "" : term.trim();
        if (t.isEmpty()) {
            return null;
        }
        int pref = preferred ? 1 : 0;
        List<PayeeCorrection> matches = correctionDao.findAllBySpokenLike(t, pref);
        if (matches.isEmpty()) {
            String bestSpoken = de.spahr.ausgaben.voice.VoiceInput.bestFuzzyPayee(
                    t, correctionDao.getSpokenByPreferred(pref));
            if (bestSpoken != null) {
                matches = correctionDao.findAllBySpokenExact(bestSpoken, pref);
            }
        }
        // Nur offene (nicht auf geschlossene Konten zeigende) Aliase, und – im typgefilterten Durchlauf –
        // nur solche, die die für die gewünschte Buchungsart erforderlichen Daten mitbringen.
        List<PayeeCorrection> open = new ArrayList<>();
        for (PayeeCorrection a : matches) {
            if (!aliasBlocked(a, closed) && aliasSupports(a, type)) {
                open.add(a);
            }
        }
        if (open.isEmpty()) {
            return null;
        }
        // Mehrere gleichnamige Aliase + bekannter Standort → den nächstgelegenen wählen. Der Nutzer hat den
        // Empfänger explizit genannt, daher ohne Radius-Deckel. Sonst der neueste (open ist created_at DESC).
        if (open.size() > 1 && coords != null) {
            PayeeCorrection near = nearestAliasUncapped(coords[0], coords[1], open);
            if (near != null) {
                return near;
            }
        }
        return open.get(0);
    }

    /** Kleinste Entfernung des aktuellen Standorts zu einer der hinterlegten Alias-Koordinaten. */
    private double nearestPointMeters(double lat, double lon, PayeeCorrection a) {
        double best = Double.MAX_VALUE;
        for (double[] p : a.gpsPoints()) {
            double d = de.spahr.ausgaben.location.Geo.distanceMeters(lat, lon, p[0], p[1]);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    /** Nächstgelegener Alias mit Standort, ohne Radius-Deckel (für explizit genannte Empfänger). */
    private PayeeCorrection nearestAliasUncapped(double lat, double lon, List<PayeeCorrection> list) {
        PayeeCorrection best = null;
        double bestD = Double.MAX_VALUE;
        for (PayeeCorrection a : list) {
            double d = nearestPointMeters(lat, lon, a);
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        return best;
    }

    /**
     * Auflösung in der gewünschten Reihenfolge: zuerst die <b>bevorzugten</b> Aliase, dann die bestehenden
     * Buchungen, erst danach die übrigen Aliase. Setzt {@code out[0]}=Vorlage-Buchung, {@code out[1]}=Alias.
     * Geschlossene Konten ({@code closed}) werden übersprungen.
     */
    void resolve(String term, double[] coords, java.util.Set<String> closed, String type,
                 Booking[] outBooking, PayeeCorrection[] outAlias) {
        // Globaler Zweipass: erst der komplette Ablauf nur mit typpassenden Kandidaten; findet der gar
        // nichts, derselbe Ablauf erneut ohne Typfilter (dann darf auch ein typfremder Treffer gewinnen).
        if (resolvePass(term, coords, closed, type, outBooking, outAlias)) {
            return;
        }
        if (type != null) {
            resolvePass(term, coords, closed, null, outBooking, outAlias);
        }
    }

    private boolean resolvePass(String term, double[] coords, java.util.Set<String> closed, String type,
                                Booking[] outBooking, PayeeCorrection[] outAlias) {
        PayeeCorrection alias = matchAlias(term, true, coords, closed, type);
        if (alias != null) {
            outAlias[0] = alias;
            return true;
        }
        Booking booking = findVoiceTemplate(term, coords, closed, type);
        if (booking != null) {
            outBooking[0] = booking;
            return true;
        }
        alias = matchAlias(term, false, coords, closed, type);
        if (alias != null) {
            outAlias[0] = alias;
            return true;
        }
        return false;
    }

    /**
     * Bringt der Alias die für {@code type} erforderlichen Daten mit? Umbuchung ⇒ Von- <b>und</b>
     * Nach-Konto; Einnahme ⇒ eine Einnahme-Kategorie; Ausgabe ⇒ eine Ausgabe-Kategorie. Ohne Typ ({@code
     * null}) immer {@code true}. Maßgeblich sind die vorhandenen Daten, nicht die bevorzugte Buchungsart.
     */
    private static boolean aliasSupports(PayeeCorrection a, String type) {
        if (type == null || a == null) {
            return true;
        }
        if (Repository.VOICE_TYPE_TRANSFER.equals(type)) {
            return !isBlank(a.fromAccount) && !isBlank(a.toAccount);
        }
        if (Repository.VOICE_TYPE_INCOME.equals(type)) {
            return !isBlank(a.catIncome1) || !isBlank(a.catIncome2);
        }
        if (Repository.VOICE_TYPE_EXPENSE.equals(type)) {
            return !isBlank(a.catExpense1) || !isBlank(a.catExpense2);
        }
        return true;
    }

    /** Passt die Buchung als Vorlage zu {@code type}? Über die eigene Art (Umbuchung/Einnahme/Ausgabe). */
    private static boolean bookingSupports(Booking b, String type) {
        if (type == null || b == null) {
            return true;
        }
        if (Repository.VOICE_TYPE_TRANSFER.equals(type)) {
            return b.isTransfer;
        }
        if (Repository.VOICE_TYPE_INCOME.equals(type)) {
            return !b.isTransfer && b.isIncome;
        }
        if (Repository.VOICE_TYPE_EXPENSE.equals(type)) {
            return !b.isTransfer && !b.isIncome;
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static List<Booking> filterBookings(List<Booking> list, String type) {
        if (type == null) {
            return list;
        }
        List<Booking> out = new ArrayList<>();
        for (Booking b : list) {
            if (bookingSupports(b, type)) {
                out.add(b);
            }
        }
        return out;
    }

    private static List<PayeeCorrection> filterAliases(List<PayeeCorrection> list, String type) {
        if (type == null) {
            return list;
        }
        List<PayeeCorrection> out = new ArrayList<>();
        for (PayeeCorrection a : list) {
            if (aliasSupports(a, type)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Ein Empfänger im Umkreis: sein Name, die Vorlage, aus der er stammt, und sein Betragsurteil. */
    static final class Candidate {
        /** Empfängername in der Schreibweise der Quelle. */
        public final String name;
        /** Vorlage-Buchung, falls er aus einer Buchung stammt – sonst {@code null}. */
        final Booking booking;
        /** Alias, falls er aus einem Alias stammt – sonst {@code null}. */
        final PayeeCorrection alias;
        /** Passt der Betrag zu diesem Empfänger? Ohne Betrag {@link PayeeAmounts.Verdict#UNKNOWN}. */
        public PayeeAmounts.Verdict verdict = PayeeAmounts.Verdict.UNKNOWN;
        /** Um welchen Faktor liegt der Betrag neben seinem Band? Siehe {@link PayeeAmounts#gap}. */
        public double gap = PayeeAmounts.GAP_UNKNOWN;

        Candidate(String name, Booking booking, PayeeCorrection alias) {
            this.name = name;
            this.booking = booking;
            this.alias = alias;
        }
    }

    /**
     * Auflösung per Standort (Betrag-only): innerhalb {@link de.spahr.ausgaben.location.Geo#RADIUS_M} in
     * strenger Reihenfolge – bevorzugte Aliase → Buchungen → übrige Aliase; der erste Tier mit Treffer
     * gewinnt, innerhalb eines Tiers der nächstgelegene. Geschlossene Konten werden übersprungen.
     *
     * <p>Stehen mehrere Empfänger am selben Ort, ordnet zusätzlich der <b>Betrag</b> ({@link
     * #byAmount}): wer ihn nachweislich hat, geht vor – auch vor einem bevorzugten Alias. Paßt er zu
     * keinem, gewinnt der betraglich nächste, denn ein zweifelhafter Empfänger ist besser als keiner.</p>
     *
     * @param scope die angezeigten Konten ({@link AccountScope}); leer = alle
     */
    void resolveGps(double lat, double lon, java.util.Set<String> closed, java.util.Set<String> scope,
                    String type, long amountCents, Booking[] outBooking, PayeeCorrection[] outAlias) {
        // Globaler Zweipass (100-m-Radius bleibt in beiden Durchläufen, nur der Typfilter fällt im 2. weg).
        // Fürs Betragsurteil gilt aber immer die gewünschte Buchungsart – der Filter grenzt nur die
        // Kandidaten ein, die Buchung entsteht so oder so als das, was die Uhr angefordert hat.
        Candidate c = pickGps(lat, lon, closed, scope, type, amountCents, type);
        if (c == null && type != null) {
            c = pickGps(lat, lon, closed, scope, null, amountCents, type);
        }
        if (c != null) {
            outBooking[0] = c.booking;
            outAlias[0] = c.alias;
        }
    }

    /** Der beste Kandidat eines Durchlaufs – dieselbe Rangfolge wie in der Ziffernmaske. */
    private Candidate pickGps(double lat, double lon, java.util.Set<String> closed,
                              java.util.Set<String> scope, String filterType,
                              long amountCents, String judgeType) {
        List<Candidate> alle = byAmount(
                nearbyCandidates(lat, lon, closed, scope, filterType, amountCents, judgeType));
        return alle.isEmpty() ? null : alle.get(0);
    }

    /**
     * Die Kandidaten nach dem Betrag geordnet: vorn die, in deren Band er liegt (untereinander in der
     * gewohnten Rangfolge nach Nähe), dahinter die übrigen nach ihrem Betragsabstand
     * ({@link PayeeAmounts#gap}) – der am wenigsten Verfehlte zuerst, wer kein Urteil hat, mittig.
     *
     * <p>Aussortiert wird niemand: sonst stünde bei einem Betrag, den hier noch keiner hatte,
     * plötzlich niemand mehr zur Wahl.</p>
     */
    private static List<Candidate> byAmount(List<Candidate> alle) {
        List<Candidate> passend = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        for (Candidate c : alle) {
            (c.verdict == PayeeAmounts.Verdict.FITS ? passend : rest).add(c);
        }
        // Stabil: bei gleichem Abstand bleibt es bei der Reihenfolge nach Nähe und Rang.
        java.util.Collections.sort(rest, (x, y) -> Double.compare(x.gap, y.gap));
        passend.addAll(rest);
        return passend;
    }

    /**
     * Alle Empfänger im 100-m-Umkreis, entdoppelt und in der gewohnten Rangfolge (bevorzugte Aliase →
     * Buchungen → übrige Aliase, je Stufe der nächstgelegene zuerst), jeder mit seinem Betragsurteil.
     *
     * <p>Die Koordinaten entscheiden, <b>wer</b> Kandidat ist; die Beträge steuert der volle Bestand
     * dieses Namens bei – auch Buchungen ohne Standort.</p>
     *
     * <p>Ist eine Kontoauswahl angezeigt, zählen nur deren Empfänger: wer in der Ansicht „Bargeld"
     * bucht, meint keinen, den es bisher nur auf dem Girokonto gab.</p>
     *
     * @param scope      die angezeigten Konten ({@link AccountScope}); leer = alle
     * @param filterType grenzt die Kandidaten auf eine Buchungsart ein ({@code null} = alle)
     * @param amountCents zu prüfender Betrag; {@code <= 0} = kein Urteil
     * @param judgeType  Buchungsart, deren bisherige Beträge das Urteil tragen
     */
    List<Candidate> nearbyCandidates(double lat, double lon, java.util.Set<String> closed,
                                     java.util.Set<String> scope, String filterType,
                                     long amountCents, String judgeType) {
        java.util.Map<String, Candidate> gefunden = new java.util.LinkedHashMap<>();
        for (PayeeCorrection a : nearAliases(lat, lon, scopedAliases(
                filterAliases(openAliases(correctionDao.getWithGps(1), closed), filterType), scope))) {
            merke(gefunden, a.corrected, null, a);
        }
        for (Booking b : nearBookings(lat, lon, scopedBookings(
                filterBookings(openBookings(bookingDao.getWithGpsNote(), closed), filterType), scope))) {
            merke(gefunden, b.payee, b, null);
        }
        for (PayeeCorrection a : nearAliases(lat, lon, scopedAliases(
                filterAliases(openAliases(correctionDao.getWithGps(0), closed), filterType), scope))) {
            merke(gefunden, a.corrected, null, a);
        }

        List<Candidate> out = new ArrayList<>(gefunden.values());
        if (amountCents > 0) {
            for (Candidate c : out) {
                float[] band = PayeeAmounts.bandOf(correctionDao.findAllByCorrected(c.name));
                List<Long> bisher = amountsOf(c.name, judgeType);
                c.verdict = PayeeAmounts.judge(bisher, amountCents, band[0], band[1]);
                c.gap = PayeeAmounts.gap(bisher, amountCents, band[0], band[1]);
            }
        }
        return out;
    }

    /**
     * Die bisherigen Beträge dieses Empfängers für eine Buchungsart. Bei <b>Umbuchungen</b> zählt nur
     * die Ausgangsseite: jede Umbuchung liegt als zwei Zeilen mit demselben Betrag vor, gezählt würde
     * sonst doppelt und die Fünfergrenze wäre schon bei drei Umbuchungen erreicht.
     */
    private List<Long> amountsOf(String payee, String type) {
        boolean transfer = Repository.VOICE_TYPE_TRANSFER.equals(type);
        boolean income = !transfer && Repository.VOICE_TYPE_INCOME.equals(type);
        return bookingDao.getAmountsByPayee(payee, income, transfer);
    }

    /** Nimmt den Empfänger auf, sofern er nicht schon (aus einer besseren Stufe) dabei ist. */
    private static void merke(java.util.Map<String, Candidate> gefunden, String name,
                              Booking booking, PayeeCorrection alias) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (!gefunden.containsKey(key)) {
            gefunden.put(key, new Candidate(name.trim(), booking, alias));
        }
    }

    /** Namen der geschlossenen Konten (läuft bereits auf dem Executor-Thread). */
    java.util.Set<String> closedAccounts() {
        return new java.util.HashSet<>(accountDao.getClosedNames());
    }

    /** True, wenn der Alias auf ein geschlossenes (Ziel-)Konto zeigt und daher nicht gewählt werden darf. */
    private static boolean aliasBlocked(PayeeCorrection a, java.util.Set<String> closed) {
        if (a == null || closed == null || closed.isEmpty()) {
            return false;
        }
        return closed.contains(a.account) || closed.contains(a.fromAccount) || closed.contains(a.toAccount);
    }

    private static List<Booking> openBookings(List<Booking> list, java.util.Set<String> closed) {
        if (closed == null || closed.isEmpty()) {
            return list;
        }
        List<Booking> out = new ArrayList<>();
        for (Booking b : list) {
            if (!closed.contains(b.account)) {
                out.add(b);
            }
        }
        return out;
    }

    /** Nur die Buchungen der angezeigten Konten; leere Auswahl läßt alles stehen. */
    private static List<Booking> scopedBookings(List<Booking> list, java.util.Set<String> scope) {
        if (scope == null || scope.isEmpty()) {
            return list;
        }
        List<Booking> out = new ArrayList<>();
        for (Booking b : list) {
            if (AccountScope.covers(scope, b)) {
                out.add(b);
            }
        }
        return out;
    }

    /** Nur die Aliase, die kein anderes Konto nennen; leere Auswahl läßt alles stehen. */
    private static List<PayeeCorrection> scopedAliases(List<PayeeCorrection> list,
                                                       java.util.Set<String> scope) {
        if (scope == null || scope.isEmpty()) {
            return list;
        }
        List<PayeeCorrection> out = new ArrayList<>();
        for (PayeeCorrection a : list) {
            if (AccountScope.covers(scope, a)) {
                out.add(a);
            }
        }
        return out;
    }

    private static List<PayeeCorrection> openAliases(List<PayeeCorrection> list, java.util.Set<String> closed) {
        if (closed == null || closed.isEmpty()) {
            return list;
        }
        List<PayeeCorrection> out = new ArrayList<>();
        for (PayeeCorrection a : list) {
            if (!aliasBlocked(a, closed)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Die Aliase innerhalb des 100-m-Umkreises, der nächstgelegene zuerst. */
    private List<PayeeCorrection> nearAliases(double lat, double lon, List<PayeeCorrection> list) {
        List<PayeeCorrection> out = new ArrayList<>();
        final java.util.Map<PayeeCorrection, Double> meter = new java.util.IdentityHashMap<>();
        for (PayeeCorrection a : list) {
            double d = nearestPointMeters(lat, lon, a);
            if (d <= de.spahr.ausgaben.location.Geo.RADIUS_M) {
                meter.put(a, d);
                out.add(a);
            }
        }
        java.util.Collections.sort(out, (x, y) -> Double.compare(meter.get(x), meter.get(y)));
        return out;
    }

    /** Die Buchungen mit Standortmarke innerhalb des 100-m-Umkreises, die nächstgelegene zuerst. */
    private List<Booking> nearBookings(double lat, double lon, List<Booking> list) {
        List<Booking> out = new ArrayList<>();
        final java.util.Map<Booking, Double> meter = new java.util.IdentityHashMap<>();
        for (Booking b : list) {
            double[] ll = de.spahr.ausgaben.location.Geo.parse(b.note);
            if (ll == null) {
                continue;
            }
            double d = de.spahr.ausgaben.location.Geo.distanceMeters(lat, lon, ll[0], ll[1]);
            if (d <= de.spahr.ausgaben.location.Geo.RADIUS_M) {
                meter.put(b, d);
                out.add(b);
            }
        }
        java.util.Collections.sort(out, (x, y) -> Double.compare(meter.get(x), meter.get(y)));
        return out;
    }

    /** Speichert einen Alias autoritativ (Editor): ersetzt einen bestehenden mit gleichem
     * {@code spoken}+{@code corrected}; die im UI zusammengestellte Standortliste gilt (inkl. Löschungen). */
    void saveAlias(final PayeeCorrection alias) {
        saveAlias(alias, false);
    }

    /**
     * @param mergeGps beim Lernen ({@code true}): die Standorte des übergebenen Alias werden an die des
     *                 bestehenden Alias <b>angehängt</b> (Duplikate übersprungen), statt sie zu ersetzen.
     */
    void saveAlias(final PayeeCorrection alias, final boolean mergeGps) {
        if (alias == null) {
            return;
        }
        executor.execute(() -> {
            alias.spoken = alias.spoken == null ? "" : alias.spoken.trim().toLowerCase(Locale.GERMANY);
            alias.corrected = alias.corrected == null ? "" : alias.corrected.trim();
            if (alias.spoken.isEmpty() || alias.corrected.isEmpty()) {
                return;
            }
            if (alias.createdAt == 0) {
                alias.createdAt = System.currentTimeMillis();
            }
            java.util.List<double[]> points = alias.gpsPoints();
            if (mergeGps) {
                PayeeCorrection existing = correctionDao.findBySpokenCorrected(alias.spoken, alias.corrected);
                java.util.List<double[]> merged = existing != null ? existing.gpsPoints()
                        : new java.util.ArrayList<>();
                for (double[] p : points) {
                    if (!containsPoint(merged, p)) {
                        merged.add(p);
                    }
                }
                points = merged;
            }
            alias.setGpsPoints(points);
            correctionDao.upsert(alias);
        });
    }

    /** Ob {@code p} (auf ~5 Nachkommastellen) bereits in der Liste steht. */
    private static boolean containsPoint(java.util.List<double[]> list, double[] p) {
        for (double[] q : list) {
            if (Math.abs(q[0] - p[0]) < 1e-5 && Math.abs(q[1] - p[1]) < 1e-5) {
                return true;
            }
        }
        return false;
    }

    void getAllAliases(final Callback<List<PayeeCorrection>> callback) {
        executor.execute(() -> {
            final List<PayeeCorrection> result = correctionDao.getAll();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Die Empfänger in der Nähe von {@code lat/lon} für den Vorspann der Vorschlagsliste – aus denselben
     * beiden Quellen wie der Umkreis-Filter (GPS-Marke der Buchungen, gelernte Alias-Standorte).
     */
    void getNearbyPayees(final double lat, final double lon, final Callback<List<String>> callback) {
        executor.execute(() -> {
            final List<String> result = de.spahr.ausgaben.location.NearbyPayees.rank(
                    bookingDao.getWithGpsNote(), correctionDao.getAll(), lat, lon);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Die bisherigen Beträge dieses Empfängers, aufsteigend sortiert – Grundlage des Reglers im
     * Alias-Formular ({@link PayeeAmounts}).
     */
    void getPayeeAmounts(final String payee, final String type, final Callback<long[]> callback) {
        executor.execute(() -> {
            final long[] result = PayeeAmounts.sorted(amountsOf(payee, type));
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Die Kategorien dieses Empfängers für die Vorbelegung im Editor: bevorzugte Aliase, Buchungen,
     * übrige Aliase – die Reihenfolge macht {@link PayeeCategories#rank}.
     */
    void getPayeeCategories(final String payee, final boolean income,
                            final Callback<List<PayeeCategory>> callback) {
        executor.execute(() -> {
            final List<PayeeCategory> result = PayeeCategories.ranked(
                    correctionDao.findAllByCorrected(payee),
                    bookingDao.getCategoriesByPayee(payee, income), income);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Die Stichwörter dieses Empfängers: der Vorspann der Auswahlliste und die Vorbelegung einer
     * neuen Buchung. Beides in einem Durchgang, damit es bei einer Abfrage bleibt.
     */
    void getPayeeTags(final String payee, final Callback<PayeeTagSuggestion> callback) {
        executor.execute(() -> {
            List<PayeeCorrection> aliases = correctionDao.findAllByCorrected(payee);
            final PayeeTagSuggestion result = new PayeeTagSuggestion(
                    PayeeTags.rank(aliases, bookingDao.getTagsByPayee(payee)),
                    PayeeTags.preset(aliases));
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    void getAlias(final long id, final Callback<PayeeCorrection> callback) {
        executor.execute(() -> {
            final PayeeCorrection result = correctionDao.getById(id);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    void deleteAlias(final long id, final Runnable onDone) {
        executor.execute(() -> {
            correctionDao.deleteById(id);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        return b == null ? "" : b.trim();
    }

    /** Hängt „GPS: lat, lon" an die Notiz an (gleiche Form wie am Phone); ersetzt einen alten GPS-Zusatz. */
    static String appendGps(String note, String coords) {
        if (coords == null || coords.trim().isEmpty()) {
            return note == null ? "" : note;
        }
        String base = (note == null ? "" : note).replaceAll("\\s*GPS:.*$", "").replaceAll("\\s+$", "");
        String tag = "GPS: " + coords.trim();
        return base.isEmpty() ? tag : base + " " + tag;
    }

    /**
     * Vorlage-Buchung per Empfänger suchen (exakter Teilstring, sonst unscharf). Gibt es mehrere Treffer
     * und ist die aktuelle Position {@code coords} bekannt, wird der zur Position nächstgelegene Treffer
     * mit GPS-Notiz gewählt – sonst die neueste Buchung.
     */
    private Booking findVoiceTemplate(String term, double[] coords, java.util.Set<String> closed,
                                      String type) {
        if (term == null || term.isEmpty()) {
            return null;
        }
        Booking template = pickTemplate(
                filterBookings(openBookings(bookingDao.findByPayeeLike(term), closed), type), coords);
        if (template == null) {
            String best = de.spahr.ausgaben.voice.VoiceInput.bestFuzzyPayee(
                    term, bookingDao.getDistinctPayees());
            if (best != null) {
                template = pickTemplate(
                        filterBookings(openBookings(bookingDao.findByPayeeLike(best), closed), type), coords);
            }
        }
        return template;
    }

    /** Aus den Treffern die zur Position nächste Buchung mit GPS-Notiz, sonst die erste (= neueste). */
    private Booking pickTemplate(List<Booking> matches, double[] coords) {
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        if (coords != null) {
            Booking near = nearestByNote(coords[0], coords[1], matches);
            if (near != null) {
                return near;
            }
        }
        return matches.get(0);
    }

    /**
     * Nächstgelegene Buchung mit GPS-Notiz aus der Liste – <b>ohne</b> 100‑m‑Deckel (der Empfänger wurde
     * explizit genannt, es soll der geografisch nächste unter den gleichnamigen Empfängern gewinnen).
     */
    private Booking nearestByNote(double lat, double lon, List<Booking> list) {
        Booking best = null;
        double bestD = Double.MAX_VALUE;
        for (Booking b : list) {
            double[] ll = de.spahr.ausgaben.location.Geo.parse(b.note);
            if (ll == null) {
                continue;
            }
            double d = de.spahr.ausgaben.location.Geo.distanceMeters(lat, lon, ll[0], ll[1]);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /**
     * Löst einen gesprochenen Empfänger für die Sprach-Erfassung auf: zuerst über die bevorzugten Aliase,
     * dann über bestehende Buchungen, erst danach über die übrigen Aliase. Liefert Vorlage-Buchung und/oder
     * Alias sowie den – ggf. ersetzten – Empfänger für die Vorbelegung.
     */
    void resolveVoice(final String term, final String coords,
                      final Callback<VoiceResolution> callback) {
        executor.execute(() -> {
            String t = term == null ? "" : term.trim();
            Booking[] booking = new Booking[1];
            PayeeCorrection[] alias = new PayeeCorrection[1];
            resolve(t, de.spahr.ausgaben.location.Geo.parse(coords), closedAccounts(), null, booking, alias);
            String payee = alias[0] != null ? alias[0].corrected : t;
            final Booking fb = booking[0];
            final PayeeCorrection fa = alias[0];
            final String fp = payee;
            mainHandler.post(() -> callback.onResult(new VoiceResolution(fb, fa, fp)));
        });
    }

    /**
     * Auflösung für die Betrag-only-Erfassung am Phone: sucht am Standort {@code coords} („lat, lon") eine
     * Vorlage (bevorzugte Aliase → Buchungen → übrige Aliase, 100 m). Ohne Standort/Treffer bleibt alles
     * leer → Editor wird nur mit dem Betrag geöffnet.
     */
    /**
     * Die Empfänger im 100-m-Umkreis, der beste zum Betrag vorn – für die Ziffernmaske, in der man
     * mit jedem Tipp einen weiter blättert.
     *
     * <p>Vorn stehen die, in deren Band der Betrag liegt (der nächstgelegene zuerst). Dahinter die
     * übrigen nach ihrem Betragsabstand ({@link PayeeAmounts#gap}), also der am wenigsten Verfehlte
     * zuerst; wer zu wenige Buchungen für ein Urteil hat, reiht sich dort mittig ein.</p>
     *
     * <p>Herausgeworfen wird niemand: blättern können muß man immer, sonst stünde bei einem Betrag,
     * den keiner je hatte, plötzlich niemand mehr zur Wahl.</p>
     *
     * @param amountCents bisher eingetippter Betrag; {@code <= 0} = noch keiner, dann zählt nur die Nähe
     * @param scope       die angezeigten Konten ({@link AccountScope}); leer = alle
     */
    void resolveNearby(final String coords, final long amountCents, final String type,
                       final java.util.Set<String> scope,
                       final Callback<List<VoiceResolution>> callback) {
        executor.execute(() -> {
            final List<VoiceResolution> out = new ArrayList<>();
            double[] ll = de.spahr.ausgaben.location.Geo.parse(coords);
            if (ll != null) {
                for (Candidate c : byAmount(nearbyCandidates(ll[0], ll[1], closedAccounts(), scope,
                        null, amountCents, type))) {
                    out.add(new VoiceResolution(c.booking, c.alias, c.name));
                }
            }
            mainHandler.post(() -> callback.onResult(out));
        });
    }

    /**
     * Der Empfänger, den der Betrag im 100-m-Umkreis <b>eindeutig</b> belegt – für die Vorbelegung im
     * Buchungseditor. Nur wenn genau einer im Band liegt; sonst {@code null}. Hier gilt keine
     * Notbremse: nicht vorbelegen ist besser als raten.
     *
     * <p>Ohne Kontoauswahl: im Editor steht das Konto im Formular und läßt sich jederzeit ändern.</p>
     */
    void suggestPayeeByAmount(final double lat, final double lon, final long amountCents,
                              final String type, final Callback<String> callback) {
        executor.execute(() -> {
            String treffer = null;
            for (Candidate c : nearbyCandidates(lat, lon, closedAccounts(),
                    java.util.Collections.emptySet(), null, amountCents, type)) {
                if (c.verdict != PayeeAmounts.Verdict.FITS) {
                    continue;
                }
                if (treffer != null) {
                    treffer = null;                 // zwei Treffer sind kein Beleg
                    break;
                }
                treffer = c.name;
            }
            final String result = treffer;
            mainHandler.post(() -> callback.onResult(result));
        });
    }
}
