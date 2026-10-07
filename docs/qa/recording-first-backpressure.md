# Recording-first transcription verification

- Worktree: `.worktrees/recording-first-backpressure`
- Branch: `feat/recording-first-backpressure`
- Base: `1237054`
- Date: 2026-10-07

## Reproduced before implementation

- JVM: history off created no audio spool; failed audio disappeared after history
  cleanup. Both new `RecordingRecoveryTest` cases failed on the original code.
- Android: `RecordingFirstAndroidTest` failed with **Capture was gated by model
  preparation** in the dedicated, model-free `.recordingfirst` QA application.
- JVM: repeated PCM callbacks rebuilt/published visual state before the requested
  refresh deadline. The corresponding `VisualRefreshTest` failed before throttling.

## Implemented behavior

- Capture and private disk writing run alongside model preparation/inference.
  Recognition failure leaves capture running until Stop. Cancellation drains
  already accepted writer input and preserves unfinished audio.
- RAM is bounded by the capture queue, one emergency overflow block, reader
  buffers and existing ASR windows. The block detecting writer overload is saved
  after the queued prefix instead of being silently rejected.
- Enabled diarization remains required. Stop drains all windows and final speaker
  lookahead; a deliberately delayed speaker finisher cannot be skipped.
- Private audio is spooled even with history off. Success deletes temporary audio
  after readers close; failures/cancellation/interruption survive cleanup and
  restarts until successful recovery or explicit deletion.
- History and live catch-up use one sequential reader handle per WAV part, with
  bounded reusable byte buffers. The writer also reuses its conversion buffer.
- Waveform energy is calculated once for new samples, accumulating fixed 100 ms
  audio buckets in a 64-point ring. Old published arrays stay immutable.
- Appearance exposes 1/2/5/10/20 Hz maximum visual refresh, default 10 Hz. File
  percentage/preview callbacks update latest-value state rather than launching
  a main-thread coroutine for every update. Input and committed text are retained.
- Unfinished-recording recovery is visible in Settings independently of notification
  permission, with retry, export, deletion and model-selection access. Privacy and
  user/developer documentation describe the approved retention exception.

## Final checks

- **275 JVM tests passed**, zero failures or skips (normal incremental full suite).
- **Debug APK and Android test APK built successfully**, ARM64 native packaging.
- **8 scoped Android tests passed** on `emulator-5554`, API 28, reported model
  `G576D`, with a separate `io.github.lrq3000.utterlane.recordingfirst` identity:
  - 3 recording-first tests: absent-model capture survival, Stop during blocked
    preparation plus delayed speaker finalization, and visible loading/failure
    state while recording with statistics hidden.
  - 3 existing capture-panel tests: controls, signal warnings, progress, statistics.
  - 2 real Settings-window tests: changing refresh to 1 Hz and reopening, and
    visible recovery with history disabled.
- UI screenshots of the frequency selector and recovery dialog were inspected.
  Generated screenshots are in the QA app's `files/onboarding-qa/` directory;
  local inspection copies are under `app/build/outputs/`.
- Review caught a new file-progress tick incorrectly reporting missing microphone
  frames. A regression test first failed, then passed after separating file-input
  publication from microphone-signal monitoring.
- An Android Settings test initially clicked a closing old activity window during
  reopening. Waiting for actual activity destruction and UI idle fixed the test
  lifecycle race; both Settings tests then passed in 10.886 seconds.
- `git diff --check` passed.

### Controlled presentation-work measurement

The deterministic test feeds **1,000 PCM callbacks over ten simulated seconds**
(100 callbacks/second). After the initial state, it observes:

| Selected maximum | Routine state publications |
| --- | ---: |
| 1 Hz | 10 |
| 10 Hz | 100 |
| 20 Hz | 200 |

All sample counts survive. This measures the presentation-work budget, not neural
inference acceleration or physical-phone latency. No new phone thermal benchmark,
ASR accuracy benchmark, or end-to-end speedup percentage is claimed. Speaker
lifecycle tests use controlled delayed outputs, not a replacement acoustic model.

## Commands

From this worktree (PowerShell; quote dotted Gradle properties):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.recordingfirst" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain -q
adb -s emulator-5554 install -r "app/build/outputs/apk/debug/app-debug.apk"
adb -s emulator-5554 install -r "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb -s emulator-5554 shell am instrument -w -e class io.github.lrq3000.utterlane.RecordingFirstAndroidTest,io.github.lrq3000.utterlane.CapturePanelAndroidTest io.github.lrq3000.utterlane.recordingfirst.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e onboardingTimeoutSeconds 10 -e class io.github.lrq3000.utterlane.RecordingSettingsAndroidTest io.github.lrq3000.utterlane.recordingfirst.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

The harness interrupted combined commands, so installations and instrumentation
were subsequently run individually. After a scoped test-APK build timed out, it
completed with explicit `JAVA_HOME=C:\Program Files\Java\jdk-21` and `--offline`;
no clean, forced rebuild, daemon shutdown or shared-cache deletion was used.

New English strings follow the repository policy of deferring translations until
release stabilization. This report records the initial implementation; the later
approved history/pin/dialog roadmap supersedes its temporary-retention policy.
The validated changes are now being committed in coherent local milestones under
the global standing commit authorization. No publication or main-worktree edits
are included.
