# Progressive archival / microphone root integration — 2026-10-11

**Source integration and requested build verification complete: 1090 JVM tests
passed, zero failures/skips; debug app and Android-test APK assemblies passed.**
Android execution of this combined tree remains parent-owned.

## Workspace and provenance

- All project inspection, edits, Git operations and builds used
  `C:\git\TranSlander`, branch `feat/progressive-archival-project`, starting at
  `72dc059cc9684e64b5f38f10d02daa54c8158086`.
- The parent had already applied the approved archival stash on that newer main
  tree. Seven files were unmerged. Git's stage 2 was the new microphone/HFP main
  implementation; stage 3 was archival. The preserved backup is
  `c4f391534a60522ec23797668292c5e0b1d47160`, based on `f6975ce`.
- Read `AGENTS.md`, `CORE_VALUES.md`, the approved archival spec, its implementation
  plan, accumulated progressive/shared-import/managed QA notes, and main's commit
  intent/diffs. Compared the integrated conflict files with both main and the
  backup stash. No wholesale ours/theirs resolution was used.
- The old worktree paths and native/UI results in previous QA reports are
  **historical evidence**, not claims about this build. Those reports are unchanged.
- No commit, push, new worktree, other-worktree access, model search, ADB or device
  operation occurred. The parent retains the running emulator and `.dhome` app.
- Only the seven original conflict paths were `git add`ed to mark resolution.
  Other integration edits and the new test/report remain unstaged/untracked;
  previously staged stash content was retained.

## Semantic resolution

| Conflict | Integrated behavior |
|---|---|
| `asr/AudioCapture.kt` | Both microphone-option and actual-format configuration APIs, plus archival prepared-recorder cleanup; existing fake capture defaults remain compatible. |
| `asr/AudioRecorder.kt` | Probe and construct actual non-native PCM using the selected Android source, not hardcoded MIC. Freeze microphone options before probing and reuse them for capture/reopen. Preserve runtime buffer sizing, preferred-device routing, explicit HFP/SCO/communication API choices, mode checks, effects, diagnostics, silencing, Stop and cleanup. Changing source before capture invalidates a prepared recorder. |
| `asr/MicrophoneSession.kt` | Pause app playback after admission; retain archival producer/foreground ownership, rolling capture and independent finalization. Save actual quality and microphone options together. Apply gain only to normalized recognition input and drain its final partial window before ASR finalization. Retain friendly storage errors. |
| `history/RecordingHistory.kt` | Persist/reload microphone gain/source/options alongside import retention, processing obligations, URI publication, representation IDs, rolling layouts and original-source descriptors. Preserve exact publication, fallback repair and pending retry behavior. |
| `transcribe/AudioDecoder.kt` | Main's validated track/output metadata and complete cancellation-safe cleanup coexist with archive-target conversion, URI parts, measured priming removal and source-count clamping. Unannounced output is inspected before computing frame-byte skips, including float/stereo output. |
| `transcribe/AudioPlaybackControls.kt` | Retain speed selection and truthful native/pending status, full-width narrow-screen timeline, and archival's separate narrow-screen Markdown/action row. |
| `transcribe/TranscriptionDialogModel.kt` | Original leased multipart/import recovery takes precedence over retained lossy representations. Every selected decode/PCM route feeds the saved gain choice exactly once, after normalization, with EOF drain. Retain attempts, retention questions, managed Markdown, snapshot export, progress and friendly storage messages. |

### Additional overlap fixes

1. `audio/MicrophoneCaptureConfiguration.kt` now validates the session's expected
   actual PCM tuple (native default remains 16000 Hz mono), preserving explicit
   source, UNPROCESSED support, PCM16 and client-capacity requirements. Its English
   error reports both expected and observed formats. This does not reset presets,
   substitute a microphone source or relax source validation.
2. `audio/AndroidCaptureRoute.kt` uses the recorder's sample rate for its finite
   buffer-time budget and converts interleaved short counts to **frames** before
   advancing route verification. Without these changes, stereo could confirm a
   source prematurely and an 8-kHz client buffer could time out halfway through
   its actual duration. Existing identity/epoch/Phone-fallback rules stay intact.
3. `transcribe/DialogAudioActions.kt` shares the decoder's validated usable-track
   selection during import inspection. An invalid first raw descriptor must not
   classify a later usable compressed track as uncompressed or use the wrong
   duration for the retention question.

