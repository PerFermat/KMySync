package de.spahr.ausgaben.ui;

import android.content.Intent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import de.spahr.ausgaben.R;

/**
 * Die Standort-Zeile im Buchungseditor: welche Koordinaten die Buchung trägt, ihre Anzeige und die
 * Karten-Auswahl. Ausgelagert aus {@link BookingEditActivity}, um deren Umfang zu verringern – der Code
 * ist dabei unverändert umgezogen.
 *
 * <p>Registriert seinen Activity-Result-Launcher im Konstruktor und muss deshalb in {@code onCreate}
 * entstehen (vor STARTED).</p>
 */
class GpsRowController {

    /** Was die Zeile von der Buchungsmaske braucht. */
    interface Host {
        /** Reine Ansicht – kann sich nach {@code onCreate} noch ändern, deshalb jedes Mal gefragt. */
        boolean isReadOnly();

        /** Eine Umbuchung zeigt ohne Standort keine leere Standort-Zeile an. */
        boolean isTransferType();

        /** Standort-Erfassung in den Einstellungen eingeschaltet? */
        boolean isGpsEnabled();

        /** Der Standort hat sich geändert – Ausgabezeilen (und Empfänger in der Nähe) neu aufbauen. */
        void onGpsChanged();
    }

    private static final java.util.regex.Pattern GPS_PAIR = java.util.regex.Pattern.compile(
            "GPS:\\s*(-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final AppCompatActivity activity;
    private final Host host;
    private final View rowGps;
    private final TextView textGps;
    private final ImageButton btnNoteMap;
    private final ImageButton btnGpsClear;
    /** Karten-Auswahl (OpenStreetMap) für den Standort der Buchung. */
    private final ActivityResultLauncher<Intent> gpsMapLauncher;

    /** Zu speichernde Koordinaten „lat, lon" (aus Standort bzw. bestehender Buchung); null = keine. */
    private String gpsRowCoords;
    /** True, sobald der Standort auf der Karte manuell gewählt wurde – dann kein Überschreiben per Live-GPS. */
    private boolean gpsEditedByUser;

    GpsRowController(AppCompatActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.rowGps = activity.findViewById(R.id.rowGps);
        this.textGps = activity.findViewById(R.id.textGps);
        this.btnNoteMap = activity.findViewById(R.id.btnNoteMap);
        this.btnGpsClear = activity.findViewById(R.id.btnGpsClear);
        // Standort auf der Karte (OpenStreetMap) wählen/ändern – wie beim Alias. Die manuelle Wahl gewinnt
        // ab jetzt gegen den Live-GPS-Wert (siehe gpsEditedByUser).
        gpsMapLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                        double lat = result.getData().getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0);
                        double lon = result.getData().getDoubleExtra(MapPickerActivity.EXTRA_LON, 0);
                        gpsRowCoords = formatCoords(lat, lon);
                        gpsEditedByUser = true;
                        host.onGpsChanged();
                    }
                });
    }

    /** Die Koordinaten der Buchung „lat,lon", {@code null} = keine. */
    String coords() {
        return gpsRowCoords;
    }

    /** Setzt die Koordinaten, ohne die Zeile neu aufzubauen. */
    void setCoords(String coords) {
        gpsRowCoords = coords;
    }

    /** Übernimmt die Koordinaten aus dem {@code GPS:}-Tag einer Notiz (keiner → {@code null}). */
    void setFromNote(String note) {
        gpsRowCoords = parseGpsCoords(note);
    }

    /** Hat der Nutzer den Standort auf der Karte gewählt oder gelöscht? Dann gilt er, nicht der Live-Wert. */
    boolean editedByUser() {
        return gpsEditedByUser;
    }

    /** Die „lat, lon" hinter einem {@code GPS:}-Tag (exakt wie gespeichert), sonst {@code null}. */
    static String parseGpsCoords(String note) {
        if (note == null) {
            return null;
        }
        java.util.regex.Matcher m = GPS_PAIR.matcher(note);
        return m.find() ? m.group(1).replaceAll("\\s+", "") : null;
    }

    /** Baut die Standort-Zeile je nach Ansicht-/Bearbeiten-Modus auf. */
    void update() {
        boolean readOnly = host.isReadOnly();
        double[] ll = de.spahr.ausgaben.location.Geo.parse(gpsRowCoords);
        if (ll != null) {
            textGps.setText(activity.getString(R.string.gps_row_label, gpsDisplay(gpsRowCoords)));
            final double lat = ll[0];
            final double lon = ll[1];
            // Ansicht: nur Karte zeigen. Bearbeiten/Neu: Standort auf der Karte ändern bzw. löschen.
            if (readOnly) {
                btnNoteMap.setOnClickListener(v -> openMapAt(lat, lon));
                btnGpsClear.setVisibility(View.GONE);
            } else {
                btnNoteMap.setOnClickListener(v -> openMapForEdit(lat, lon));
                btnGpsClear.setVisibility(View.VISIBLE);
                btnGpsClear.setOnClickListener(v -> {
                    // Ohne Rückfrage – wie das Löschkreuz der Stichwörter; rückgängig durch Verlassen
                    // der Maske, ohne zu speichern.
                    gpsRowCoords = null;
                    gpsEditedByUser = true;
                    host.onGpsChanged();
                });
            }
            rowGps.setVisibility(View.VISIBLE);
        } else if (!readOnly && host.isGpsEnabled() && !host.isTransferType()) {
            // Noch kein Standort: Zeile zum Setzen eines Standorts anbieten.
            textGps.setText(R.string.gps_row_none);
            btnNoteMap.setOnClickListener(v -> openMapForEdit(null, null));
            btnGpsClear.setVisibility(View.GONE);
            rowGps.setVisibility(View.VISIBLE);
        } else {
            rowGps.setVisibility(View.GONE);
        }
    }

    /** Anzeigeform der Koordinaten, z. B. „50.1109° N, 8.6821° O". */
    private String gpsDisplay(String coords) {
        double[] ll = de.spahr.ausgaben.location.Geo.parse(coords);
        if (ll == null) {
            return coords == null ? "" : coords;
        }
        String ns = activity.getString(ll[0] >= 0 ? R.string.compass_n : R.string.compass_s);
        String ew = activity.getString(ll[1] >= 0 ? R.string.compass_e : R.string.compass_w);
        return String.format(java.util.Locale.US, "%.4f° %s, %.4f° %s",
                Math.abs(ll[0]), ns, Math.abs(ll[1]), ew);
    }

    private void openMapAt(double lat, double lon) {
        Intent i = new Intent(activity, MapPickerActivity.class);
        i.putExtra(MapPickerActivity.EXTRA_LAT, lat);
        i.putExtra(MapPickerActivity.EXTRA_LON, lon);
        i.putExtra(MapPickerActivity.EXTRA_VIEW_ONLY, true);
        activity.startActivity(i);
    }

    /**
     * Öffnet die Karten-Auswahl (wählbar, wie im Alias), zentriert auf die aktuellen Koordinaten (falls
     * vorhanden – sonst letzte bekannte Position/Standard). Das Ergebnis übernimmt {@link #gpsMapLauncher}.
     */
    private void openMapForEdit(Double lat, Double lon) {
        Intent i = new Intent(activity, MapPickerActivity.class);
        if (lat != null && lon != null) {
            i.putExtra(MapPickerActivity.EXTRA_LAT, (double) lat);
            i.putExtra(MapPickerActivity.EXTRA_LON, (double) lon);
        }
        gpsMapLauncher.launch(i);
    }

    /** Koordinaten als „lat,lon" mit sechs Nachkommastellen (wie die Karten-Auswahl liefert). */
    static String formatCoords(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon);
    }
}
