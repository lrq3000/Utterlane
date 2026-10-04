# Publish Utterlane 2.0.0

Targets: **F-Droid** and **Google Play, free of charge**. Direct downloads use
GitHub Releases, which Obtainium and Komi Store can consume. Application ID:
`io.github.lrq3000.utterlane`; version: **2.0.0 (11)**; Android 8+, ARM64.

This is preparation for a new listing. Upstream TranSlander accounts, approvals,
signatures, test history and listings do not transfer to this application ID.
See [latest-main integration results](../qa/store-release-main-first.md) and
[original preparation results](../qa/store-release-2.0.md) for what was actually
tested, and the [release notes](release-notes-2.0.0.md) for the announcement.

## Before publication

1. Review and integrate this worktree's changes. Check the English screenshots
   and the new machine-translated accessibility disclosure in supported locales.
2. Run the build and artifact checks below from a fresh checkout with the pinned
   toolchain. Test the minified release on a current Android device, including
   Android 16 and a 16 KB page-size environment. ELF/ZIP checks do not replace
   runtime testing.
3. Choose your signing arrangement before the first public release. Back up the
   private keystore and passwords securely; publish only its certificate fingerprint.
4. Create and publish the GitHub `v2.0.0` release from the reviewed release commit.
5. Submit F-Droid metadata and upload the signed Play bundle, as described below.
6. Update README status buttons to actual store listing URLs after publication.

## Build and inspect

Install OpenJDK 21, Python 3.11.8+, Git, SDK platforms 35 and 36, build tools 35.0.0,
NDK 28.2.13676358 and SDK CMake 3.22.1 (with Ninja). Set `JAVA_HOME` and
`ANDROID_HOME` to absolute paths. Gradle 8.12.1 is supplied by the wrapper.

```sh
python tools/prepare_native.py
python tools/build_sherpa.py
python -m unittest discover -s tools/tests
./gradlew --console=plain --quiet testDebugUnitTest assembleDebug assembleRelease bundleRelease
python tools/release_artifacts.py app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/bundle/release/app-release.aab
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging app/build/outputs/apk/release/app-release-unsigned.apk
```

On Windows use `gradlew.bat`, `.exe` SDK tools and PowerShell environment syntax.
With signing enabled the APK filename is `app-release.apk`, not
`app-release-unsigned.apk`. Confirm package ID, version 11 / 2.0.0, minSdk 26,
targetSdk 36, `arm64-v8a`, and absence of `debuggable`/`testOnly` in the merged
release manifest. Validate the AAB with current Google bundletool/Play Console;
check that its bundle configuration requests 16 KB alignment.

Native source inputs are pinned in `tools/prepare_native.py` and
`tools/build_sherpa.py`. The latter compiles sherpa JNI/Kotlin bindings from source
and uses **official MIT-licensed ONNX Runtime 1.23.2 from Maven Central**, checked
against SHA-256 `82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827`.
The separately pinned transcribe.cpp source builds the compact ternary backend;
its vendored ggml remains isolated from CrispASR's ggml.
The builder does **not** build ONNX Runtime itself. F-Droid permits freely licensed Maven
Central dependencies under its inclusion policy. Runtime licenses/notices are
packaged in `assets/native-licenses/`; CrispASR/ggml notices are in
`assets/native-licenses.txt`. Model weights are separate CC-BY-4.0 downloads.

## Signing and GitHub Releases

### Key choice

- **GitHub:** use a stable developer distribution key for every update.
- **F-Droid:** the initial recipe uses F-Droid signing. Expect a different
  certificate from GitHub; do not claim cross-channel in-place updates.
- **Google Play:** new apps use Play App Signing. The upload key authenticates
  your AAB; the **app signing key** signs the APK users receive. If you want
  GitHub ↔ Play updates to work, supply the same developer distribution key as
  the Play app signing key during initial setup, using Play's PEPK instructions.
  If Google generates the app signing key, keep channels separate and document
  the certificate mismatch. A dedicated upload key can be configured afterward.

Create a key locally if you do not already have an appropriate one:

```sh
keytool -genkeypair -keystore /secure/path/utterlane-release.jks -alias utterlane -keyalg RSA -keysize 4096 -validity 10000
```

Use the password prompts. Keep the keystore outside the repository. Never use the
development debug key or generate a fresh distribution key on each CI run.