Native source PCM remains the archival authority. Non-native capture has genuine
rate/channel declarations and independently normalized ASR input. Software gain
does not modify captured/archived PCM. Native default capture avoids resampling.

## Auto-merged intersections reviewed

- **App build:** AGP 9.2.1 built-in Kotlin, explicit Kotlin/Compose compiler 2.4.21,
  Gradle 9.4.1 and SDK 37 migration coexist with main's Robolectric/MockK dependencies,
  Android-resource JVM testing, heap limit and Android-16 JDK flags. Target 36,
  minimum 26, JDK 21, NDK 28.2.13676358 and the standard app identity remain.
- **Manifest:** Bluetooth/CONNECT and optional Bluetooth hardware declarations
  coexist with the archive worker, parent foreground supervisor, processing/transfer
  permissions and API-28-capped picker permission. No new Bluetooth scan/location
  request is introduced by archival.
- **Application:** local microphone diagnostics and input controller remain in the
  main process. Secondary-process early return protects recognition/archive/test
  worker startup from model/history/DataStore initialization. Parent histories use
  the shared archival journal and URI-aware audio IO.
- **Settings:** atomic HFP/Disabled/Custom settings, independent selected input and
  automatic Bluetooth preference, permission flow and pinch-only floating sizing
  remain. Archival quality/folders and the two remembered import choices use
  separate keys and controls; archival does not rewrite microphone presets.
- **Dialog audio actions:** retained byte-for-byte imports, immutable export/share
  representation selection and original-error-preserving provider cleanup coexist.
  The metadata intersection fix above aligns inspection with decoding.
- **Playback controller:** source leases and asynchronous URI/header preparation
  coexist with capture-safe pause, pending seeks, selected speed and native-rate
  readback. Timeline lengths use rolling source metadata rather than padded native
  container estimates. No additional production controller edit was needed.
- **Documentation:** main's HFP/explicit-source/pinch/speed/storage guidance remains
  alongside archival quality/retention/folder guidance. Toolchain/distribution
  prerequisites and existing F-Droid limitations remain explicit. This report adds
  root evidence without rewriting earlier runtime history.

The transferred codec/journal implementation was retained: measured AAC calibration
and codec-only EOS tail padding, AMR bundled-frame splitting, original-source
leases, bounded metadata jobs, exact URI publication, representation replacement,
cancellation epochs and durable `PENDING_FALLBACK` retry.

## Regression coverage and failure investigation

**Eight new test methods expand to 14 executed cases** (SDK 28/31/36 where annotated):

| Suite | Added executed cases | Boundary |
|---|---:|---|
| `ExplicitCaptureRouteTest` | 6 | Stereo buffer confirmation and full 8-kHz fallback verification budget. |
| `MicrophoneCaptureConfigurationTest` | 3 | Accept actual 48-kHz stereo, reject source substitution and native PCM mislabeled as stereo. |
| `AudioRecorderArchivalConfigurationTest` (new) | 1 | Real recorder-construction path requests every explicit source with 48-kHz stereo and the snapshotted 0.25-second buffer. Only the native AudioRecord boundary is mocked. |
| `MicrophoneHistoryGainTest` | 1 | Production pipeline writes exact stereo originals, normalizes 4803 source frames to 1601 ASR samples, preserves the partial gain tail and reloads quality/gain for equivalent replay. |
| `AudioDecoderTest` | 2 | Owned priming with unannounced float/stereo output; stereo archive conversion across partial channel-frame buffer boundaries. |
| `DialogAudioActionsTest` | 1 | Retention/compression metadata selects the same usable track as decoding before copying. |

The first focused run executed 102 tests and reproduced all nine new format/route
failures before their fixes. A later run reproduced the inspection-track mismatch.
The combined decoder tests passed after conflict integration; no pre-fix RED claim
is made for those combination checks or the source/gain integration checks.

Three existing fixture adaptations preserve assertions:

- `DialogAudioActionsTest`: use inspected imports/owned leases and export selections
  rather than removed APIs; retain unknown-size, byte preservation and suppressed
  cleanup-error assertions.
