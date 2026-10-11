package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Eine gelernte Vorlage für Buchungen aus Benachrichtigungen, wie sie in der Datenbank steht — die
 * Regeln als JSON in derselben Schreibweise wie die Vorlagen der Abrechnungen
 * ({@code StatementRulesIo}). Das Modell dazu ist {@code NotificationTemplate}.
 *
 * <p>Hier stehen nur Regeln: Ankerwörter wie „über" und „bei", ein Paketname, ein Kontoname. Der Text
 * einer Benachrichtigung steht nicht darin; der liegt im Puffer außerhalb dieser Datenbank und damit
 * außerhalb jeder Sicherung.</p>
 */
@Entity(tableName = "notification_template",
        indices = {@Index({"package_name", "discriminator"})})
public class NotificationTemplateRow {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "package_name")
    public String packageName = "";

    /** Etwa die Kartenendung; leer, wenn die Meldung keines trägt. */
    @NonNull
    @ColumnInfo(name = "discriminator")
    public String discriminator = "";

    /** Der Kontoname — Konten hängen in dieser App überall am Namen. */
    @NonNull
    @ColumnInfo(name = "account")
    public String account = "";

    @ColumnInfo(name = "is_income")
    public boolean isIncome;

    @NonNull
    @ColumnInfo(name = "amount_rule")
    public String amountRule = "";

    /** {@code null}, wenn die Vorlage keinen Empfänger liest. */
    @Nullable
    @ColumnInfo(name = "payee_rule")
    public String payeeRule;
}
