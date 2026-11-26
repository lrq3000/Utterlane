# Model Downloads and Keyboard Dictation Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep model setup useful on unreliable networks, let keyboard users reach Settings without accidental loss, seed Utterlane’s spelling, and verify existing SCO readiness.

**Architecture:** Reuse the app’s model store, native recording panel, and durable transcription queue. Keep four independently reviewable changes with focused tests and local commits.

**Tech Stack:** Android Kotlin, Compose and native views, OkHttp, coroutines, JUnit/Robolectric.

**Spec:** `docs/superpowers/specs/2026-11-26-download-and-dictation-reliability-design.md`

## Global Constraints

- No new permissions, dependencies, release, push, or merge. Work stays in the project-local feature worktree. No prefilled-dictionary migration that changes an existing file.
- Never load incomplete model artifacts, trust a returned range blindly, resume an explicitly canceled operation automatically, or discard user input merely because Settings was tapped.
- Verify milestones individually and run `.\gradlew.bat --offline :app:testDebugUnitTest assembleDebug --console=plain --max-workers=2` before claiming branch-wide success.

## Review Focus

- Resume must remain safe if a CDN returns an unexpected range or a full representation.
- Earlier completed bundle files must not be fetched again after a later file fails.
- Screen state returns to normal after failure/cancel/finish and local import does not keep it awake.
- Transcription finishing before queue publication, or capture ending before discard confirmation, cannot orphan or discard the completed result.
- SCO already-ready transport cannot bypass ownership, mode, or actual microphone checks.

### Task 1: Resumable model artifacts

**Files:** `asr/ModelManager.kt`, a private downloader helper in `asr/`, and `app/src/test/java/io/github/lrq3000/utterlane/asr/ModelResumeTest.kt`.

- [x] Add injected OkHttp fixtures for a broken stream followed by a `206`, valid full `200` replacing a prefix, malformed range, successful prior artifact reuse, and explicit cancellation. Assert bytes, offsets, and absence of a published incomplete model.
- [x] Run `:app:testDebugUnitTest --tests '*.ModelResumeTest'`; expect the resume and reuse assertions to fail.
- [x] Implement per-model private persistent staging, range validation, integrity validation, safe replacement and owned cancellation cleanup. Do not redirect import through range handling.
- [x] Rerun `:app:testDebugUnitTest --tests '*.ModelResumeTest' --tests '*.Download*Test'`; expect pass. Commit the change and tests.

### Task 2: Screen on while downloads are visible

**Files:** `onboarding/OnboardingActivity.kt`, `settings/SettingsActivity.kt`, and a focused view/window test.

- [x] Test a real window across inactive → speech download → error and separately a speaker download and import.
- [x] Bind `FLAG_KEEP_SCREEN_ON` to active network downloads in both windows, keeping onboarding voice-trial behavior and not holding the flag for idle/error/import.
- [x] Run the focused test and an APK build. Commit separately.

### Task 3: Keyboard Settings and queue handoff

**Files:** `ui/RecordingPanel.kt`, `ui/RecordingOverlay.kt`, `ime/VoiceInputMethodService.kt`, `service/VoiceInputActivity.kt`, plus a focused shared handoff owner and tests.

- [x] Test inline action state, including dismissal, stale destructive confirmation, and processing finishing while queue publication is still pending. Test the exact queue result with original audio and the existing transcript, with no duplicate producer.
- [x] Route both keyboard entry points through one phase-aware gear UI and one app-scoped handoff protocol. Reuse the queue’s `QueueTranscriptTarget`, `captureEnded`, and `completeCapture` contracts. Do not transfer the microphone to Settings. Only suppress editor delivery once the background handoff is accepted. If submission cannot be saved, leave the current UI and ordinary recovery path intact.
- [x] Run focused tests and the keyboard/queue checks possible on a controlled target. Commit with the user-facing strings and tests.

### Task 4: Dictionary and upstream SCO regression

