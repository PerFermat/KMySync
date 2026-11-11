package de.spahr.ausgaben.ui;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.export.KmyImporter;

/**
 * Konten in der Auswahl beim Import: In KMyMoney geschlossene stehen am Ende und tragen den Zusatz
 * „(geschlossen)". Gewählt werden dürfen sie trotzdem – sie kommen dann als geschlossen in die App.
 */
final class ImportAuswahl {

    private ImportAuswahl() {
    }

    /** Dieselben Namen, die offenen zuerst, jeweils in der Reihenfolge der Datei. */
    static List<String> offeneZuerst(KmyImporter importer, List<String> namen) {
        List<String> offen = new ArrayList<>();
        List<String> zu = new ArrayList<>();
        for (String n : namen) {
            (importer.isClosed(n) ? zu : offen).add(n);
        }
        offen.addAll(zu);
        return offen;
    }

    /** Die Zeile für einen Namen; {@code zeile} ist, was sonst dastünde (etwa „Depot: …"). */
    static String zeile(Context c, KmyImporter importer, String name, String zeile) {
        return importer.isClosed(name) ? c.getString(R.string.account_closed_suffix, zeile) : zeile;
    }
}
