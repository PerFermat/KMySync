package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.core.content.pm.PackageInfoCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.R;

/**
 * „Was ist neu": die neuen Funktionen je Version und der Dialog nach einem Update.
 *
 * <p>Die Liste steht in {@code res/raw*}/{@code news.json}, je Sprache eine, und enthält bewusst
 * <b>nur neue Funktionen</b> – keine Fehlerbehebungen. Eine Version, die nur Korrekturen bringt,
 * steht gar nicht darin, und nach einem Update auf sie erscheint nichts.</p>
 *
 * <p>Der Dialog kommt genau einmal je Version und nur nach einem Update, nie nach einer
 * Neuinstallation: wer die App gerade eingerichtet hat, braucht keine Liste dessen, was „neu" ist.
 * Er zeigt nur die laufende Version; alle früheren stehen auf der Seite {@link NewsActivity}.</p>
 */
final class WhatsNew {

    static final String DLG = "dlg_whatsNew";

    /** Gerätweit: welche Version der Nutzer schon „gesehen" hat. */
    private static final String KEY_SEEN = "news_seen_code";
    private static final String PREFS = "ausgaben_settings";

    private WhatsNew() {
    }

    /** Ein Punkt: eine neue Funktion. */
    static final class Item {
        final String title;
        final String text;

        Item(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    /** Die neuen Funktionen einer Version. */
    static final class Entry {
        final String version;
        final int code;
        final List<Item> items;

        Entry(String version, int code, List<Item> items) {
            this.version = version;
            this.code = code;
            this.items = items;
        }
    }

    /** Die Liste in der Sprache von {@code context}, neueste Version zuerst; leer bei einem Fehler. */
    static List<Entry> load(Context context) {
        try (InputStream in = context.getResources().openRawResource(R.raw.news)) {
            return parse(read(in));
        } catch (IOException | JSONException | RuntimeException e) {
            android.util.Log.w("WhatsNew", "Neuigkeiten nicht lesbar", e);
            return Collections.emptyList();
        }
    }

    static List<Entry> parse(String json) throws JSONException {
        JSONArray versions = new JSONArray(json);
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            JSONArray its = v.getJSONArray("items");
            List<Item> items = new ArrayList<>();
            for (int j = 0; j < its.length(); j++) {
                JSONObject it = its.getJSONObject(j);
                items.add(new Item(it.getString("title"), it.getString("text")));
            }
            out.add(new Entry(v.getString("version"), v.getInt("code"), items));
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static Entry entryFor(List<Entry> all, int code) {
        for (Entry e : all) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    /**
     * Ob der Dialog erscheinen soll. Rein, damit die Regel ohne Gerät prüfbar ist.
     *
     * @param seen         die zuletzt gesehene Version; negativ = noch keine gemerkt
     * @param freshInstall die App wurde nie aktualisiert, nur installiert
     * @param hasEntry     die laufende Version bringt neue Funktionen
     */
    static boolean shouldShow(int current, int seen, boolean freshInstall, boolean hasEntry) {
        return seen < current && !freshInstall && hasEntry;
    }

    /** Beim Start der Hauptmaske: einmal je Version, nur nach einem Update und nur mit Neuem. */
    static void maybeShow(FragmentActivity activity) {
        int current;
        boolean fresh;
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            current = (int) PackageInfoCompat.getLongVersionCode(info);
            fresh = info.firstInstallTime == info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        SharedPreferences prefs = activity.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int seen = prefs.getInt(KEY_SEEN, -1);
        if (seen >= current) {
            return;
        }
        // Gemerkt wird gleich: auch eine Version ohne Neues oder eine Neuinstallation gilt als
        // gesehen, sonst käme der Dialog beim nächsten Neuigkeiten-Update für eine alte Version.
        prefs.edit().putInt(KEY_SEEN, current).apply();
        Entry entry = entryFor(load(activity), current);
        if (shouldShow(current, seen, fresh, entry != null)) {
            HostedDialog.show(activity, DLG, new Bundle());
        }
    }

    /** Der Dialog „Neu in Version …" – nur die laufende Version, dazu der Weg zu allen. */
    static Dialog build(Activity activity) {
        int current;
        try {
            current = (int) PackageInfoCompat.getLongVersionCode(activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        Entry entry = entryFor(load(activity), current);
        if (entry == null) {
            return null;
        }
        return new AppDialog(activity)
                .setTitle(activity.getString(R.string.news_dialog_title, entry.version))
                .setMessage(itemsText(entry.items))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.news_all, (d, w) ->
                        activity.startActivity(new Intent(activity, NewsActivity.class)))
                .create();
    }

    /** Die Punkte einer Version: Titel fett, darunter der Text. */
    static CharSequence itemsText(List<Item> items) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            int start = sb.length();
            sb.append(it.title);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n').append(it.text);
        }
        return sb;
    }
}