- `AudioPlaybackControllerTest`: wait for native **PREPARING**, not merely player
  construction, before invoking its fake prepared callback. The new IO step made
  early callbacks cause impossible uninitialized-player states. Pause remains
  before completion in the capture races, and all original speed/lease assertions
  remain. No production pause behavior was weakened.
- `DialogStorageFailureTest`: provide real preferences and an application coroutine
  scope, mock the new pre-copy inspection/archival producer, and await the separate
  export flag. The first full run's three failures were absent fixture flows/scope,
  not storage-message regressions. All three pass with their original error/source
  assertions intact.

The initial new rolling/gain test incorrectly attempted the legacy reader after
capture finalization; retained rolling input requires the part-format path. The
test now feeds the exact saved stereo PCM through the production decoder converter
and compares it with live pipeline gain output. No reader restriction was relaxed.
One intermediate compilation failure was a patch-application omission in the
inspection edit; corrected before verification.

## Commands and actual results

All commands ran at the root above with a 600000-ms timeout. No clean, global cache
change, native task exclusion, forced rerun or `kotlin.incremental=false` workaround
was needed. Parent-prepared native sources and source-built sherpa AAR were used.

Focused integration command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*audio.*Test" --tests "*AudioRecorderArchivalConfigurationTest" --tests "*CaptureFormatBudgetTest" --tests "*RecordingPipelineTest" --tests "*AudioDecoderTest" --tests "*DialogAudioActionsTest" --tests "*AudioPlaybackControllerTest" --tests "*OriginalMultipartProcessingTest" --tests "*RepresentationReplacementTest" --tests "*EncodingFailureRecoveryTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

Result: **423 passed, zero failures/skips**, 40.649 seconds test time; Gradle
successful in 45 seconds. The subsequent three storage-dialog cases passed in a
focused run after their fixture adaptation.

Final required command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

Result: **1090 passed, zero failures/skips**, 1m15.67s test time;
**BUILD SUCCESSFUL in 4m15s**, 104 tasks (59 executed / 45 up-to-date).
Both root CMake native targets configured/built, linked and packaged. Compiler,
deprecation and pinned native-source warnings remain; no warnings were suppressed.

Artifacts:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/test-results/testDebugUnitTest/`
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Output metadata confirms **io.github.lrq3000.utterlane**, version **2.1.0 / 210**,
minimum SDK **26**. These are standard-identity APKs, not `.dhome` builds and not
installed by this task. Neither known `$stable` failure nor intermittent Windows
deletion failure appeared in the final run. The earlier host-IO investigation is
still valid; this green run does not resolve its historical root cause.

## Changed paths in this integration pass

Relative to `app/src/main/java/io/github/lrq3000/utterlane/`, production edits are
the seven conflict files in the resolution table plus
`audio/AndroidCaptureRoute.kt`, `audio/MicrophoneCaptureConfiguration.kt`, and
`transcribe/DialogAudioActions.kt`. The resource edit is
`app/src/main/res/values/microphone_processing.xml`.

Relative to `app/src/test/java/io/github/lrq3000/utterlane/`, test edits are
`asr/AudioRecorderArchivalConfigurationTest.kt` (new),
`audio/AudioRoutingPlatform.kt`, `audio/ExplicitCaptureRouteTest.kt`,
`audio/MicrophoneCaptureConfigurationTest.kt`, `audio/MicrophoneHistoryGainTest.kt`,
`transcribe/AudioDecoderTest.kt`, `transcribe/AudioPlaybackControllerTest.kt`,
`transcribe/DialogAudioActionsTest.kt`, and `transcribe/DialogStorageFailureTest.kt`.
This report is the only new documentation edit in this pass. All other transferred
root source changes remain part of the parent's uncommitted archival work.

## Handoff checks

- `git ls-files -u`: empty after marking exactly the seven conflict files resolved.
- `git diff --check` and `git diff --cached --check`: passed.
- The four user `test_material/streaming_diarization_accuracy/test-2-speakers-french-3-turns*`
  files remain untracked, unstaged, and byte-identical to the initial root hashes.
- Parent should rebuild with `-PqaApplicationIdSuffix=.dhome` before replacing that
  isolated installation. Re-run combined native microphone/route/compression and
  playback/Markdown layout flows there. The default-build tests compile, but this
  task makes no new emulator, hardware-HFP, codec, provider or performance claim.
