# Arbeitsstand: erste Einreichung bei F-Droid

> **Übergabenotiz, kein dauerhafter Bestandteil des Projekts.** Sie existiert, weil Michael an mehreren
> Rechnern arbeitet und der andere Claude die lokalen Notizen dieses Rechners nicht sieht. Sobald die
> Einreichung durch ist, kann sie gelöscht werden.
>
> Stand: 2026-09-12. **2.0 ist veröffentlicht** – Tag `v2.0` (Commit `c08dcef`) und GitHub-Release mit
> den drei signierten APKs sind draußen. Offen ist nur noch der Merge Request; siehe unten.

## Stand 2026-11-11: Arbeit vom 10. und 11.11.2026 (Rechner mit 7 GB RAM)

Alles ist committet und gepusht (`4ee6853` … `8e7b01b`). Version im Repo: 2.5 / versionCode 20,
noch nicht veröffentlicht. Die Änderungstexte `changelogs/20.txt` und „Was ist neu“ (`res/raw*/news.json`)
sind für alles unten nachgezogen; in `20.txt` fehlen noch die Punkte vom anderen Rechner (lokaler Ordner,
Fremdwährung u. a.).

### Was neu ist

1. **KMyMoney-Datenbanken aus 5.2** (`4ee6853`): geprüft sind jetzt Schema 12 (KMyMoney 5.1) bis 16
   (5.2.2), vorher verweigerte die App jede andere Version als 12. Spalten nach Vorhandensein
   (`kmmPayees.idPattern`/`urlTemplate` ab 13); die Schema-Version wird nie angehoben.
   **Behobener Fehler:** 5.2 legt Budgets in `kmmBudgetConfig` in *einer* Zeile ab – das Abbild ließ
   sie bisher ganz weg (`KmySqlite.budgetZeilen`).