### GitHub Actions setup

1. In `lrq3000/Utterlane`, create an Actions environment named **release**.
2. Add these environment secrets:
   - `UTTERLANE_KEYSTORE_BASE64`: the keystore encoded as one Base64 string.
   - `UTTERLANE_STORE_PASSWORD`.
   - `UTTERLANE_KEY_ALIAS`.
   - `UTTERLANE_KEY_PASSWORD`.
3. Configure the environment's permitted branches/tags and reviewers to match
   your maintainer workflow. PR builds receive no signing credentials.
4. Commit/integrate the reviewed preparation and create tag **v2.0.0** at that
   commit. Pushing the tag runs the existing Android tag workflow, which now uses
   the same signing helper/secrets and creates the GitHub release. Edit its notes
   using `release-notes-2.0.0.md` (replace relative links with full GitHub URLs).
   Alternatively, manually publishing a release triggers the Build APK workflow;
   both paths use the same signed artifact names and validation.
5. The release workflow tests and builds, then signs and uploads:
   - `Utterlane-2.0.0-arm64-v8a.apk`
   - `Utterlane-2.0.0.aab`
   - `SHA256SUMS`
6. Save the `Utterlane-release-unsigned` workflow artifact privately for its R8
   `mapping/release/mapping.txt`. Upload that mapping to Play if requested.
7. Check the public APK with `apksigner verify --print-certs` and publish the
   resulting SHA-256 certificate fingerprint with the release announcement.

Missing signing secrets intentionally fail publication rather than attach a
debug APK. Ordinary push/PR builds produce debug and unsigned release artifacts.
The signing helper is intended for the Ubuntu GitHub runner. Locally, Gradle
signing works on Windows and Linux through these **four environment variables**:

```text
UTTERLANE_KEYSTORE=/absolute/path/utterlane-release.jks
UTTERLANE_STORE_PASSWORD=<your keystore password>
UTTERLANE_KEY_ALIAS=utterlane
UTTERLANE_KEY_PASSWORD=<your key password>
```

These are setup examples, not credentials to paste into source control. If none
are supplied, release artifacts remain unsigned for F-Droid/local inspection;
if only some are supplied, Gradle fails with an explanatory error.

## F-Droid submission

1. Publish the reviewed source and `v2.0.0` tag. Resolve its **full 40-character
   commit hash**, e.g. `git rev-parse v2.0.0^{commit}`.
2. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) on GitLab. Create a
   packaging branch in a fresh checkout of your fork, following that project's
   contributor guidance.
3. From the Utterlane checkout, generate the metadata into that checkout:

   ```sh
   python tools/fdroid_metadata.py --commit FULL_RELEASE_SHA --output /absolute/path/fdroiddata/metadata/io.github.lrq3000.utterlane.yml
   ```

   Replace `FULL_RELEASE_SHA` with the actual SHA. The generator checks that its
   source contains version 2.0.0 / 11, and refuses to overwrite an existing recipe.
   The `.yml.in` is a template, **not** a file to submit with its token intact.
4. In a supported Linux F-Droid build environment, run from **fdroiddata**:

   ```sh
   fdroid readmeta
   fdroid rewritemeta io.github.lrq3000.utterlane
   fdroid lint io.github.lrq3000.utterlane
   fdroid build io.github.lrq3000.utterlane
   ```

   Alternatively push the metadata to your fork and inspect its F-Droid CI build.
   A passing app Gradle build or source scan does not prove this full build passes.
   Include the build log in the submission. If an unsigned APK from an earlier
   attempt exists, F-Droid may skip rebuilding it; remove only that generated
   output when a genuinely new verification is required.
