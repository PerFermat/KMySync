package de.spahr.ausgaben.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import de.spahr.ausgaben.R;

/**
 * Färbt die Symbole im aufgeklappten Menü um.
 *
 * <p>Sie tragen {@code android:tint="?attr/colorControlNormal"}, und aufgelöst wird das beim Laden –
 * mit dem Kontext der Toolbar. Dort ist die Farbe weiß, richtig für das grüne Band, aber auf dem
 * hellen Grund des Menüs praktisch unsichtbar. Ein {@code popupTheme} an der Toolbar hilft dagegen
 * nicht (probiert): Es färbt die Ansichten des Aufklappers, nicht die längst geladene Zeichnung.</p>
 *
 * <p>Gemeinsam für Hauptbildschirm und Depot, damit beide Menüs gleich aussehen.</p>
 */
final class MenuIcons {

    private MenuIcons() {
    }

    /**
     * Alle Symbole außer den dreien im Band. Die Ausnahmeliste stimmt nur, weil diese drei in beiden
     * Menüs auf {@code showAsAction="always"} stehen – ein Symbol, das je nach Bildschirmbreite mal
     * im Band und mal im Menü landet, wäre in einem der beiden Fälle falsch gefärbt.
     */
    static void tintOverflow(Context context, Menu menu) {
        int color = context.getColor(R.color.menu_icon);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            int id = item.getItemId();
            if (id == R.id.action_export || id == R.id.action_import_all
                    || id == R.id.action_filter) {
                continue;
            }
            tint(item, color);
            if (item.hasSubMenu()) {
                SubMenu sub = item.getSubMenu();
                for (int j = 0; j < sub.size(); j++) {
                    tint(sub.getItem(j), color);
                }
            }
        }
    }

    private static void tint(MenuItem item, int color) {
        Drawable icon = item.getIcon();
        if (icon == null) {
            return;
        }
        // mutate(), damit nicht die gemeinsam genutzte Zeichnung aus dem Ressourcen-Zwischenspeicher
        // umgefärbt wird – dieselben Symbole stehen anderswo weiß auf dem grünen Band.
        icon = icon.mutate();
        icon.setTint(color);
        item.setIcon(icon);
    }
}