**Files:** `asr/DictionaryManager.kt`, `app/src/test/java/io/github/lrq3000/utterlane/asr/DictionaryDefaultsTest.kt`, `audio/ExplicitCaptureRouteTest.kt`, and its platform fixture.

- [x] Prove the default normalizes standalone casing only, a removed rule stays removed after reload, and a saved custom dictionary wins. Add the default and run the focused tests. Commit.
- [x] Inject the existing SCO state as a return-only registration snapshot on legacy and modern Android. Advance past the connection deadline without emitting CONNECTED; assert selected input and one acquired/released SCO request. Cover denied ownership. If the test passes unchanged, commit regression coverage only; otherwise make the smallest adaptation.

## Execution record

- `7c45a14c`: four missing resume behaviors reproduced; all 13 model resume/options/cancellation tests then passed. `50260a91`: two Android window/policy tests passed; not yet verified on a running device.
- `dc4002e3`: first-run dictionary casing failed before the change; all three dictionary default tests and streaming correction tests passed afterward. `28b47722`: sticky-only SCO route and ownership test pass on SDK 28, 31 and 36; no production Bluetooth change. Initial fixture paused reads for six seconds and triggered the independent, expected audio-stall safeguard; the corrected fixture continuously delivers PCM past the activation timeout without delivering a second SCO broadcast.
- Independent review found five rollout blockers: stale destructive confirmation, queue registration followed by a later side-effect error, premature deletion of text during clipboard/recovery, a late IME navigation callback affecting a newer session, and an unsigned suffix with no matching validator. The implementation fences capture EOF, treats journal publication as ownership, preserves the clipboard source through the read, makes unclaimed text discoverable and expirable without exposing queue paths, pins navigation to one attempt, and requires a matching strong validator for unsigned files. `98ee1a37` also releases the Settings window flag when leaving the visible download page.
- Ruling: The first Android 9 overlay test used accessibility window discovery, which omitted the application overlay even though its screenshot showed the gear. The fixture now checks and drives the attached native views within the test process, without changing app window focus. This establishes the installed app flow, not how every third-party keyboard or Android version exposes that window to accessibility.
- The initial combined QA-variant JVM run reported three cleanup failures in unchanged Windows history fixtures (`EncodingFailureRecoveryTest.failedEofWatermarkKeepsAll19200FramesThroughFallbackPublicationAndRestart`, `ManagedHistoryTest.archival journal failure cannot undo a complete text history publication`, and `ProcessingObligationTest.repeatedUnsuccessfulRetriesTransferOneObligationAndIgnoreOldCallbacks`). All new targeted tests passed in that run. Keep the canonical variant check separate from the isolated Android test package.
- The Android 9 overlay journey passed: dismissing the first warning did not stop capture; confirming discard deleted that recording with automatic history enabled; the next take finished with one queue row, a COMPLETE transcript matching its original audio identity, and the temporary audio still available for review. Both Android download cancellation and integrity cases also passed. No physical Bluetooth hardware was used.
- Final canonical debug APK and 93 focused JVM checks passed. A new negative test reproduced an unresolved handoff when both journal registration and journal lookup failed; the fixed version returns ownership to foreground delivery and passed the regression. The broader canonical run reported `ImportedAudioCopyTest.providerCloseFailureReleasesTheAlreadyPublishedLeaseForBothRetentionChoices`; that test also failed in a focused rerun with `leases=false` and an empty directory still visible. The four unchanged test classes passed a focused unmodified-base run. No changes were made to the unrelated Windows history deletion logic.
- Pre-merge review found that an ordinary IME take could retain temporary audio after its text was copied to the clipboard. `StreamingTextTargetTest` exercises the real delivery, private history, and text recovery paths in Robolectric: the clipboard case reproduced the retention bug, oversized text preserves audio for recovery, and an empty, preserved result must not signal delivery. Accepted queue handoffs do not run foreground delivery cleanup.
