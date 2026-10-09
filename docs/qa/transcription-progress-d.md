# Native transcription progress D verification

## Environment and intent

- Approved design: minimal bar/labels from progress concept A in concept C's
  reader footer, plus completion that expands reading space downward.
- Worktree `.worktrees/transcription-progress-native`, branch
  `feat/transcription-progress-d`, based on local main `9dc33a8` and the two
  approved mockup commits. User subsequently deferred squash merge and push.
- JDK 21; Android SDK 36; normal incremental Gradle, two workers, offline caches.
- LDPlayer instance 1, API 28, `emulator-5556`; isolated application identity
  `io.github.lrq3000.utterlane.progressd`, version 2.1.0 / code 210.
- Native source/AAR cache junctions are read-only inputs from existing local
  artifacts. Edits, Gradle outputs and installed QA data belong to this worktree
  and isolated package.

## Accounting evidence

The original bar represented decoding/reading position and did not display
percentage or ETA. New file accounting uses completed ownership samples and a
whole-source denominator. Imported metadata is marked approximate, discarded
when contradicted, and replaced by the exact accepted sample count at EOF.
CaptureMetrics exposes its already-existing smoothed seconds-per-sample rate;
the file display does not create another estimator or count down on clock ticks.

- Baseline CaptureMetrics tests passed.
- The new contract's red run had **10 expected failures out of 17 tests**, covering
  missing measurements, percentage and stage behavior. Those 17 then passed.
- Added coverage includes decoded-ahead input, unknown/incorrect totals, EMA
  changes, duplicate/overlapping ownership, zero input, cancellation/failure,
  unmeasured speaker finishing, terminal success and stable repeated snapshots.
- The finalization callback is between the last processed window and the native
  speaker drain. An additional regression showed that a callback could close the
  session and still enter the drain; rechecking lifecycle after callback now
  prevents that. Callback ordering is verified natively.
- An ordering test initially encountered the JVM's unmocked Android logging API.
  It was moved to the Android suite; production logging was preserved.

## Native UI and pipeline evidence

- The first percentage/ETA native test failed before the footer implementation:
  no `transcription_percentage` node existed, while the original top progress
  and transcript were present.
- **353 JVM tests passed**, zero failures/ignored, in the final full JVM run.
- Debug application and instrumentation APK builds passed.
- **35 Android tests passed in 84.231 seconds**:
  - 6 progress UI/session-boundary tests;
  - 5 existing dialog ownership/recovery/export tests;
  - 19 existing dialog D/history/control tests;
  - 4 playback tests;
  - 1 real native processing test through the dialog ViewModel.
- The UI tests verify default-visible percentage/ETA with advanced statistics
  disabled; footer below the document and above controls; unknown duration;
  interrupted text remaining readable; narrow light/dark layout; 160% font
  wrapping; and unchanged viewport top and scrollbar position upon completion.
- The real processing test uses 30 seconds of generated non-speech PCM and the
  existing checksum-verified local TQ1_Q8_0 model. It observes an intermediate
  measured percentage/ETA and requires 480,000 processed/total samples and 100%
  only at successful operation completion. This verifies real counter plumbing,
  not speech accuracy or quantitative ETA prediction error. No model download.

Local screenshots were inspected in `app/build/outputs/progress-qa/`:
`transcription-progress-d-light.png`, `transcription-progress-d-320-dark.png`,
`transcription-progress-d-complete.png`, and
`transcription-progress-d-large-font.png`. The new footer fits at 160% font scale;
the existing header title and playback timestamp clip in that extreme narrow
configuration. Their layout is outside this progress change; the screenshot
does not establish that the entire existing dialog is large-font-perfect.

## Commands

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.progressd" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -e onboardingTimeoutSeconds 15 -e class io.github.lrq3000.utterlane.TranscriptionProgressAndroidTest,io.github.lrq3000.utterlane.TranscriptionDialogAndroidTest,io.github.lrq3000.utterlane.TranscriptionDialogDAndroidTest,io.github.lrq3000.utterlane.AudioPlaybackAndroidTest,io.github.lrq3000.utterlane.TranscriptionProgressPipelineAndroidTest io.github.lrq3000.utterlane.progressd.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

## Timing and delivery limits

ETA estimates measured window processing; it is approximate, not a promised
deadline. When speaker EOF finishing is unmeasured, the numeric label explicitly
says **+ finishing**. During the finishing/persistence stages it reports the
stage without a fabricated numeric ETA. New strings use English fallback under
the release-time translation policy.

No main-branch merge or remote push is part of this delivery: the user deferred
them to avoid interfering with another agent's ongoing work.
