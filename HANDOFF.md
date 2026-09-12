# Arbeitsstand: erste Einreichung bei F-Droid

> **Übergabenotiz, kein dauerhafter Bestandteil des Projekts.** Sie existiert, weil Michael an mehreren
> Rechnern arbeitet und der andere Claude die lokalen Notizen dieses Rechners nicht sieht. Sobald die
> Einreichung durch ist, kann sie gelöscht werden.
>
> Stand: 2026-09-12. **2.0 ist veröffentlicht** – Tag `v2.0` (Commit `c08dcef`) und GitHub-Release mit
> den drei signierten APKs sind draußen. Offen ist nur noch der Merge Request; siehe unten.

## Worum es geht

KMySync (früher „Ausgaben") soll erstmals zu F-Droid. Der GMS-freie `foss`-Flavor ist geprüft und
fertig, das Rezept liegt bereits im GitLab-Fork bereit. Es fehlt nur noch der Tag – und davor ein paar
Tage Alltagstest.

## Der eine Punkt, der leicht falsch gemacht wird

**Version 2.0 ist nirgends veröffentlicht.** Es gibt keinen Tag `v2.0` (letzter Tag: `v1.12`) und kein
GitHub-Release dafür; die 2.0 liegt nur als Testinstallation auf Michaels Pixel 7.

Fällt beim Testen ein Fehler auf, wird er **in der 2.0 behoben** und die 2.0 danach veröffentlicht.
Kein Sprung auf 2.1, kein neuer versionCode, keine Änderung am Rezept. Eine Versionsnummer ist erst ab
der Veröffentlichung bindend – ein bereits gepushter Tag dürfte dagegen nie verschoben werden.

Was bei einer Codeänderung tatsächlich zählt, ist nicht die Nummer, sondern die **Testtage**: Danach
gilt das bisher Getestete nur für den alten Stand. Bei einem Eingriff in den Export lohnt es, neu zu
zählen. Deshalb: Änderungen an `app/`, `wear/`, `mpandroidchart/` nur nach Rückfrage. Metadaten
(`fastlane/`, `fdroid/`, `docs/`, READMEs) sind unbedenklich, sie berühren das APK nicht.

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
- `scanignore` für `app/build.gradle`, `app/src/full`, `wear` ergänzt: Google Play Services stehen im
  Quellbaum, landen aber in keinem gebauten Artefakt. Vorsichtsmaßnahme gegen den F-Droid-Scanner, der
  den ganzen Baum durchsucht, nicht nur das Gebaute.
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
- `fdroid/fdroiddata` geforkt nach **`gitlab.com/PerFermat/Data`**, öffentlich, nur Branch `master`.
- Das Rezept liegt dort als `metadata/de.spahr.ausgaben.yml` im Branch
  **`add-kmysync-de.spahr.ausgaben`**. **Noch kein Merge Request** – zum Zeitpunkt dieser Notiz fehlte
  der Tag `v2.0`; inzwischen gibt es ihn, der MR kann eröffnet werden.

## Erledigt am 2026-09-12

1. ~~Remote abgleichen.~~
2. ~~Taggen und pushen.~~ Tag `v2.0` zeigt auf `c08dcef`.
3. ~~GitHub-Release.~~ <https://github.com/PerFermat/KMySync/releases/tag/v2.0> mit den drei signierten
   APKs, Notes samt SHA-256-Summen und Zertifikat-Fingerabdruck. Vor dem Tag geprüft: versionCode 14 /
   versionName 2.0 in beiden APKs, Signaturzertifikat gleich wie bei v1.12 (`571fd757…afc3c`), im
   `foss`-APK **0** Treffer auf `com/google/android/gms` (im `full`-APK 1557).

   **Abweichung von der Planung:** `changelogs/14.txt` wurde in allen drei Sprachen angelegt und liegt
   im Tag, obwohl die Erstveröffentlichung bewusst ohne Änderungstext geplant war (siehe den Punkt
   weiter oben). Bewusst belassen, statt einen gepushten Tag zu verschieben – Einordnung steht in
   `fdroid/README.md`.

## Offen

4. **Merge Request** im Fork eröffnen, von `add-kmysync-de.spahr.ausgaben` gegen `fdroid/fdroiddata`
   `master`.
   **Achtung:** fdroiddata schreibt ein **Format für den MR-Titel** vor (Commit „Require MR title format
   in MR templates"). Welches genau, ist offen – GitLab zeigt es in der MR-Vorlage. Erst lesen, dann
   formulieren.
5. Danach `versionName`/`versionCode` im Repo auf die nächste Entwicklungsversion heben.

Ein Entwurf für die MR-Beschreibung (Flavor, scanignore-Begründung, Submodul statt JitPack) steht in
`fdroid/README.md`.

**Erwartung dämpfen:** Die Prüfung eines neuen Programms bei F-Droid dauert Tage bis Wochen, sie
passiert ehrenamtlich. Erst nach dem Merge baut und veröffentlicht F-Droid.

**Gute Nachricht:** Nur diese erste Einreichung geht von Hand. `UpdateCheckMode: Tags` plus
`AutoUpdateMode: Version v%v` holen jede spätere Version automatisch vom Tag – kein weiterer MR.

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
