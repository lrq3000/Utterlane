# Full-screen history verification — 2026-10-08

## Scope

- Worktree: `.worktrees/fullscreen-histories`; branch: `feat/fullscreen-histories`.
- Base: `b5e3074`, including the waveform correction and current diarization defaults.
- User-approved changes: full-screen Audio history / Transcript history, selected
  B-style entries, continuous loading, and an aesthetically consistent logo header.
- APK identity: **`io.github.lrq3000.utterlane`**, version 2.1.0 / code 210. The app
  and test APKs were installed with `-r` into the regular package on API-28 LDPlayer
  `emulator-5554`; no new suffixed application was installed for this feature.

## Evidence

- Two Android navigation tests first failed on the old dialog implementation:
  `History must launch a real full-screen Activity, not a dialog`.
- Nine new JVM index/repository cases cover stable tied-date ordering, timestamp
  extremes, forward/backward pages, exact-size completion, deleted cursor anchors,
  inserts between loads, refresh near boundaries, pin order, and leased deletion.
  The focused history/reader/recovery set passed **30 tests**.
- Final full JVM suite passed **300 tests**, zero failures or skips. App and
  instrumentation APK builds passed with the regular application ID.
- `HistoryScreenAndroidTest`: **4 tests passed** (57.283 seconds). Both Settings
  links launch a non-floating, full-width/full-height Activity with logo, correct
  title and Back navigation. Long-history cases create 160 pinned fixtures per
  history, browse to entry 131 beyond the cached paging window, unpin/open/return,
  recreate the Activity, then scroll back to the newest entry. The launch token
  stays unchanged. Each test deletes only its own fixtures and restores its theme.
- Restoration tests reproduced a whole-row jump or an off-screen anchor when
  recreation invalidated the cached pager. Retaining the page generation and
  formatting visible dates in the current UI locale fixed it. Position assertions
  tolerate at most two physical pixels for accessibility-coordinate rounding;
  the observed one-pixel rounding is distinct from the original 114+ pixel jump.
- A further **14 Android tests passed** (36.473 seconds): three pin/launch-grace,
  two row-interaction/deletion-boundary, three Settings/recovery, three waveform,
  and three recording-panel checks. Test hosts now explicitly open their own
  history Activity instead of relying on the removed recovery-dialog shortcut.
- Native screenshots of empty histories and populated light audio/dark transcript
  pages were inspected. Local copies are ignored under `app/build/outputs/`:
  `fullscreen-audio-history.png`, `fullscreen-transcript-history.png`,
  `fullscreen-audio-records-light.png`, `fullscreen-transcript-records-dark.png`.
  These are real native screen captures; no generated design artifact is tracked.

## Reproduction

Use JDK 21. Resolve the new Paging 3.3.6 dependencies online once; subsequent
incremental runs can use `--offline`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
adb -s emulator-5554 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e onboardingTimeoutSeconds 40 -e class io.github.lrq3000.utterlane.HistoryScreenAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e onboardingTimeoutSeconds 20 -e class io.github.lrq3000.utterlane.HistoryPinsAndroidTest,io.github.lrq3000.utterlane.HistoryListInteractionAndroidTest,io.github.lrq3000.utterlane.RecordingSettingsAndroidTest,io.github.lrq3000.utterlane.WaveformCadenceAndroidTest,io.github.lrq3000.utterlane.CapturePanelAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

## Environment and limits

- The initial fresh-worktree baseline timed out; a narrower incremental baseline
  succeeded. A later native compile ran out of disk space. The duplicate local
  source cache was replaced with a junction to this session's existing pinned
  cache, and the same Ninja target completed with `-j2`; normal Gradle builds then
  succeeded. No native build configuration or source was changed for this workaround.
- Streamed ADB installation stalled once; push installation (`--no-streaming`)
  succeeded. One instrumentation invocation had a malformed class-list argument
  and was corrected before the recorded test batches.
- The shared emulator crash buffer contained a system `cmd` SIGSEGV; the targeted
  application instrumentation completed successfully. No claim is made about
  unrelated emulator processes or physical-phone frame timing.
- New English strings follow the existing release-time translation policy. Loading
  errors retain loaded rows and offer Retry; this run did not inject storage faults.
  Visual preference remains subject to the user's review on their own device.
