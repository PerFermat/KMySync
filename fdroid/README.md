# F-Droid packaging

KMySync ships two build flavors of the phone app (`:app`):

- **`full`** — with the Wear OS companion bridge over Google Play Services (Wear Data Layer).
  This is what the GitHub releases use (`assembleFullRelease`).
- **`foss`** — identical app **without any Google Play Services** (`assembleFossRelease`).
  This is the flavor F-Droid builds. GMS classes and the Wear listener service live only in
  `app/src/full/`; the `foss` flavor uses a no-op `LanguageSync` stub.

The Wear OS app (`:wear`) requires the Google Wear Data Layer and is therefore **not** distributed
via F-Droid; it stays a GitHub-only artifact (its microphone permission is unaffected).

## Submitting to F-Droid

1. Build locally to confirm the FOSS variant is clean:

   ```bash
   ./gradlew :app:assembleFossRelease
   # verify no Google Play Services classes ended up in the APK:
   unzip -p app/build/outputs/apk/foss/release/app-foss-release*.apk classes*.dex \
     | strings | grep -c com/google/android/gms   # expect 0
   ```

2. Open a Request For Packaging (RFP) issue, or a merge request against
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding
   `metadata/de.spahr.ausgaben.yml`. Use [`de.spahr.ausgaben.yml`](de.spahr.ausgaben.yml) in this
   folder as the starting point (it builds the `foss` flavor; MPAndroidChart is vendored as a git
   submodule and built from source, so the recipe just needs `submodules: yes` — no JitPack, no
   srclib).

3. Store listing texts and screenshots are provided as Fastlane metadata under
   `fastlane/metadata/android/{en-US,de-DE}/` and are picked up automatically.
