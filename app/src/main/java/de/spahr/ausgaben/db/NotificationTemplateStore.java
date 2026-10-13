package de.spahr.ausgaben.db;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import de.spahr.ausgaben.notify.KnownApps;
import de.spahr.ausgaben.settings.ProfileManager;
import de.spahr.ausgaben.statement.AnchorRule;
import de.spahr.ausgaben.statement.NotificationTemplate;
import de.spahr.ausgaben.statement.StatementRulesIo;
import de.spahr.ausgaben.statement.TextRule;

/**
 * Die Vorlagen für Buchungen aus Benachrichtigungen, als Modell gelesen und geschrieben.
 *
 * <p>Alle Methoden arbeiten sofort und gehören deshalb auf den Hintergrundfaden des
 * {@link Repository}. Jede Änderung zieht {@link KnownApps} nach — die Liste, an der der Dienst im
 * Hintergrund erkennt, welche Meldungen nicht nach einem Tag verfallen dürfen.</p>
 */
public final class NotificationTemplateStore {

    /** Das Ende eines Textfelds ({@link TextRule#until}) im JSON der Regel. */
    private static final String KEY_UNTIL = "u";

    private final Context app;
    private final AppDatabase db;
    private final NotificationTemplateDao dao;

    public NotificationTemplateStore(Context context, AppDatabase db) {
        this.app = context.getApplicationContext();
        this.db = db;
        this.dao = db.notificationTemplateDao();
    }

    /** Alle Vorlagen des aktiven Profils; was sich nicht mehr lesen lässt, fehlt. */
    public List<NotificationTemplate> all() {
        List<NotificationTemplate> out = new ArrayList<>();
        for (NotificationTemplateRow row : dao.getAll()) {
            NotificationTemplate t = fromRow(row);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Nimmt die Vorlage auf. Vorlagen desselben Kontos für eine <b>andere</b> App gehen dabei: ein
     * Konto gehört zu genau einer Bank. Eine gleichlautende wird nicht doppelt abgelegt.
     */
    public void save(final NotificationTemplate template) {
        db.runInTransaction(() -> {
            dao.deleteOtherApps(template.account, template.packageName);
            for (NotificationTemplate known : all()) {
                if (known.sameAs(template)) {
                    return;
                }
            }
            NotificationTemplateRow row = toRow(template);
            if (row != null) {
                dao.insert(row);
            }
        });
        syncKnownApps();
    }

    public void deleteForAccount(String account) {
        dao.deleteForAccount(account);
        syncKnownApps();
    }

    /** Schreibt eine von Hand geänderte Regel zurück; sie behält ihre Nummer. */
    public void update(NotificationTemplate template) {
        NotificationTemplateRow row = toRow(template);
        if (row == null || template.id <= 0) {
            return;
        }
        row.id = template.id;
        dao.update(row);
        syncKnownApps();
    }

    /** Löscht eine einzelne Regel. */
    public void delete(long id) {
        dao.deleteById(id);
        syncKnownApps();
    }

    /** Vergisst alles, was für diese App gelernt wurde — für jedes Konto des aktiven Profils. */
    public void deleteForPackage(String packageName) {
        dao.deleteForPackage(packageName);
        syncKnownApps();
    }

    /** Schreibt die Paketnamen dieses Profils nach {@link KnownApps}. */
    public void syncKnownApps() {
        new KnownApps(app).put(new ProfileManager(app).getActiveProfileId(),
                new HashSet<>(dao.packages()));
    }

    static NotificationTemplateRow toRow(NotificationTemplate t) {
        try {
            NotificationTemplateRow row = new NotificationTemplateRow();
            row.packageName = t.packageName;
            row.discriminator = t.discriminator;
            row.account = t.account;
            row.isIncome = t.isIncome;
            row.amountRule = StatementRulesIo.ruleToJson(t.amountRule).toString();
            if (t.payeeRule != null) {
                JSONObject o = StatementRulesIo.ruleToJson(t.payeeRule.rule);
                o.put(KEY_UNTIL, t.payeeRule.until);
                row.payeeRule = o.toString();
            }
            return row;
        } catch (JSONException e) {
            return null;
        }
    }

    static NotificationTemplate fromRow(NotificationTemplateRow row) {
        try {
            AnchorRule amount = StatementRulesIo.ruleFromJson(new JSONObject(row.amountRule));
            if (amount == null) {
                return null;
            }
            TextRule payee = null;
            if (row.payeeRule != null && !row.payeeRule.isEmpty()) {
                JSONObject o = new JSONObject(row.payeeRule);
                AnchorRule rule = StatementRulesIo.ruleFromJson(o);
                if (rule == null) {
                    return null;   // eine halbe Vorlage läse Meldungen, die sie nicht lesen soll
                }
                payee = new TextRule(rule, o.optString(KEY_UNTIL, ""));
            }
            return new NotificationTemplate(row.id, row.packageName, row.discriminator, row.account,
                    row.isIncome, amount, payee);
        } catch (JSONException e) {
            return null;
        }
    }
}
