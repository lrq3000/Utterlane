# Dialog polish and recovery verification — 2026-10-09

Branch: `feat/polish-recovery`; worktree: `.worktrees/polish-recovery`.
Android target: `emulator-5584` (Android emulator with arm64 native bridge).
Isolated QA package: `io.github.lrq3000.utterlane.polishqa`.

## Reproduction and checks

- Before changing exit behavior, `ordinaryClosePublishesRecoveredContentBeforeReleasingWorkingCopies`
  failed with **Ordinary close must retain recovered audio**. It now passes through
  the same interactive exit path as both Back controls.
- Recovery provenance tests initially failed because audio and transcript metadata
  had no persisted recovered-origin property. They now verify persistence, row
  projection, acknowledgement, pinning, retry, and normal temporary-result exclusion.
- **486 JVM tests passed**, zero failures/ignored tests. Debug and instrumentation
  APK assembly passed.
- **54 distinct Android tests passed across focused runs:**
  - `TranscriptionExitAndroidTest` (11): retained recovery; disabled/immediate
    policies; all exit choices; both kinds pinned; missing source audio; normal
    entries remaining normal; expiry while viewing; changed confirmation scope;
    failed pin with real filesystem obstruction and successful retry; both Back
    routes; actual accessibility toast events for pin, copy, history save, text
    share, audio share, audio export, and failed export.
  - `TranscriptionDialogDAndroidTest` (19), `TranscriptionDialogAndroidTest` (5):
    existing linked deletion, provenance, independent result versions and layouts.
  - `HistoryPresentationAndroidTest` (3), `HistoryPinsAndroidTest` (3),
    `HistoryListInteractionAndroidTest` (2), `HistoryNavigationAndroidTest` (5):
    recovery badges, bottom legends, passive row indicators, navigation and unpin.
  - `HomeDetailOwnershipAndroidTest` (1): borrowed Home details keep the underlying
    result on both Back paths; explicit Reset still retires that workspace.
  - `OnboardingAndroidTest` (5): actual Settings replay action, replay/Back preserving
    configuration, existing completion and theme behavior.

## Findings during validation

- The unchanged `EmptyAudioImportTest` initially failed when grouped on Windows
  (leftover rejected-import directory), but passed alone. The user approved
  continuing. Later focused and full suites passed without changing that code.
- A Kotlin incremental compiler transaction encountered a Windows temporary-directory
  cleanup failure. One non-incremental in-process compile recovered the build;
  subsequent builds used normal incremental work with the in-process compiler.
- New view leases initially delayed physical removal after explicit text deletion
  while its independent audio stayed open. Repository refresh now releases deleted
  view identities; the existing scoped-deletion regression passes.
- Old history tests forbade pin explanations anywhere on the screen. Assertions
  now scope that restriction to each entry and allow the requested bottom legend.
- The existing narrow-header probe mixed accessibility geometry across window
  resize frames. It now checks one repeated, stable bounds snapshot, preserving
  its target-size and non-overlap assertions. The focused test passed.
- Initial emulator UiAutomation-registration attempts failed before application
  assertions. Completed later runs obtained working automation; the new UI tests
  prepare it before entering their suspending test bodies.

## Reproducible commands

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest "-PqaApplicationIdSuffix=.polishqa" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain -q
adb -s emulator-5584 install -r "app/build/outputs/apk/debug/app-debug.apk"
adb -s emulator-5584 install -r "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb -s emulator-5584 shell am instrument -w -e class io.github.lrq3000.utterlane.TranscriptionExitAndroidTest,io.github.lrq3000.utterlane.TranscriptionDialogDAndroidTest,io.github.lrq3000.utterlane.TranscriptionDialogAndroidTest,io.github.lrq3000.utterlane.HistoryPresentationAndroidTest,io.github.lrq3000.utterlane.HistoryPinsAndroidTest,io.github.lrq3000.utterlane.HomeDetailOwnershipAndroidTest,io.github.lrq3000.utterlane.OnboardingAndroidTest io.github.lrq3000.utterlane.polishqa.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class io.github.lrq3000.utterlane.HistoryListInteractionAndroidTest,io.github.lrq3000.utterlane.HistoryNavigationAndroidTest io.github.lrq3000.utterlane.polishqa.test/androidx.test.runner.AndroidJUnitRunner
```

The UI tests save screenshots in the QA app's external `files/onboarding-qa/`
directory: `polish-audio-history-legend.png`, `polish-transcript-history-legend.png`,
and `polish-exit-loss-warning.png`. Audio/text legends and exit choices were
visually inspected. New English resources use the project's pre-release
translation-batching workflow; this task does not publish a release.
