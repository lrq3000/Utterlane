# Dialog Polish and Recovery Implementation Plan

> **For agentic workers:** Execute inline with executing-plans, one verified milestone at a time.

**Goal:** Make setup and history actions clearer and prevent ordinary dialog exit from silently losing recoverable work.

**Architecture:** Extend atomic file-backed history metadata with persistent recovery provenance. Add a shared exit policy to the retained dialog model, keeping interactive navigation separate from explicit Home retirement. Reuse existing error handlers for toast feedback.

**Tech Stack:** Kotlin, Compose Material 3, AndroidX ViewModel, coroutines, JUnit and Android instrumentation.

## Task 1: Wording, onboarding and feedback

- [x] In `onboarding/OnboardingSetupPages.kt`, move the accessibility card before the floating card.
- [x] In `settings/SettingsActivity.kt`, use `Icons.Default.AutoFixHigh`, a new dedicated replay label, no subtitle, and a stable `settings_onboarding` tag. Keep `EXTRA_REPLAY` behavior.
- [x] Update `res/values/strings.xml` labels and add specific success messages. Add a small `transcribe/ActionFeedback.kt` toast helper; invoke it after completed operations and from existing catches only. Sharing success follows chooser launch; export success follows completed IO.
- [x] Extend onboarding instrumentation to snapshot preferences before replay and compare after Back; check card ordering.
- [x] Run focused unit tests and both debug assemblies, review diff, commit the milestone.

## Task 2: Recovery provenance and policy

- [x] Add failing repository regressions using existing `TemporaryFolder` fixtures for recovery origin surviving completeRecovery/pin/reopen and ordinary temporary-result exclusion.
- [x] Add `recovered: Boolean = false` to audio/transcript metadata and working transcript provenance, serialize it with backward-compatible defaults, propagate through result metadata and saves. Recovery notifications/checkpoints mark actual recovered content; successful temporary ownership alone does not imply recovery.
- [x] Add repository handoff methods that retain nonexpired temporary recovery under enabled history without resetting existing reference times. Text publication is idempotent and guarded against discard.
- [x] Add a pure exit-loss policy and boundary tests: pinned, disabled, immediate, expired, forever, missing audio/text and independently retained siblings.
- [x] Run focused history tests, then commit the verified persistence/policy milestone.

## Task 3: Safe dialog exit and legends

- [x] Add a single interactive exit entry point to `transcribe/TranscriptionDialogModel.kt`; both Activity Back routes call it. Keep explicit Home retirement on its existing noninteractive disposition path.
- [x] Add `DialogExitConfirmation.kt` for Discard/Pin/Go back. Name threatened kinds; await both saves before close; failure leaves data/dialog available. Revalidate changed disposition and do not discard newly published work without confirmation.
- [x] Add bottom history legends and persistent recovery badge mapping in `history/HistoryScreen.kt` and `HistoryViewModel.kt`.
- [x] Add Android regression fixtures for retained recovery, disabled/immediate confirmation, both-pin, Go back, both Back routes, missing linked sources and saving errors. Run these plus existing dialog/history regressions on an isolated QA package.
- [x] Update user/lifecycle/privacy documentation and record checks in `docs/qa/polish-recovery.md`; run final unit tests and debug assembly, review/stage only intended files, commit.

## Commands and evidence

Use `gradlew.bat :app:testDebugUnitTest --tests "io.github.lrq3000.utterlane.history.*" --console=plain` for repository iterations, normal incremental builds only. Full final command: `gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest -PqaApplicationIdSuffix=.polishqa --console=plain`. Run focused `adb -s <selected-device> shell am instrument -w -e class <test-classes> io.github.lrq3000.utterlane.polishqa.test/androidx.test.runner.AndroidJUnitRunner` after installing both APKs. Record actual outcomes and limitations in the final report.
