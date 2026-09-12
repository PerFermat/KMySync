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

2. Tag the release (`git tag v2.0 && git push origin v2.0`). The recipe points at that tag, so
   without it the F-Droid build fails immediately.

3. Open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding
   `metadata/de.spahr.ausgaben.yml`. **Copy [`de.spahr.ausgaben.yml`](de.spahr.ausgaben.yml) from
   this folder verbatim** — it is kept ready to paste, which is why it carries no notes addressed
   at us; everything explaining *our* side lives in this README instead. Keep the file name and the
   package id `de.spahr.ausgaben`: F-Droid keys apps by applicationId, not by display name, and
   keeping it is what lets existing installs update across the rename to KMySync. The display name
   comes from `app_name` / fastlane `title.txt` and needs no entry in the recipe.

   Only this first submission is manual. `UpdateCheckMode: Tags` plus `AutoUpdateMode: Version v%v`
   make F-Droid pick up every later version from its tag on its own — no further merge request.

   What fdroiddata expects of the merge request itself (from its **App inclusion** template):

   - the **title must read `New app: <app name>`** — so `New app: KMySync`;
   - the **fork must be public** and the source branch **must not be protected**, because fdroiddata
     merges fast-forward and has to be able to rebase;
   - tick "the original app author has been notified" yourself — you are the author;
   - two boxes are already true here and worth pointing out: the Fastlane metadata lives in this
     repository, and releases are tagged with auto-update enabled;
   - "external repos as git submodules instead of srclibs" is likewise already the case
     (MPAndroidChart, pinned to v3.1.0).

   One box deserves a deliberate answer rather than a reflex: **Reproducible Builds**. Enabling it
   means F-Droid verifies its build against the APK signed with your key and then ships *your*
   signature, so users can move between the GitHub download and F-Droid without reinstalling.
   Declining means F-Droid signs with its own key — and the template is explicit that this **cannot
   be switched on later**. The GitHub release notes for 2.0 already advertise the build as
   reproducible from source.

4. Store listing texts and screenshots are provided as Fastlane metadata under
   `fastlane/metadata/android/{en-US,de-DE,es-ES}/` and are picked up automatically. From the
   **second** F-Droid release onwards, each one needs a `changelogs/<versionCode>.txt` per language:
   F-Droid shows the file whose name matches the versionCode being published. The first release
   deliberately has none — a "what changed" text would refer to versions that were never on F-Droid.
   The existing `changelogs/{1,2,6,7,8,13}.txt` belong to the GitHub history and stay unpublished
   here, because only the packaged versionCode is ever shown.

   A `changelogs/14.txt` was written once and removed again for exactly that reason. Note that it is
   still part of tag `v2.0`: if the F-Droid build takes its metadata from the tagged commit, the
   first release will show it after all. Removing it here keeps the repository honest and keeps it
   out of everything built from `main` onwards.
