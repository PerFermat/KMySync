# Prüfauftrag: Sicherheit und Datenschutz

Stand: 2026-09-22 · gültig ab Version 2.1

Zum Vorschalten vor eine Sicherheitsprüfung des Repositorys. Der Abschnitt
[Bereits geprüft](#bereits-geprüft--nicht-erneut-melden) ist der eigentliche Zweck: Ohne ihn kommen
immer wieder dieselben Punkte zurück, weil sie in jeder allgemeinen Android-Checkliste stehen.

**Pflege.** Dieser Abschnitt hat ein Verfallsdatum. Wird am Passwortspeicher, an der Netzschicht,
an der Wear-Kopplung oder an den Manifest-Zusicherungen etwas geändert, gehört der betroffene
Eintrag gestrichen — sonst deckt er einen echten Fehler zu. Jeder Eintrag nennt deshalb die Stelle,
an der er hängt.

---

## Der Prompt

```
Prüfe dieses Repository auf Sicherheits- und Datenschutzrisiken.

# Was das Programm ist

KMySync (de.spahr.ausgaben), Android, Java, Room. Ein Einzelnutzer-Begleiter zu
KMyMoney auf dem Desktop. Es gibt keinen Anbieter, keinen zentralen Dienst, kein
Konto und keine Telemetrie. Der Nutzer trägt selbst einen Server ein (Nextcloud,
generisches WebDAV oder SMB/Samba, meist ein NAS im eigenen Haus) und tauscht
darüber eine KMyMoney-Datei aus. Die foss-Variante läuft ohne Google Play
Services.

# Bedrohungsmodell

Im Umfang:
- Andere Apps auf demselben Gerät.
- Jemand mit kurzzeitigem Zugriff auf das entsperrte Gerät.
- Ein Angreifer im selben Netz (Heim-WLAN, fremdes WLAN).
- Ein versehentlich zu viel preisgebender Ausgabepfad: Log, Diagnosebericht,
  Zwischenablage, geteilte Datei, Sicherung.
- Datenverlust und stille Datenbeschädigung. Zählt hier als Sicherheitsthema.

Nicht im Umfang, bitte nicht dagegen prüfen:
- Angreifer mit Root oder physischem Vollzugriff über längere Zeit.
- Kompromittiertes TLS bzw. gebrochene Zertifikatsketten.
- Der Server des Nutzers selbst. Er gehört ihm; die .kmy liegt dort
  naturgemäß im Klartext (gezipptes XML), das ist KMyMoneys Format, nicht
  unsere Entscheidung.

# Bereits geprüft und abgeschlossen -- nicht erneut melden

Diese sieben Punkte sind am Code verifiziert. Melde sie nur dann wieder, wenn du
belegen kannst, dass sich der Code seither geändert hat; dann nenne die Stelle.

1. HTTP Basic Auth bei Nextcloud/WebDAV. Beabsichtigt. Klartextverkehr ist
   plattformseitig gesperrt (kein usesCleartextTraffic, keine
   network_security_config, targetSdk 34), kein einziges "http://" im Quelltext.
   App-Passwörter sind der von Nextcloud vorgesehene Weg für Nicht-Browser-
   Clients.
2. SMB-Passwörter angeblich im Klartext. Falsch. Es gibt einen einzigen
   Schlüssel KEY_PASSWORD für alle drei Servertypen, in
   EncryptedSharedPreferences (SettingsStore#getPassword, gereicht über
   RemoteStorage an SmbStorage).
3. Unverschlüsselter Rückfall bei defektem Keystore. Behoben in 8c0e894
   (2026-09-17): zwei Versuche mit Verwerfen dazwischen, fallbackInUse-Flag,
   sichtbare Warnung am Passwortfeld (settings_secret_fallback). Ein harter
   Abbruch wäre falsch -- der größte Teil der App läuft ohne Server.
4. App-Sperre verschlüsselt die Datenbank nicht. Beabsichtigt und im Javadoc von
   BiometricAuth so benannt. SQLCipher hülfe nicht: Der Schlüssel läge im
   Keystore und wäre beim entsperrten Gerät genauso verfügbar.
5. Kein Certificate Pinning. Nicht umsetzbar bei frei wählbaren Servern und
   schädlich bei 90-Tage-Zertifikaten. Vom Nutzer installierte CAs sind seit API
   24 ohnehin nicht vertrauenswürdig, minSdk ist 26.
6. SMB-Authentifizierung und -Verschlüsselung. Geklärt: smbj 0.13.0, NTLMv2
   (NTLMv1 ist nicht implementiert), SMB1 gibt es nicht, withEncryptData(true),
   Signierung wird ausgehandelt. Der Verbindungstest zeigt Dialekt, Signierung
   und Verschlüsselung zur Laufzeit an.
7. Unsignierte Wear-Nachrichten. Integrität kommt vom Wear Data Layer: Zustellung
   nur zwischen Apps mit gleicher applicationId und gleichem Signaturschlüssel.
   Ein HMAC müsste sein Geheimnis in beiden öffentlich herunterladbaren APKs
   tragen.

# Wie du vorgehen sollst

Folge jedem Befund bis zur Quelle, bevor du ihn meldest. Ein Suchtreffer ist
kein Befund. Konkret:
- Lies den Kommentar neben der Stelle. Dieses Projekt begründet bewusste
  Entscheidungen im Javadoc. Steht dort eine Begründung, setze dich mit ihr
  auseinander oder lass den Punkt fallen.
- Verfolge, woher ein Wert kommt und wohin er geht, über Dateigrenzen hinweg.
- Prüfe, ob die Plattform das Problem schon löst (Manifest, minSdk/targetSdk,
  Scoped Storage, App-privater Speicher), bevor du App-Code dagegen forderst.
- Nenne bei jedem Befund Datei und Zeile und den Pfad, über den ein Angreifer
  aus dem Bedrohungsmodell ihn tatsächlich erreicht. Kannst du den Pfad nicht
  angeben, ist es kein Befund.
- Empfiehl keine Maßnahme, ohne ihre Kosten zu nennen (Bedienbarkeit, F-Droid-
  Reproduzierbarkeit, Wartung, Ausfallrisiko).

# Worauf es sich zu schauen lohnt

- Ausgabepfade: Logging, der kopierbare Verbindungs-Diagnosebericht, Teilen-
  Intents, exportierte Dateien, Sicherungsarchive. Was steht darin, was nicht
  darin stehen müsste?
- Exportierte Komponenten, Intent-Filter, Broadcast-Empfänger, FileProvider-
  Pfade (res/xml/file_paths.xml), Deeplinks aus den Widgets.
- Profiltrennung: Kann ein Profil an Daten, Belege oder Zugangsdaten eines
  anderen gelangen?
- Pfadbehandlung auf dem Server: Traversal über ".." in Datei- oder
  Ordnernamen, Kollisionen, Überschreiben fremder Dateien.
- Parser gegen bösartige Eingaben: .kmy-XML, PDF-Abrechnungen, CSV-Import.
- Die Aufräumläufe (ReceiptGc, KmyBackups): Können sie etwas löschen, das
  ihnen nicht gehört?
- Unterschiede zwischen den Flavors full und foss.

# Ausgabe

Nach Schwere sortiert. Je Befund: was, wo (Datei:Zeile), wie erreichbar, was es
kostet zu beheben. Wenn du nichts findest, schreibe das. Eine leere Liste ist ein
gültiges Ergebnis; erfundene Befunde kosten mehr Zeit als sie sparen.
```

---

## Woran die abgeschlossenen Punkte hängen

Ändert sich eine dieser Stellen, gehört der zugehörige Eintrag oben überprüft:

| # | Hängt an |
|---|---|
| 1 | `AndroidManifest.xml` (kein `usesCleartextTraffic`, keine `network_security_config`), `targetSdk`, `NextcloudUploader` |
| 2 | `SettingsStore#getPassword` / `KEY_PASSWORD`, `RemoteStorage` → `SmbStorage` |
| 3 | `SettingsStore#createSecretPrefs`, `fallbackInUse`, `settings_secret_fallback` |
| 4 | `security/BiometricAuth`, `allowBackup="false"`, Speicherort der Room-Datenbank |
| 5 | `minSdk`, `NextcloudUploader#client`, `WebDavDiagnostics#CLIENT` |
| 6 | `net/smb/SmbSessions#configure`, smbj-Version in `gradle/libs.versions.toml` |
| 7 | `applicationId` in `app/build.gradle` und `wear/build.gradle`, `wear/ExpenseWearListenerService` |
