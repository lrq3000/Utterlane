# Transcription progress D implementation plan

> For agentic workers: use the executing-plans skill for inline execution. The
> user approved D and implementation; merge/push were initially deferred while
> another agent worked on main, then authorized after final verification.

**Goal:** Implement the approved minimal bottom progress dock, preserving the
reader's top and scroll position while showing useful percentage and ETA.

**Architecture:** Keep the disk-backed reader and retained ViewModel. A small,
pure file-progress object combines completed PCM ownership with the full source
duration and the existing measured processing rate. Compose renders that state
in the reader's footer; native recognition stages and persistence determine when
completion is real. No additional inference or transcript scans are required.

**Tech stack:** Kotlin, Compose Material 3, coroutines/StateFlow, JUnit 4 and
Android instrumentation. Existing source-built native dependencies are read from
local caches; all build outputs stay in this worktree.

## Approved presentation and accuracy contract

- Use C's bottom placement and A's plain background/thin bar/compact labels.
- Progress remains outside the scrolling text and is visible without statistics.
- Complete replaces the bar/timing row with a compact confirmation below the
  reader. Its top stays fixed; extra reading space appears below.
- Use processed audio, not decoded/read input, for percentage. Imported duration
  metadata is approximate and must be marked as such. Unknown/contradicted
  duration means indeterminate progress until EOF supplies the exact sample count.
- Expose the existing smoothed seconds-per-sample measurement from CaptureMetrics
  rather than maintain a second estimator. No measured rate means estimating.
- Sample throughput estimates remaining window processing. When an optional
  speaker finisher is still unmeasured, explicitly qualify the numeric estimate
  as audio processing plus finishing. At the finisher, show its actual stage and
  unknown ETA. Never advertise a speech-only time as guaranteed full completion.
- Do not show 100% until final text and requested persistence have succeeded.
- Preserve recovery/error details, optional diagnostics, history and controls.

## Milestone 1 — Measurement contract

- [x] Extend `asr/CaptureSnapshot` with the existing measured processing rate,
  published on the same cadence. Test that it is available during file input and
  remains unavailable before a timed completed window.
- [x] Add `transcribe/FileTranscriptionProgress.kt`: source total/uncertainty,
  exact EOF accounting, explicit preparing/transcribing/finalizing/saving/terminal
  stages, and an immutable snapshot derived from CaptureSnapshot. Methods:
  `start(hasSpeakerFinalization)`, `inputEnded(samples)`, `finalizing(speakers)`,
  `saving()`, `complete()`, `fail(cancelled)`, `snapshot(capture)`.
- [x] Add focused JVM tests for decoded-ahead input, unknown/incorrect metadata,
  overlapping/repeated processed watermarks, EMA adaptation, zero-length input,
  finalization, errors, cancellation, and no clock-only countdown.
- [x] Run the failing tests before implementation; implement and rerun. Commit
  the working measurement contract and its tests together.

Evidence: baseline CaptureMetrics tests passed. The new contract's red run had
10 expected failures out of 17 tests (missing rate/progress/stage behavior).
After implementation, the same focused 17-test command passed.

## Milestone 2 — Native integration

- [x] Wire `TranscriptionDialogModel` to the source total and measured snapshots;
  remove its input-read percentage. Signal exact EOF, finalization, persistence,
  success and failure. Publish final state even between presentation ticks.
- [x] Give `TranscriptionSession.finish` an optional callback after final ASR
  windows and before the speaker/text finisher. Expose whether the session has a
  speaker finisher; existing callers retain default behavior.
- [x] Add `TranscriptionProgressFooter.kt`, English fallback strings, and a footer
  slot in `TranscriptReader`. Put transient status/diagnostics/errors below the
  reader. Retain the reader's lazy-list identity and existing action controls.
- [x] Add native tests that exercise the real dialog at narrow/light/dark/large
  font configurations, default-visible percentage and ETA, unknown totals,
  interrupted partial text, and the finalization-to-complete geometry/scroll
  transition. Use existing instrumentation patterns and isolated `.progressd` ID.
- [x] Build and run focused native tests plus dialog/history/playback regressions.
  Inspect native screenshots; record actual results and commit the integration.

Evidence and limitations are recorded in `docs/qa/transcription-progress-d.md`.
The native percentage-node regression failed before integration. Final validation
passed 353 JVM tests and 35 Android tests, including actual native processing.

## Commands and completion

Run from `.worktrees/transcription-progress-native`, JDK 21 and configured SDK:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*CaptureMetricsTest' --tests '*FileTranscriptionProgressTest' "-PqaApplicationIdSuffix=.progressd" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.progressd" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -e onboardingTimeoutSeconds 20 -e class io.github.lrq3000.utterlane.TranscriptionProgressAndroidTest io.github.lrq3000.utterlane.progressd.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: focused tests fail on missing behavior first, then pass; final builds
and regression checks pass. Inspect the diff, commit each coherent milestone,
and report hashes/QA evidence. The user subsequently authorized squash merge onto
the latest main and a normal push. Inspect current main before integration and
preserve unrelated work. See the QA note for the reproduced EOF anchor correction.
