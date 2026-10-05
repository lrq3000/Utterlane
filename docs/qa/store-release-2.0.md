# Utterlane 2.0.0 distribution preparation evidence

Date: 2026-10-04. Base: `cad85a1`. Worktree: `.worktrees/store-release`.
Branch: `release/2.0-store-preparation`. Application: `io.github.lrq3000.utterlane`.

## Build and artifact checks

The baseline JVM tests passed before release changes. After the changes:

```text
python tools/build_sherpa.py
gradlew.bat --console=plain --quiet testDebugUnitTest assembleDebug assembleRelease bundleRelease assembleDebugAndroidTest
python -m unittest discover -s tools/tests
python tools/qa/check_store.py --aapt <SDK>/build-tools/35.0.0/aapt.exe --apk app/build/outputs/apk/release/app-release-unsigned.apk
python tools/release_artifacts.py app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/bundle/release/app-release.aab
<SDK>/build-tools/35.0.0/zipalign.exe -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
java -jar .native-cache/bundletool-all-1.18.3.jar validate --bundle app/build/outputs/bundle/release/app-release.aab
java -jar .native-cache/bundletool-all-1.18.3.jar dump config --bundle app/build/outputs/bundle/release/app-release.aab
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 -shellcheck= -pyflakes= .github/workflows/build_apk.yml
git diff --check
```

- **41 JVM tests passed**, no failures/errors/skips: pipeline 16, metrics 4,
  model catalog 5, sleep/recovery 9, history 7.
- **10 Python tooling tests passed**, covering ELF/digest validation and publishing
  guards. The initial ELF test run failed because the new validator did not exist;
  the implemented validator then passed those cases.
- Debug APK, minified unsigned release APK, unsigned release AAB and test APK built.
- One final incremental compilation logged a Kotlin daemon temporary-directory
  cleanup error on Windows; Gradle's compiler fallback succeeded, and the overall
  build returned exit code zero. No caches were deleted or checks bypassed.
- The release manifest has version 2.0.0 / 11, minSdk 26, targetSdk 36, ARM64 only,
  and neither debug nor test-only flags.
- All three shipped libraries (`libonnxruntime.so`, `libsherpa-onnx-jni.so`,
  `libutterlane_crisp.so`) passed ARM64 ELF 16 KB load-segment checks in APK/AAB.
  APK ZIP alignment passed independently.
- Bundletool **1.18.3** validated the AAB; bundle configuration explicitly reports
  `uncompressNativeLibraries.alignment = PAGE_ALIGNMENT_16K`.
- Source-built sherpa 1.12.23 uses official Maven ONNX Runtime 1.23.2, verified
  against the pinned SHA-256. Upstream native licenses/notices are packaged.
- All six store metadata locales are within title/description/changelog limits.
  Icon is 512×512, feature graphic 1024×500, both screenshots 1080×1920.
  New privacy/disclosure resources exist in all 24 existing UI resource locales.
- Actionlint passed; ShellCheck and Pyflakes were not enabled in that invocation.

Local builds used Windows JDK 21.0.5 (Oracle), the installed Android SDK, NDK
28.2.13676358 and CMake 3.22.1. The hosted workflow selects Temurin OpenJDK 21;
the F-Droid recipe selects Debian OpenJDK 21. Local results do not establish a
F-Droid Linux/toolchain build or byte-for-byte reproducibility.

## F-Droid checks

Installed fdroidserver **2.4.5** into a worktree-local Python environment. Ran
`tools/qa/check_fdroid.py` on copies of actual app/native sources. It uses current
fdroiddata category definitions and a synthetic all-zero commit solely for local
metadata syntax checking; it is not the submission file.

Initial scan: **46 errors**, all in unused upstream bindings, example package
manifests, Wear OS examples and test model binaries. Targeted removal of those
unused trees via `tools/prepare_fdroid.py` reduced the scan to **zero errors**.
No `scanignore` was added. The scanner retained two warnings for SentencePiece
test models (`bbpe.model` and `bpe.model`); these are upstream test data, not app
runtime libraries. The Windows fdroidserver tool also warned that it could not
find a suitable apksigner; Android SDK 35.0.0 apksigner/zipalign were used directly
for artifact checks rather than claiming fdroidserver signed anything.

`fdroid lint io.github.lrq3000.utterlane` passed against the staged metadata with
the official current category taxonomy. The first attempt using fdroidserver's
old built-in categories was replaced with the repository configuration.

**Build after scanning:** the scanner deletes Gradle wrappers. Using the installed
Gradle **8.12.1** through `--gradle`, rebuilt sherpa JNI/bindings and then
`assembleRelease` successfully from the pruned/scanned source copy at
`.native-cache/fdroid-check-4/source`. This demonstrates that removed examples and
test assets are not required. It is a Windows source-build rehearsal, **not** a
completed `fdroid build` in the official Linux environment.

## Device checks and screenshots

Target: LDPlayer `emulator-5554`, API 28, ARM64 translation. During an early run,
another process replaced the app with version 1.2.4. Those overlapping results
were not relied on for final verification. After the maintainer reserved the
emulator, reinstalled both APKs and explicitly verified version **11 / 2.0.0** and
targetSdk **36** before running these tests separately:

| Check | Result |
| --- | --- |
| `AudioIntegrationAndroidTest#voiceActivityPreviewsSpeechAndReturnsFinalCallerResult` | Passed, 30.646 s; sherpa Parakeet native inference and result delivery |
| `ModelRecoveryAndroidTest#q8ModelsValidateAndTranscribeNormalWindowsInWorker` | Passed, 119.209 s; Ultra and Redux Q8 inference in the worker |

Signed a **local test copy** of the minified release APK with the existing debug
certificate so it could update the emulator app without deleting its models.
This copy is under ignored `qa-artifacts/Utterlane-2.0.0-release-localtest.apk` and
is **not a public release artifact**. The actual distribution APK/AAB remain
unsigned pending maintainer release credentials.

On that minified build:

- Settings loaded the Redux Q8 model and displayed **Loaded and ready**.
- Opened the real transcription activity with `/sdcard/Download/speech-source.wav`.
  Actual result: “Ask not what your country can do for you, ask what you can do for
  your country.” This is the existing JFK fixture documented by
  `tools/qa/audio_fixture.py`, not a fabricated transcript.
- Captured and visually inspected fresh English screenshots from that build:
  `fastlane/metadata/android/en-US/images/phoneScreenshots/{1,2}.png`.
- Opened the corrected Accessibility disclosure and exercised **Decline**.
- The Privacy policy row launched the browser. Chrome showed its first-run screen;
  browser terms were not accepted and a page render was not claimed.
- Restored French-only `fr-FR`, physical display 1920×1080, density 280, and installed
  the 2.0.0 debug build again for subsequent development.

## Remaining publication gates

- Review and integrate the changes, then publish source/tag and generate F-Droid
  metadata with the actual release commit SHA.
- Run the complete recipe in Linux F-Droid CI/buildserver; no full official
  F-Droid build was performed here.
- Test Android 16 behavior and native loading on an actual 16 KB environment;
  LDPlayer API 28 cannot establish these results.
- Supply the stable signing key and Actions secrets; exercise the hosted release
  workflow. Production-key signing and Play upload have not been performed.
- Complete Play account setup, policy forms and demonstration videos, and closed
  testing if required by the account. No public store submission has been made.

The maintainer's step-by-step instructions and draft form text are in
[`docs/distribution/`](../distribution/README.md).
