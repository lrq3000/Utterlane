# Parallel microphone capture verification — 2026-10-07

## Scope and reproduction

This change separates microphone capture and disk writing from model preparation
and inference. It applies through the shared MicrophoneSession/Factory used by the
microphone entry points. CORE_VALUES.md guides input preservation, bounded working
memory, truthful UI state, failure isolation, and explicit temporary-file cleanup.

The new `ParallelCaptureAndroidTest` was run against the original lifecycle before
the implementation. Its unavailable-model case failed with:

```text
java.lang.AssertionError: Model failure prevented microphone startup
Tests run: 1, Failures: 1
```

The same regression passes after the change: recording continues through the
failure until Stop, and the exact captured sample count is present in recovery.

## Results

- **269 JVM tests passed**, with zero failures/skips in the full Gradle report.
- **10 targeted Android tests passed** in the final instrumentation run.
- Debug app and instrumentation APKs were built, installed, and used on
  `emulator-5554` (reported device model `G576D`).
- A real AudioRecord test captured at least one second of PCM while the selected
  model was unavailable, then verified the saved and captured sample counts match.
- A cold native ternary Parakeet Redux test verified that capture started before
  model readiness, processed all **144,000 samples / 9 seconds** of the bundled
  public-domain excerpt, and deleted successful No-history temporary audio.
- The native transcript included: “There was nothing so very remarkable in that,
  nor did Alice think it so very much out of the way to hear the rabbit say to
  itself, Oh dear, oh dear, I shall be late!”
- Recovery UI checks covered the initial loading-failure message, direct model
  selection, returning to the same audio, failed retry without data loss, successful
  retry with another native model, and deletion when recovery closes.
- Native recording-panel checks covered simultaneous Listening/Loading status,
  enabled Stop during preparation/failure, and the existing statistics controls.
- The existing reset-before-dispatch regression also passed.

Pure JVM cases additionally cover gated preparation, inference failure, sample
ordering, Stop before startup, cancellation, writer failure, preservation of the
block reaching the queue limit, temporary cleanup, reader leases, and honoring
explicit retained-history deletion.

## Reproduce

Use an isolated application identity so QA does not replace another installation:

```text
gradlew.bat --console=plain --quiet "-PqaApplicationIdSuffix=.parallelcapture" testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class "io.github.lrq3000.utterlane.ParallelCaptureAndroidTest,io.github.lrq3000.utterlane.CapturePanelAndroidTest,io.github.lrq3000.utterlane.RecordingRecoveryAndroidTest,io.github.lrq3000.utterlane.NativeParallelCaptureAndroidTest,io.github.lrq3000.utterlane.ModelRecoveryAndroidTest#resetBeforeDispatchStillCompletesMicrophoneOwner" io.github.lrq3000.utterlane.parallelcapture.test/androidx.test.runner.AndroidJUnitRunner
```

The native tests require the catalog's hash-pinned
`parakeet-redux-0.6b-TQ1_Q8_0.gguf` fixture in
`/sdcard/Download/parakeet-qa/`. The production model manager verifies it before
loading. Speech comes from the already-bundled, attributed onboarding sample.

Some combined Gradle tool calls were interrupted at the harness boundary. The
completed reports/APKs were inspected instead of forcing rebuilds; focused build
commands and the final instrumentation run completed normally.

## Evidence boundaries

The native cold-load test supplies deterministic PCM rather than physical speech;
the separate real AudioRecord test verifies the capture API and frame preservation,
not acoustic fidelity. These runs do not establish phone performance benchmarks
or coverage of every Android version's background-activity restrictions. Recovery
also has a notification and Settings route when automatic activity launch is blocked.

With No history, failed audio remains private for the recovery flow, including
while viewing a successful retry, and is deleted when that screen closes. Retained
history continues to obey the user's retention/deletion choices. Abandoned
temporary audio is cleaned up at the next main app process start.