5. Open a **New App** merge request to fdroiddata with the metadata file and the
   [submission text](submission-texts.md#f-droid). The RFP queue is an alternative
   if you need packager help; do not open duplicate MR/RFP requests.
6. Answer review questions. After acceptance/build/publication, update the README
   button to `https://f-droid.org/packages/io.github.lrq3000.utterlane/`.

The recipe materializes pinned CrispASR, ggml, transcribe.cpp, sherpa and CMake dependency sources
**before scanning**, removes only unused upstream example/test trees (preserving
CrispASR's runtime sources under `examples/`), and compiles
the AAR **after scanning**. No blanket `scanignore` is used. Downloaded model
weights are not bundled in the APK. Tag-based updates read the version fields
from Gradle. Reproducible builds/developer-signature reuse remain a separate,
unverified milestone.

For a local source-scan rehearsal, install fdroidserver in a separate Python
environment and run `tools/qa/check_fdroid.py --output /fresh/staging/path` after
source preparation. It copies inputs, applies the same pruning and runs the real
scanner. Its all-zero commit is exclusively a syntax-check fixture and must never
be submitted. Run `fdroid lint io.github.lrq3000.utterlane` from that staging path.

## Google Play submission — free app

1. Sign in to [Play Console](https://play.google.com/console/), complete account
   and device verification applicable to your account, and choose **Create app**.
   Set name **Utterlane**, default language **English (United States)**, type
   **App**, and price **Free**. Complete the developer declarations.
2. Configure **Play App Signing** using the signing decision above before your
   first upload. Upload the signed `Utterlane-2.0.0.aab` to **Internal testing**.
   Confirm application ID, version code, supported devices and 16 KB compatibility.
   This app requires an ARM64 phone and a separately downloaded/imported model.
3. Fill the main store listing from `fastlane/metadata/android/en-US/`:
   `title.txt`, `short_description.txt`, `full_description.txt`, `images/icon.png`
   (512×512), `images/featureGraphic.png` (1024×500), and both phone screenshots.
   Category suggestion: **Tools** or **Productivity**, whichever best matches
   your final listing. Contact: **LRQ3000@GMAIL.COM**.
4. Provide a public privacy-policy URL. The current in-app link is
   `https://github.com/lrq3000/Utterlane/blob/main/PRIVACY_POLICY.md`.
   Confirm it opens without authentication after the policy changes are published.
5. Complete **App content**: no ads; no login needed; target audience and content
   rating according to your actual intended users; Data safety; Accessibility API;
   foreground-service declarations. Use [reviewer/declaration drafts](submission-texts.md).
   Do not claim a disability-focused accessibility-tool exemption merely because
   voice typing is useful to people with disabilities.
6. Record short videos on the release build demonstrating the accessibility
   disclosure/consent and insertion, microphone foreground operation/stop, and
   optional folder-monitoring foreground operation/disable. Upload accessible
   video links where Play requests them. These must show this app, not upstream.
7. Run the internal test and review the pre-launch report. Test Android 16 behavior,
   gesture/system-bar insets, keyboard integration, permissions, sharing, file
   transcription, and 16 KB native loading on an appropriate device/emulator.
8. If your **personal account was created after November 13, 2023**, the current
   documented production-access process requires at least **12 testers opted in
   continuously for 14 days** in a closed test. Follow your Console's actual
   account requirements, collect real feedback, and apply for production access.
9. Choose distribution countries, keep the app **Free**, and submit the production
   release for review. Update README status only once the listing is live:
   `https://play.google.com/store/apps/details?id=io.github.lrq3000.utterlane`.

### Data safety review points

- Audio, transcripts, word corrections and optional recording history are local;
  on-device-only processing is outside Play's definition of collection.
- User-requested insertion, clipboard use and sharing need to be considered under
  Play's user-initiated-transfer exceptions, not silently treated as never sharing.
- Model downloads contact Hugging Face/CDN hosts and expose an IP address and
  request metadata. Confirm their applicable handling/logging and the final
  dependency behavior before answering the global collection questions. Do not
  select “no data collected” solely because speech recognition is offline.
- The app has no accounts, ads or analytics SDKs. Local deletion is through
  history controls/retention, model deletion, or Android app storage/uninstall;
  exported copies belong to the receiving app/user.
- The maintainer is responsible for final Console answers; these drafts do not
  constitute a completed or submitted Data safety declaration.

## Sources checked for this preparation (2026-10-04)

- [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [F-Droid submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [F-Droid build metadata](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Play personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Play Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Obtainium deep links](https://wiki.obtainium.imranr.dev/deep_links/)
- [Komi Store](https://github.com/komi-store/komi-store)

Recheck time-sensitive requirements in the Console when submitting. Account
verification, signing-key custody, final policy answers, closed testing where
applicable, and store review are maintainer-controlled publication steps.

Further technical reference: [Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes).
That page could not be retrieved in this preparation environment; the recorded
alignment results come from direct inspection of the built artifacts.
