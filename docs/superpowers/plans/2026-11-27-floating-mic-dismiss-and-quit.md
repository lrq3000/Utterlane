# Floating Mic Dismissal and Graceful Quit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide the floating mic without losing work, show consistent recording/save feedback, and give active work an independent recoverable Quit action.

**Architecture:** Adapt AudioRecorder’s drop affordance and animation in the existing floating service. Transfer live speech through the shared `DictationSession` / `DictationQueueHandoff`; extend the existing queue and archive owners for coordinated quit, not an always-running service.

**Tech Stack:** Kotlin, Android View/WindowManager, Compose, coroutines, DataStore, Robolectric, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-11-27-floating-mic-dismiss-and-quit-design.md`

## Global Constraints

- Keep Utterlane’s existing floating mic, drag and pinch gestures, shared dictation panel, and durable processing owners.
- Do not force-stop the package or kill the app process; no new idle notification or resident service.
- No dependency, platform floor, release version, or persistent overlay-preference change.

## Review Focus

- A cancelled drag or second finger over the × must not hide the mic; test gesture cancellation and pointer changes.
- Hardware closure and queue ownership can lag the drop; test that service teardown is not used as capture cancellation.
- A new take or configuration update can interrupt save feedback; test success gating and a stale animation callback.
- A task may arrive while Quit is checkpointing; test the dispatch fence, including a later queued row.
- A blocked archive provider or absent notifications must not force a destructive quit; verify recoverable archival pause and the existing queue screen fallback.

---

### Task 1: Temporarily hide and hand off dictation

**Files:** `FloatingControlTouchListener.kt`, `OverlayDismissTarget.kt`, `FloatingMicService.kt`, `StreamingDictationOverlay.kt`, `DictationSession.kt`, focused floating and handoff tests and documentation.

**Interfaces:** `FloatingControlTouchListener` accepts target visibility, motion and release callbacks; `StreamingDictationOverlay.stopInBackground(queued: () -> Unit)` uses `DictationSession.stopInBackground(accepted: () -> Unit)` with the existing ordinary completion/recovery path as fallback.

- [x] Write RED tests that release the actual floating control on the red target, cancel an overlapping drag and confirm it stays attached, and check that accepted background work never calls cancel on its producer.
- [x] Run `.\gradlew.bat :app:testDebugUnitTest --offline --tests "*FloatingMicServiceTest" --tests "*DictationQueueHandoffTest"`; expect missing target/dismissal behavior.
- [x] Adapt the donor’s non-intercepting bottom target, physical circle hit testing and haptic feedback. Use the existing overlay bounds and queue handoff; do not persist coordinates of a successful drop.
- [x] Rerun the same focused Gradle command and verify green, then commit `feat(floating): hide the mic without interrupting dictation`.

### Task 2: Recording gradient and rainbow save feedback

**Files:** `FloatingMicService.kt`, `StreamingDictationOverlay.kt`, `FloatingMicAppearance.kt`, focused floating visual tests, documentation.

**Interfaces:** An overlay completion callback takes the confirmed outcome; `FloatingMicAppearance` owns button rendering and animation invalidation. Only explicit tap-stop arms success feedback.

- [x] Write RED tests that render a gradient rather than an inner circle, verify purple–blue idle and red recording fills with the same glyph, and verify successful tap feedback returns to idle without stale callbacks changing a new take.
- [x] Run the focused `*FloatingMicServiceTest`; expect failed appearance assertions.
- [x] Reuse the current theme and shape; adapt AudioRecorder’s hue/desaturation calculation to an idle gradient, preserving animation scale settings and stopping feedback on a new take or removal.
- [x] Rerun the tests and `.\gradlew.bat assembleDebug --offline`, then commit `feat(floating): add recording gradient and rainbow save feedback`.

### Task 3: Stop all jobs and quit

**Files:** queue journal and runner, archival coordinator and journal, microphone admission, quit coordinator, active notification builders, `HomeQueueMonitor.kt`, tests and user guide.

**Interfaces:** A single app-owned quit command fences new work, awaits live capture recovery and existing queue/archival stop boundaries, and removes only app-owned tasks. Notification actions and the in-app action call that same command.

- [x] Write RED tests for active and waiting queue rows, durable archival pause, stop/close ordering, no publication or automatic restart during quit, and absence of an idle status notification.
- [x] Run `*QueueJournalTest`, focused archival tests and the quit coordinator test; expect missing quit or pause behavior.
- [x] Reuse queue pause checkpoints and archival pause before producer teardown, add the action to existing notifications and the queue screen, and retain completed notices and interrupted input.
- [x] Rerun focused tests and `assembleDebug`, review the full candidate, and commit `feat(queue): stop active work safely and quit`.
- [x] Verify `.\gradlew.bat :app:testDebugUnitTest assembleDebug --offline` and exercise the real drag/notification path on an approved isolated emulator if available.