2. **GPG-verschlüsselte .kmy** (Issue #13, `c2e2696`): `export/KmyGpg.java` über BouncyCastle-Modul
   `bcpg` (gleiche Fassung 1.79, leichtes Bc-API, keine Keep-Regeln). KMyMoney/GnuPG 2.4 schreibt
   ASCII-armored, AEAD/OCB, darin ZLIB und reines XML. Zurück in derselben Hülle an dieselben
   Empfänger; fehlt ein öffentlicher Schlüssel, wird nichts geschrieben. Schlüssel + Passphrase je Profil
   im `SecretStore` (Profileinstellungen „GPG-Schlüssel…“).
3. **SQLCipher-verschlüsselte Datenbank** (Issue #13, `b934b8f`): `export/KmySqlCipher.java`, ein
   Seitenwandler in reinem Java (keine nativen Bibliotheken!). KMyMoney 5.2.2 schreibt SQLCipher 3
   (PBKDF2-SHA1 64 000, Seite 1024, Reserve 48). Passwort je Profil („Datenbank-Passwort…“).
   Dabei behoben: Die Erkennung binärer GPG-Dateien sah nur aufs erste Byte und hätte jede ~25.
   SQLCipher-Datenbank für GPG gehalten.
4. **Im Willkommen-Assistenten** (`c825439`): dieselben Knöpfe (`include_kmy_encryption.xml`,
   `ui/EncryptionFields.java`). Scheitert ein Import an Schlüssel/Passwort, kommt ein Dialog mit der
   ganzen Meldung, führt zur Eingabe und startet den Import danach selbst neu.
5. **Geschlossene Konten mit KMyMoney abgleichen** (`3de11a4`, `8e7b01b`): KMyMoney führt „geschlossen“
   als `<PAIR key="mm-closed" value="yes"/>` am Konto. Import übernimmt es (Konto grau, keine neuen
   Buchungen, in der Importauswahl am Ende mit „(geschlossen)“). Schließen/Wiederöffnen in der App wird
   vorgemerkt (`SettingsStore.getPendingAccountStatus`) und beim nächsten Export geschrieben
   (`KmyKontoStatus`, in der DB `kmmKeyValuePairs` + `kmmFileInfo.kvps`). Die Selbstprüfung erlaubt am
   Kontenbereich nur dieses angesagte Paar. Ein von KMyMoney geschlossenes Konto öffnet sich nicht mehr
   von selbst wegen eines Restsaldos.

Handbuch (de/en, PDFs neu gebaut) ist für alle Punkte nachgezogen, die READMEs nur für GPG/SQLCipher. Die Handbuch-**Webseite** ist
seit 2.4 nicht neu erzeugt (`python3 docs/build_manual_web.py`, dann `deploy-server.sh` im Repo
`kmysync-handbuch`).

### Geprüft

- Einzelne Testklassen und die Pakete `export`/`settings` (423 Tests) grün. **Die volle Testsuite lief
  seit `c2e2696` nicht mehr** – auf diesem Rechner friert sie ihn ein (siehe unten). Bitte auf dem
  anderen Rechner einmal ganz laufen lassen.
- `StatementRulesTestFileTest` scheiterte hier unter Speicherdruck: ein Timing-Test (wartet nicht auf
  das Einlesen der PDF im Hintergrund), hat mit den Änderungen nichts zu tun.
- Echte Prüflinge von Michael (nur Testdaten, Passphrase/Passwort „michael“):
  `~/Nextcloud/KMyMoney/testdate/` (`test.verschluesselt.kmy`, `.app.*`, `.emulator.*`) und
  `~/Downloads/test.verschluesselt.db`, Schlüssel `~/backup.asc`. Kleine Prüflinge im Repo unter
  `app/src/test/resources/kmy/gpg/`. Große Dateien über `-Dkmy.gpg=…`, `-Dkmy.sqlcipher=…`
  (Ausgabe mit `…out`).
- KMyMoney 5.2.2 hat alle von der App verschlüsselten Dateien geöffnet und die Testbuchungen gezeigt.
- Emulator (nur auf diesem Rechner angelegt, AVD `KMySync_Test`, 2 GB): beide Verschlüsselungen,
  Assistent und geschlossene Konten durchgespielt.

### Offen

- Nach R8 (Release-Build) am Handy prüfen, ob GPG/SQLCipher laufen – bisher nur Debug-Build.
- Ein **Depot**, das beim ersten Import neu angelegt wird, bekommt den Geschlossen-Status erst beim
  nächsten Import.
- Sind **alle** Konten eines Profils geschlossen, erscheint der Willkommen-Assistent (er prüft nur auf
  offene Konten). Michael hat noch nicht entschieden, ob das so bleibt.
- Vier ältere Auffälligkeiten aus dem Emulator, nicht angefasst: Import-Fehler in der Hauptansicht als
  abgeschnittene Einblendung; nach dem Assistenten bleibt er im Verlauf (Zurück führt dorthin);
  Profileinstellungen zeigen beim lokalen Ordner nicht den gewählten Ordner; „Zurück“ verwirft dort
  Änderungen, Format und Passwort aber nicht.
- Ob KMyMoney 5.1.3 eine mit 5.2 gespeicherte Datenbank noch öffnet, ist ungeprüft.
- GitHub: Issue #13 (Verschlüsselung) noch ohne Antwort an visionok; Issue #12 (Paras, Herunterziehen
  aktualisiert nur das angezeigte Depot) beantwortet, offen. #11 ist erledigt und geschlossen.

### Achtung auf dem 7-GB-Rechner

Die volle Testsuite (Gradle 2 GB + Test-Worker 3 GB) hat den Rechner dreimal eingefroren (nur 512 MB
Swap). Dort nur einzelne Klassen/Pakete testen, danach `./gradlew --stop`; Emulator und Gradle nicht
gleichzeitig.

## Worum es geht

KMySync (früher „Ausgaben") soll erstmals zu F-Droid. Der GMS-freie `foss`-Flavor ist geprüft und
fertig, 2.0 ist getaggt und veröffentlicht, das Rezept liegt im GitLab-Fork bereit. Es fehlt nur noch
der Merge Request.

## Nächste Version: 2.2

2.1 ist am 24.09.2026 veröffentlicht, 2.1.1 am 25.09.2026 als Fehlerbehebung hinterher – Tag
`v2.1.1` (Commit `c2a7ae8`, versionCode 16): Der Export tauscht die .kmy jetzt, statt sie per
WebDAV-MOVE zu überschreiben (Nextcloud löschte dabei das Ziel zuerst; bei einem gescheiterten
Umbenennen war die Datei weg). Im Repo steht jetzt `versionCode 17` / `versionName "2.2"`;
`changelogs/17.txt` muss vor dem nächsten Tag geschrieben werden.

## Erledigt

### Geprüft (2026-09-09)

Der `foss`-Build wurde aus einem **sauberen Klon** gebaut, so wie es der F-Droid-Server tut – ohne
`keystore.properties`, mit `--recurse-submodules`, aus `app/` heraus:

| Prüfung | Ergebnis |
|---|---|
| Build aus sauberem Klon | BUILD SUCCESSFUL |
| Ergebnis | `app-foss-release-unsigned.apk`, kein Signaturblock |
| `com/google/android/gms` im DEX | 0 |
| `com/google/firebase` im DEX | 0 |
| native `.so`-Bibliotheken | 0 |
| vorgebaute jar/aar/dex im Repo | keine (außer gradle-wrapper.jar) |
| Repositories | nur `google()` + `mavenCentral()`, kein JitPack |
| MPAndroidChart | Submodul auf Tag `v3.1.0`, aus Quelle gebaut |

Nachprüfbar mit:

```bash
ANDROID_HOME=/home/michael/Android/Sdk ./gradlew :app:assembleFossRelease
unzip -p app/build/outputs/apk/foss/release/app-foss-release*.apk 'classes*.dex' \
  | strings | grep -c com/google/android/gms    # erwartet: 0
```

### Repo (Commits `1f0960c`, `8c748ce`)

- `fdroid/de.spahr.ausgaben.yml` stand auf `versionName 1.3` / `versionCode 4` / `commit: v1.3` –
  **einem Tag, den es nie gab**. So eingereicht wäre der Build sofort gescheitert. Jetzt 2.0 / 14 / v2.0.
- `scanignore` für `app/build.gradle`, `app/src/full`, `wear` war ergänzt worden – **am 12.09.2026 in
  der Prüfung beanstandet und wieder entfernt** („scanignore is not allowed"). Es war ohnehin
  weitgehend überflüssig: Der Scanner wertet Gradle-Flavors aus, sucht bei `gradle: - foss` also nach
  `fossImplementation` und übersieht die `fullImplementation`-Zeile mit GMS von selbst; in
  `app/src/full` liegt nur Quelltext. Offen bleibt allein `wear/build.gradle:65` mit
  `implementation 'com.google.android.gms:play-services-wearable'` ohne Flavor-Präfix – ob der Scanner
  daran Anstoß nimmt, zeigt erst ein CI-Lauf.
- Die an uns gerichteten Kopfzeilen aus dem Rezept entfernt – die Datei ist jetzt **wörtlich
  einfügbar**. Alles, was uns betrifft, steht in `fdroid/README.md`.
- Store-Texte (`fastlane/metadata/android/{de-DE,en-US,es-ES}/full_description.txt`): mehrere Profile
  und Wertpapier-Buchungen ergänzt, PDF-Erkennung samt ihrer Grenzen, GitHub-Link eingefügt.
- Der Umbenennungshinweis („Ausgaben" → KMySync) wurde **entfernt**: Auf F-Droid ist dies die erste
  Version überhaupt, und der Absatz versprach „nichts zu deinstallieren" – was für einen Wechsel von
  GitHub nach F-Droid gerade *nicht* stimmt, weil F-Droid mit eigenem Schlüssel signiert.
- **Kein Änderungstext** (`changelogs/14.txt`) – aus demselben Grund: Es gibt keine Vorversion, von der
  sich diese unterscheiden könnte. Ab der zweiten F-Droid-Version wird je Sprache eine
  `changelogs/<versionCode>.txt` gebraucht.

### GitLab

- Konto besteht (nur E-Mail-Bestätigung, keine Ausweisprüfung). Anmeldung per SSO, deshalb kein
  Passwort – für Git über HTTPS bräuchte es ein Personal Access Token. Über die Weboberfläche irrelevant.
- `fdroid/fdroiddata` geforkt nach **`gitlab.com/PerFermat/fdroiddata`** (öffentlich).
- Das Rezept liegt dort als `metadata/de.spahr.ausgaben.yml` im Branch
  **`add-kmysync-de.spahr.ausgaben`**. **Noch kein Merge Request** – er kann eröffnet werden.

## Erledigt am 2026-09-12

1. ~~Remote abgleichen.~~
2. ~~Taggen und pushen.~~ Tag `v2.0` zeigt auf `c08dcef`.
3. ~~GitHub-Release.~~ <https://github.com/PerFermat/KMySync/releases/tag/v2.0> mit den drei signierten
   APKs, Notes samt SHA-256-Summen und Zertifikat-Fingerabdruck. Vor dem Tag geprüft: versionCode 14 /
   versionName 2.0 in beiden APKs, Signaturzertifikat gleich wie bei v1.12 (`571fd757…afc3c`), im
   `foss`-APK **0** Treffer auf `com/google/android/gms` (im `full`-APK 1557).

   **Zu beachten – inzwischen bestätigt:** `changelogs/14.txt` wurde versehentlich angelegt und liegt
   deshalb **im Commit `c08dcef`**, obwohl die Erstveröffentlichung bewusst ohne Änderungstext geplant
   war (siehe den Punkt weiter oben). Aus `main` ist die Datei entfernt – und genau das beweist, woher
   F-Droid seine Metadaten nimmt: Der Linter der Pipeline vom 13.09.2026 beanstandet `whatsNew` in
   allen drei Sprachen, obwohl es die Datei in `main` nicht gibt. **Gelesen wird der gebaute Commit,
   nicht der Branch.**

   Die Datei ist mit 1228 / 1136 / 1327 Zeichen weit über der Empfehlung von 500. Der Befund ist
   „Minor", `fdroid lint` bleibt grün, der Merge wird nicht blockiert.

   **Bewusst nicht behoben.** Das Rezept pinnt den vollen Commit-Hash, nicht den Tag; die Datei
   loszuwerden bräuchte also einen neuen Commit und damit einen neuen versionCode, ein neues
   GitHub-Release, neue Prüfsummen und einen neuen Prüflauf. Für eine Formatwarnung steht das in
   keinem Verhältnis. Die erste F-Droid-Seite zeigt daher einen Änderungstext, der sich auf nie dort
   veröffentlichte GitHub-Versionen bezieht, womöglich abgeschnitten.

   **Lehre für das nächste Release:** je Sprache eine knappe `changelogs/<versionCode>.txt` unter
   500 Zeichen anlegen – *vor* dem Taggen, denn danach ist sie nicht mehr korrigierbar.

## Offen

4. **Merge Request** im Fork eröffnen, von `add-kmysync-de.spahr.ausgaben` gegen `fdroid/fdroiddata`
   `master`.
   **Titelformat geklärt** (aus der Vorlage „App inclusion" von fdroiddata): `New app: KMySync`.
   Ebenfalls dort verlangt: Der **Fork muss öffentlich** sein und der Quell-Branch **darf nicht
   geschützt** sein, sonst kann fdroiddata nicht fast-forward mergen. Beides ist am 12.09.2026 über
   die GitLab-API geprüft und erfüllt: Fork `public`, Branch `add-kmysync-de.spahr.ausgaben`
   ungeschützt. Dass `master` im Fork geschützt ist, stört nicht — es zählt der Quell-Branch. Auch
   das Rezept im Fork stimmt mit `fdroid/de.spahr.ausgaben.yml` überein (nur der Zeilenumbruch am
   Dateiende fehlt dort, ohne Folgen). Die ausgefüllte Checkliste und der Punkt zu Reproducible
   Builds stehen in `fdroid/README.md`.
5. ~~Danach `versionName`/`versionCode` im Repo auf die nächste Entwicklungsversion heben.~~ Steht auf
   2.2 / 17.

Ein Entwurf für die MR-Beschreibung (Flavor, Submodul statt JitPack) steht in
`fdroid/README.md`.

**Erwartung dämpfen:** Die Prüfung eines neuen Programms bei F-Droid dauert Tage bis Wochen, sie
passiert ehrenamtlich. Erst nach dem Merge baut und veröffentlicht F-Droid.

**Gute Nachricht:** Nur diese erste Einreichung geht von Hand. `UpdateCheckMode: Tags` plus
`AutoUpdateMode: Version` holen jede spätere Version automatisch vom Tag – kein weiterer MR.

## Fällig in 2.2: `androidx.security` entfernen

Die Bibliothek `androidx.security:security-crypto` ist abgekündigt und steckt auf
`1.1.0-alpha06`. Das Server-Passwort liegt seit 2.1 in `SecretStore` (AES-256/GCM mit einem Schlüssel
aus dem Android-Keystore). Die alte Bibliothek ist **nur noch ein Lesepfad**, damit das Passwort
bestehender Nutzer beim Update nicht verlorengeht.

2.1 ist seit 24.09.2026 draußen; ist es eine Weile im Feld, fällt beides weg. Drei Handgriffe:

1. `app/src/main/java/de/spahr/ausgaben/settings/SecretMigration.java` löschen.
2. Den einen Aufruf `SecretMigration.uebernehmenFallsNoetig(app, store)` in `SecretStore.open`
   entfernen.
3. `libs.security.crypto` aus `app/build.gradle` und den Eintrag `securityCrypto` aus
   `gradle/libs.versions.toml` streichen.

Danach darf `grep -rn "androidx.security" app/` nichts mehr finden. Wer sehr spät aktualisiert, gibt
sein Server-Passwort dann einmal neu ein — das ist der Preis, und ab 2.2 ist er vertretbar.

## Regeln für dieses Projekt

- **Vor jedem neuen Thema `git fetch`**, `git log HEAD..origin/main` ansehen und **nachfragen**, ob die
  Commits vom anderen PC übernommen werden sollen. Nicht selbst entscheiden. (Beim Release 1.11 lagen
  vier ungepushte Commits vor; der Tag lief daran vorbei und musste samt APKs neu gebaut werden.)
- **Jede benutzersichtbare Änderung dokumentieren**, als Teil der Änderung: Handbuch
  (`docs/handbuch_de.json` / `handbuch_en.json`, PDFs neu bauen mit
  `python3 build_manual.py Handbuch-KMySync-de de` bzw. `… Manual-KMySync-en en` aus `docs/` heraus) und
  **beide** READMEs. Deutsch und Englisch parallel halten.
  Selbstverständlichkeiten gehören nicht ins Handbuch – im README ist Technisches in Ordnung.
- **Drei Sprachen vollständig halten:** `values/`, `values-de/`, `values-es/`.
- Commit-Fuß: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Bauen und testen:
  ```bash
  ANDROID_HOME=/home/michael/Android/Sdk ./gradlew \
    :app:assembleFullDebug :app:assembleFossDebug :app:testFossDebugUnitTest
  ```
  Zuletzt: 1017 Tests, 0 Fehler.
