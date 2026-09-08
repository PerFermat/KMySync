# KMySync

**English** · [Deutsch](README.de.md)

A mobile companion app for **[KMyMoney](https://kmymoney.org/)** (Android, Java). Record cash expenses,
income and transfers on the go — right on your phone or a Wear OS watch — and export them into KMyMoney,
instead of typing everything in by hand later.

> Offline-first · no account, no ads, no tracking · open source.

> [!NOTE]
> **KMySync was previously called “Ausgaben”.** It is the same program, just under a new name.
> If you already have “Ausgaben” installed, simply install the new version over it — it arrives as
> a normal update: the package id (`de.spahr.ausgaben`) and the signing key are unchanged, so all
> your bookings, profiles, settings and receipts stay exactly where they are. There is nothing to
> export, uninstall or migrate. Only the name and the launcher label change.

📖 The full **[user manual (PDF, English)](docs/Manual-KMySync-en.pdf)** describes every feature in
detail, with screenshots.

<p>
  <img src="screenshots/en/Promo-Datenschutz.png" width="220">
  <img src="screenshots/en/Promo-Syncronisation.png" width="220">
  <img src="screenshots/en/Promo-Alias.png" width="220">
  <img src="screenshots/en/Promo-UhranlagemitAlias.png" width="220">
</p>
<sub>(Promo graphics are in German; the app itself is fully available in English.)</sub>

## Why it might be useful for KMyMoney users

- 📲 **Mobile extension for KMyMoney** — capture cash spending the moment it happens
- 🔌 **Seamless KMyMoney integration** via `.kmy` files or CSV import
- 🗂️ **Sync through a shared WebDAV or SMB folder** — your own server, your data
- 🔒 **Fully offline** — no extra cloud, no vendor account required
- ⌚ **Wear OS app with voice input** — speak an expense right from your wrist
- ➗ **Split bookings and transfers**, categories, places/holdings and portfolio import
- 📈 **Analysis**: history per account, category pie chart, budget (actual/planned), portfolio return
- 🌍 **Multilingual** — ships in English, German and Spanish, more languages via translation upload
- 👆 **Biometric lock**, encrypted credentials, backup & restore
- 🆓 **No ads. Open source.**

## Screenshots

<p>
  <img src="screenshots/en/Kontobuchungen.png" width="140">
  <img src="screenshots/en/Kontenmenü.png" width="140">
  <img src="screenshots/en/Buchung Empfänger.png" width="140">
  <img src="screenshots/en/Buchungen Auswertung.png" width="140">
  <img src="screenshots/en/Kategorien Auswertung.png" width="140">
  <img src="screenshots/en/Budget.png" width="140">
  <img src="screenshots/en/Depot Auswertung.png" width="140">
  <img src="screenshots/en/Einstellungen_1.png" width="140">
</p>

## Download

The current APKs are on the **[releases page](../../releases/latest)**:

- **app-full-release.apk** — the phone app with the Wear OS bridge (Android 8 / API 26 and up)
- **app-foss-release.apk** — the same phone app without Google Play Services (F-Droid build)
- **wear-release.apk** — the Wear OS watch app (spoken expenses to the phone app). Only needed if
  the watch doesn't get the app automatically alongside the phone install; otherwise sideload it onto the
  watch separately.

Both are signed with the same key (required for Wear Data Layer pairing). Enable "Install from unknown
sources" to install.

### Build flavors / F-Droid

The phone app builds in two flavors:

- **`full`** — with the Wear OS bridge via Google Play Services (`./gradlew :app:assembleFullRelease`).
- **`foss`** — the same app **without any Google Play Services**
  (`./gradlew :app:assembleFossRelease`), meant for **F-Droid**. Every feature stays; only the Wear OS
  bridge is missing.

The Wear OS app (`:wear`) needs the Google Wear Data Layer and stays **GitHub-only**. See [`fdroid/`](fdroid/)
for F-Droid packaging notes.

## Features at a glance

This list names the main features only. The exact behaviour, every detail and screenshots are in the
**[user manual](docs/Manual-KMySync-en.pdf)**.

- **Record bookings** — expense, income, transfer, split bookings; a built-in calculator keyboard in the
  amount field.
- **No typing on the go** — voice input ("hairdresser 20 €") and silent amount-only entry: from your
  location and the amount the app suggests the payee and prefills its category. Payees are learnt as
  aliases.
- **Receipts** — a photo or a PDF document per booking, multi-page, transfers included. Photos can be
  cropped and straightened; everything is uploaded into the sync folder. The receipts of a filtered
  selection can be exported as a ZIP file under speaking names.
- **List & filter** — search across payee, note and category, plus amount, date-range and radius
  filters; undo after delete.
- **Organising accounts** — grouped by account kind, plus freely chosen account groups (including ones
  taken from the `.kmy`), free ordering and account search.
- **Analysis** — history per account/place/total, category pie chart, budget (actual vs. planned) and a
  preview of scheduled bookings.
- **Holdings & portfolio** — several cash places per account with reconciliation; portfolio import with
  price history, buys, sells, dividends and gain/loss.
- **Scheduled bookings** — taken from KMyMoney, bookable or skippable one date at a time; the schedule
  moves on by one period with the next export.
- **Sync** — Nextcloud/WebDAV/SMB, `.kmy` mode (reads and writes the KMyMoney file directly) or CSV;
  automatic backup before every export. Bookings changed after the fact are edited in the file rather
  than added twice. KMyMoney's **tags** are read, edited, filtered and written back.
- **Wear OS** — speak an expense right from your wrist, offline too; the watch only captures the text,
  the phone creates the booking. Anything recorded offline is sent on later, without loss or duplication.
- **Security & backup** — optional biometric lock, GPS off by default, encrypted credentials; data and
  settings can be backed up into a file that may be encrypted.
- **Appearance & language** — light and dark theme, app-wide font size, English, German and Spanish
  built in, further languages via a translation file.

## CSV format (export)

Column separator (`;` or `,`) and decimal separator (comma or dot) follow the settings, date
`DD.MM.YYYY`, UTF-8, CRLF. Split bookings are written as one row per category. Import is
language-independent: it reads KMyMoney ledger exports in any language (German, English, …) and re-imports
the app's own export.

```
Datum;Empfänger;Konto;Typ;Betrag;Notiz;Kategorie
29.06.2026;Metzgerei;Bargeld;Ausgabe;-7,30;Mittagessen;Lebensmittel
```

## Tech

**Frame.** Plain Java 17, no Kotlin. Gradle 8.9 / AGP 8.7.3, `compileSdk` and `targetSdk` 34, `minSdk 26`
(`:app`) / `minSdk 30` (`:wear`). Two modules: `:app` (phone, around 180 source files) and `:wear`
(watch, around 15). No dependency-injection framework, no reflection on app code, no analytics, crash
reporting or ad library.

**Layout.** The packages under `de.spahr.ausgaben` are cut by job: `db` (Room and the computing logic),
`export` (KMyMoney and CSV), `net` (WebDAV/SMB), `receipt`, `voice`, `location`, `security`, `settings`,
`backup`, `i18n`, `notify`, `widget`, `wear` and `ui`.

**Storage.** [Room](https://developer.android.com/training/data-storage/room) on SQLite, database
version 44 with an unbroken chain of migrations — an update keeps your data, a fresh install is never
required. Amounts are `long` cents throughout, never floating point.

**KMyMoney.** The `.kmy` file is gzipped XML and is read **and written** directly — splits, transfers,
portfolio and schedules included. Writing happens into the existing tree (same transaction ids in the
same place) so KMyMoney carries on with the file unchanged; a backup is written before every such run.

**Testability.** Everything that computes or decides lives in pure classes **without Android** —
`PayeeAmounts`, `PayeeCategories`, `AccountScope`, `BudgetMath`, `RadiusFilter`, `EditStatus`,
`NoteReceipt` and others. They run under JUnit 4 with no emulator and no mocks; **844 unit tests** at
present, among them checks against real `.kmy` files (via Robolectric, which never ships in the APK).

**Receipts.** Photos and PDFs are kept app-private and uploaded in the background into `Belege/<year>/`
next to the KMyMoney file. The reference is a short tag inside the booking note, so it survives export
and re-import; a PDF is handed to the device's viewer through a `FileProvider`. Opening a receipt that
is not on the phone shows *"Loading – please wait"* and fetches it in the background (`Net.isOnline` plus
a bounded retry in `ReceiptSync.ensureLocalWaiting`); an error appears only when offline. If it stays
unreachable while online, the app offers to drop the orphaned reference, marking the booking as edited.

**Flavors.** Google Play Services exist solely in the `full` flavor under `app/src/full/`; the `foss`
flavor contains not a line of it. Phone and watch app need the same `applicationId` **and** the same
signature, or the Data Layer will not pair them.

**Third-party libraries.** [Room](https://developer.android.com/training/data-storage/room), OkHttp
(WebDAV), [smbj](https://github.com/hierynomus/smbj) with BouncyCastle (SMB2/3),
[MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) — as a source submodule, since F-Droid does
not allow JitPack —, [osmdroid](https://github.com/osmdroid/osmdroid) (map picker, no API key),
[androidx.security](https://developer.android.com/jetpack/androidx/releases/security) (encrypted prefs),
[androidx.biometric](https://developer.android.com/jetpack/androidx/releases/biometric), plus
[play-services-wearable](https://developer.android.com/training/wearables/data/data-layer) and
[androidx.wear.tiles](https://developer.android.com/training/wearables/tiles) — the last two only in
`full` and `:wear` respectively.

## Building

```bash
./gradlew assembleDebug
```

The Android SDK is located via `local.properties` (`sdk.dir=…`) — this file is not checked in and must be
present locally (Android Studio creates it automatically). A signed release build needs
`keystore.properties` (also not checked in); without it an unsigned release is produced.

## Setting up a sync target (Nextcloud / WebDAV / SMB)

In the settings choose the **server type**, then enter base URL/share, username and password; a **"Test
connection"** button checks the credentials. Without a configured sync target, export goes locally into a
folder you choose.

- **Nextcloud**: base URL of the server + an **app password** (Nextcloud → Settings → Security → App
  password).
- **WebDAV (generic)**: the full DAV root URL, auth via HTTP basic.
- **SMB/Samba**: a **setup wizard** scans the local network for servers, then you pick a share and
  browse to the target folder. SMB2/3, encrypted, anonymous and DFS shares included.
- **Diagnostics**: the button "Check connection (diagnostics)" walks the whole chain up to the write
  permission and shows, per step, the result, the duration and the error code. The report can be copied
  and contains neither the password nor the user name.

Every detail — ports, guest access, domains, error cases — is in the
**[user manual](docs/Manual-KMySync-en.pdf)**.

## License

Released under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

## Disclaimer

This project was initially developed with extensive AI assistance.

I have been working as a software developer for approximately 25 years, but primarily in technologies outside the modern mobile application ecosystem. While I have experience with Java and have reviewed parts of the codebase, I cannot claim to fully understand every implementation detail generated during the development process.

The application has been tested and is actively used, but there may still be bugs, architectural shortcomings, or code that could be improved by developers with more Android-specific experience.

I am continuously reviewing, learning from, and refining the generated code. Contributions, code reviews, bug reports, and suggestions are therefore especially welcome.
