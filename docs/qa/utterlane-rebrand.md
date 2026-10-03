# Utterlane rebrand verification

Date: 2026-10-04. Base source: `cc23968`.
Branch/worktree: `feat/utterlane-rebrand`, `.worktrees/utterlane-rebrand`.
Target: LDPlayer, `emulator-5554`, Android API 28, x86_64 with ARM64 translation.

## Scope and identity

Application ID and source namespace: `io.github.lrq3000.utterlane`.
First-party source/test packages, components, custom actions, JNI exports,
native library, theme, localized branding and release artifact names were
renamed together. The app installs alongside upstream with separate data.
Third-party package names and licenses remain authentic.

Original artwork SHA-256 (copy verified byte-for-byte):
`411769b4143a1b7f35251d94a245081accd466604b3de2d524cd77b27cb48061`.
The design document describes derived assets and launcher-safe geometry.

## Build and packaged-artifact verification

Commands used with JDK 21 and the installed Android SDK:

```text
gradlew.bat --offline --console=plain testDebugUnitTest
gradlew.bat --offline --console=plain testDebugUnitTest assembleDebug assembleDebugAndroidTest
gradlew.bat --console=plain assembleRelease
gradlew.bat --offline --console=plain --quiet testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
python tools/qa/check_rebrand.py --aapt <SDK>/build-tools/35.0.0/aapt.exe --apk app/build/outputs/apk/debug/app-debug.apk --apk app/build/outputs/apk/release/app-release-unsigned.apk
git diff --check
```

- Baseline JVM tests passed before the rename.
- **29 JVM tests passed** under the new namespace: 15 pipeline, 4 telemetry,
  3 model-catalog, and 7 history tests; zero failures/errors/skips.
- Debug application and instrumentation APK builds passed.
- The minified **unsigned release APK** built successfully, including vital lint.
  The first offline attempt lacked cached lint-gradle; the online retry retrieved
  that dependency and completed. No check was disabled to get a passing build.
- Both APKs passed application-ID, all-localized-label, manifest-component,
  provider-authority, transcribe-action, DEX-namespace and JNI-symbol checks.
- The packaged library is `libutterlane_crisp.so`, with all three renamed
  `CrispParakeetBackend` JNI entry points.
- The source audit found no obsolete names in current application code or paths;
  README local image/document links resolved and the design hash matched.

The source-built sherpa-onnx AAR and pinned native sources were copied from
existing local prerequisites into this worktree. This is not a claim of a fresh
sherpa-onnx source build or F-Droid validation.

## Runtime checks

Installed both APKs with `adb -s emulator-5554 install -r`, then ran focused
tests with `adb shell am instrument -w -e class ...`:

| Test under `io.github.lrq3000.utterlane` | Result |
| --- | --- |
| `ModelRecoveryAndroidTest#q8ModelsValidateAndTranscribeNormalWindowsInWorker` | Passed: Ultra and Redux Q8 loaded, validated, and each transcribed two 12-second windows through the renamed worker/JNI backend |
| `CapturePanelAndroidTest#realPanelReportsSilenceAndCentralButtonStopsCapture` | Passed |
| `AudioIntegrationAndroidTest#voiceActivityPreviewsSpeechAndReturnsFinalCallerResult` | Passed |
| `AudioIntegrationAndroidTest#floatingMicrophoneInsertsLiveTextIntoPinnedEditor` | Passed, including accessibility connection and actual insertion |
| `AudioIntegrationAndroidTest#voiceImeCommitsLiveTextAndDrainsOnDone` | Passed after fixture corrections below |

Share (`ACTION_SEND`, audio/wav) and Open With (`ACTION_VIEW`, audio/wav,
content URI) resolved to the new `TranscribeActivity`. `ime list -s -a` listed
the new input service alongside upstream. Settings loaded Redux Q8 and displayed
**Loaded and ready** under the new name.

### IME fixture investigation

Initially, the IME test timed out before capture. Matching application/source
namespaces produce the shortened Android ID
`io.github.lrq3000.utterlane/.ime.VoiceInputMethodService`, whereas the mechanical
rename kept a long form. The fixture now uses `flattenToShortString()`.

A second issue remained: the emulator could replace transient `ime set`
selection with the persisted keyboard when the settings observer ran. During
failure, `dumpsys input_method` showed Pinyin bound to the editor; Utterlane's
service was created then destroyed without starting an input view. The original
installed upstream test passed as a comparison.

The fixture now writes the canonical default-IME setting after configuring the
hardware-keyboard setting; teardown restores the original selection and enabled
list. The live-capture/text-commit test then passed in 27.995 seconds. Production
recording code was not altered for the fixture. The diagnostic procedure is
preserved in `tools/qa/ime_evidence.py`.

## Visual evidence

- Inspected banner, standalone icon and circular/rounded/themed mask previews.
- Inspected the live launcher: Utterlane has the U-and-waveform icon and remains
  distinct from the installed upstream app.
- Inspected Settings header and notification/status-bar branding.
- Replaced both store screenshots with real English portrait captures of the
  renamed app; the README embeds these images.
- Restored the original French-only locale list and 1920 × 1080 display size.

Store screenshots are committed under `fastlane/metadata/android/en-US/images`;
the mask preview is under `docs/design`. Other UI-tree/runtime captures remain
local ignored `qa-artifacts` evidence.

## Historical references and limits

Old names remain in copyright/lineage text, authentic upstream changelog URLs,
dated QA evidence and older plans, plus the audit's explicit checks. These are
listed in `RebrandAudit.HISTORICAL`, not live application component IDs.

- No F-Droid CLI or fdroiddata checkout was available; the new application ID
  requires its own submission and F-Droid build validation.
- Android 13 themed icons have a generated mask/tint preview, not a live API 33+
  test. The runtime target is API 28.
- No new Q4 or physical-phone performance claim is made.
- The release APK is unsigned; no release/store submission was performed.
- Links target the approved future `lrq3000/Utterlane` URL. Repository rename,
  push, merge and publishing are separate maintainer operations.
