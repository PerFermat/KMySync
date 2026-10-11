package de.spahr.ausgaben.ui;

import android.net.Uri;

import androidx.activity.ComponentActivity;

import java.util.List;

/**
 * Der Dokumentenscanner – in dieser Variante (F-Droid) gibt es ihn nicht.
 *
 * <p>Die Variante „full" ruft hier den Scanner der Google-Play-Dienste auf. Der ist keine freie
 * Software und hat in diesem Build nichts zu suchen; diese Klasse hält nur dieselbe Schnittstelle
 * bereit, damit der Belegweg in beiden Varianten derselbe Code ist. Belege kommen hier weiter über
 * Kamera, Galerie oder PDF.</p>
 */
final class DocumentScan {

    /** Empfängt die Seiten eines Scans, in der gescannten Reihenfolge. */
    interface Seiten {
        void da(List<Uri> seiten);
    }

    DocumentScan(ComponentActivity activity, Seiten empfaenger) {
    }

    /** Gibt es den Scanner in diesem Build? */
    static boolean available() {
        return false;
    }

    /** Nichts zu starten: Es geht sofort den Weg, der sonst der Rückfall ist. */
    void start(Runnable beiFehler) {
        beiFehler.run();
    }
}
