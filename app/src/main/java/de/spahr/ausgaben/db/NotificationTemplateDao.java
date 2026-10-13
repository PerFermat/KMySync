package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/** Zugriff auf die Vorlagen für Buchungen aus Benachrichtigungen ({@link NotificationTemplateRow}). */
@Dao
public interface NotificationTemplateDao {

    @Query("SELECT * FROM notification_template ORDER BY id")
    List<NotificationTemplateRow> getAll();

    @Insert
    long insert(NotificationTemplateRow row);

    @androidx.room.Update
    void update(NotificationTemplateRow row);

    @Query("DELETE FROM notification_template WHERE id = :id")
    void deleteById(long id);

    /** Ein Konto gehört zu genau einer App: lernt es von einer anderen, gehen die alten Vorlagen. */
    @Query("DELETE FROM notification_template WHERE account = :account AND package_name <> :packageName")
    void deleteOtherApps(String account, String packageName);

    @Query("DELETE FROM notification_template WHERE account = :account")
    void deleteForAccount(String account);

    @Query("DELETE FROM notification_template WHERE package_name = :packageName")
    void deleteForPackage(String packageName);

    @Query("SELECT DISTINCT package_name FROM notification_template")
    List<String> packages();
}
