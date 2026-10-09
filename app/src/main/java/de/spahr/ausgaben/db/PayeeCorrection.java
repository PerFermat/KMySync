package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Ein gelernter Alias für die Spracherkennung: ein (falsch) erkannter Begriff {@link #spoken} (klein
 * geschrieben abgelegt) und der richtige Empfänger {@link #corrected}. Zusätzlich können – damit ein Alias
 * jede Buchungsart abdeckt – ein {@link #account}, je bis zu zwei Kategorien für Einnahmen/Ausgaben sowie
 * Von-/Bis-Konto für Umbuchungen hinterlegt werden. Wird bei der Sprach-Erfassung konsultiert.
 */
// Unique über (spoken, corrected): derselbe gesprochene Begriff darf mehrfach vorkommen, solange er auf
// verschiedene Empfänger zeigt (z. B. „rewe" → „Rewe Ort1" und „rewe" → „Rewe Ort2", per GPS unterschieden).
@Entity(tableName = "payee_correction",
        indices = {@Index(value = {"spoken", "corrected"}, unique = true)})
public class PayeeCorrection {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "spoken")
    public String spoken = "";

    @NonNull
    @ColumnInfo(name = "corrected")
    public String corrected = "";

    @ColumnInfo(name = "created_at")
    public long createdAt;

    /** Konto für Einnahme/Ausgabe (leer = Standardkonto). */
    @NonNull
    @ColumnInfo(name = "account")
    public String account = "";

    /** Bis zu zwei Kategorien für Einnahmen. */
    @NonNull
    @ColumnInfo(name = "cat_income_1")
    public String catIncome1 = "";

    @NonNull
    @ColumnInfo(name = "cat_income_2")
    public String catIncome2 = "";

    /** Bis zu zwei Kategorien für Ausgaben. */
    @NonNull
    @ColumnInfo(name = "cat_expense_1")
    public String catExpense1 = "";

    @NonNull
    @ColumnInfo(name = "cat_expense_2")
    public String catExpense2 = "";

    /**
     * Die Seite jeder der vier Kategorien: {@code true} = Einnahmekategorie, {@code false} =
     * Ausgabekategorie, {@code null} = unbekannt (Alias aus der Zeit vor diesen Feldern, oder das Feld
     * ist leer). {@code catIncome…} heißt „für Einnahmen vorgesehen", nicht „Einnahmekategorie" – ein
     * aus einer Erstattung gelernter Alias trägt dort eine Ausgabekategorie. Deshalb steht die Seite
     * eigens daneben und wird mit der Kategorie in die Buchung übernommen.
     */
    @ColumnInfo(name = "cat_income_1_is_income")
    public Boolean catIncome1IsIncome;

    @ColumnInfo(name = "cat_income_2_is_income")
    public Boolean catIncome2IsIncome;

    @ColumnInfo(name = "cat_expense_1_is_income")
    public Boolean catExpense1IsIncome;

    @ColumnInfo(name = "cat_expense_2_is_income")
    public Boolean catExpense2IsIncome;

    /** Quell-/Zielkonto für Umbuchungen. */
    @NonNull
    @ColumnInfo(name = "from_account")
    public String fromAccount = "";

    @NonNull
    @ColumnInfo(name = "to_account")
    public String toAccount = "";

    /** Ort für Einnahme/Ausgabe (leer = keiner/Standardort). */
    @NonNull
    @ColumnInfo(name = "place")
    public String place = "";

    /** Von-/Nach-Ort für Umbuchungen (leer = keiner). */
    @NonNull
    @ColumnInfo(name = "from_place")
    public String fromPlace = "";

    @NonNull
    @ColumnInfo(name = "to_place")
    public String toPlace = "";

    /** true = dieser Alias wird vor der Suche in bestehenden Buchungen berücksichtigt. */
    @ColumnInfo(name = "preferred")
    public boolean preferred;

    /** Bevorzugte Buchungsart (income/expense/transfer); leer = Ausgabe. Am Phone maßgeblich; Wear nimmt
     * die per Knopf mitgegebene Art. */
    @NonNull
    @ColumnInfo(name = "type")
    public String type = "";

    /** Standort des Alias (beim Lernen aus der Buchung übernommen); {@code 0/0} = keiner. Für die
     * Betrag-only-Erfassung: passt der aktuelle Standort (≤100 m), liefert der Alias die Buchungsdaten. */
    @ColumnInfo(name = "lat")
    public double lat;

    @ColumnInfo(name = "lon")
    public double lon;

    /** Beliebig viele Standorte im Format {@code "lat,lon;lat,lon;…"} (US-Dezimalpunkt). {@link #lat}/{@link #lon}
     * spiegeln die erste Koordinate (Rückwärtskompatibilität). Leer = kein Standort. */
    @NonNull
    @ColumnInfo(name = "gps_list")
    public String gpsList = "";

    /** Rangband der üblichen Beträge in Prozent (Vorgabe 10–90) – das Betragssieb der Standort-Auflösung,
     * siehe {@link PayeeAmounts}. Wer eng zieht, läßt seinen Empfänger nur bei typischen Beträgen gewinnen. */
    @ColumnInfo(name = "pct_low", defaultValue = "10")
    public float pctLow = PayeeAmounts.DEFAULT_LOW;

    @ColumnInfo(name = "pct_high", defaultValue = "90")
    public float pctHigh = PayeeAmounts.DEFAULT_HIGH;

    /**
     * Die Stichwörter dieses Empfängers (siehe {@link BookingTags}) – anders als die Kategorien ohne
     * Trennung nach Einnahme und Ausgabe, die ein Stichwort nicht kennt. Sie belegen eine neue
     * Buchung vor und stehen im Vorspann der Auswahlliste (siehe {@link PayeeTags}).
     */
    @NonNull
    @ColumnInfo(name = "tags", defaultValue = "")
    public String tags = "";

    public PayeeCorrection() {
    }

    /** Alle hinterlegten Standorte; fällt bei leerer {@link #gpsList} auf {@link #lat}/{@link #lon} zurück. */
    public java.util.List<double[]> gpsPoints() {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (gpsList != null && !gpsList.trim().isEmpty()) {
            for (String pair : gpsList.split(";")) {
                String[] xy = pair.split(",");
                if (xy.length == 2) {
                    try {
                        out.add(new double[]{Double.parseDouble(xy[0].trim()),
                                Double.parseDouble(xy[1].trim())});
                    } catch (NumberFormatException ignore) {
                        // fehlerhafte Koordinate überspringen
                    }
                }
            }
        } else if (lat != 0 || lon != 0) {
            out.add(new double[]{lat, lon});
        }
        return out;
    }

    /** Setzt {@link #gpsList} (6 Nachkommastellen) und spiegelt die erste Koordinate nach {@link #lat}/{@link #lon}. */
    public void setGpsPoints(java.util.List<double[]> points) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : points) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(String.format(java.util.Locale.US, "%.6f,%.6f", p[0], p[1]));
        }
        gpsList = sb.toString();
        if (!points.isEmpty()) {
            lat = points.get(0)[0];
            lon = points.get(0)[1];
        } else {
            lat = 0;
            lon = 0;
        }
    }

    @Ignore
    public PayeeCorrection(@NonNull String spoken, @NonNull String corrected, long createdAt) {
        this.spoken = spoken;
        this.corrected = corrected;
        this.createdAt = createdAt;
    }
}
