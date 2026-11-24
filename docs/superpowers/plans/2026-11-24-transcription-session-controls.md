# Transcription Session Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Choose labels for each file, change labels during live capture, and identify interrupted transcript versions on reopening.

**Architecture:** A shared setup activity passes a typed file-only choice to the existing file recognition owner. The existing streaming session handles live policy changes at owned windows. The existing working sidecar and history metadata carry durable result completion.

**Tech Stack:** Kotlin, Compose Material 3, Android activity results/intents, JUnit and Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-11-24-transcription-session-controls-design.md`

## Global Constraints

- No additional per-file options without the maintainer's approval; microphone entry points remain immediate.
- Do not replay old audio or rewrite committed text for live changes.
- Respect URI ownership, history retention, explicit deletion, and independent audio/transcript lifetimes.
- No periodic status/progress persistence; legacy completion remains unknown.

## Review Focus

- Shared read-only URI reaches the existing import with the selected option; Back does not start work.
- A second file starts with labels off even if the previous file or global live default used labels.
- Toggle while inference is queued or speaker output has a tail; no lost words or label leakage into plain text.
- Native speaker state starts at a nonzero absolute sample after re-enabling; ownership remains disjoint.
- A capture or recognition failure and retry cannot make the wrong transcript complete.

## Steps

- [x] File-only setup activity and explicit options.
- [x] Per-result durable completion and history presentation.
- [x] Live speaker toggle and pipeline transitions; JVM and APK checks.
- [ ] Final Android journey verification and independent review.

### Task 1: File-only setup activity

**Files and interfaces:** Add `transcribe/FileTranscriptionSetupActivity.kt` and `FileTranscriptionOptions.kt`; modify `AndroidManifest.xml`, `home/HomeActivity.kt`, `home/HomeController.kt`, `transcribe/TranscribeActivity.kt`, `transcribe/TranscriptionDialogModel.kt`, `asr/RecognizerManager.kt`, and default strings. `FileTranscriptionOptions(speakerLabels: Boolean = false)` is the explicit request payload; `DialogInput.fileOptions` distinguishes old/internal requests that inherit the live setting.

- [ ] Write a deterministic contract test for default-off and explicit on/off against the global default; cover Share/Open/picker and cancellation with Android activity tests.
- [ ] Run focused tests to establish the baseline failure for the new behavior.
- [ ] Add the setup activity and route its positive action to the existing result owner, preserving read grants and keeping internal/recovery entry points separate.
- [ ] Run focused tests and `./gradlew assembleDebug` after manifest/resources; commit checked milestone.

### Task 2: Completion

**Files and interfaces:** Add `asr/TranscriptCompletion.kt`; carry `completion: TranscriptCompletion` through `TranscriptSource`, `TranscriptStore`, `TranscriptHistory`, `TranscriptMetadata`, microphone/file producers, and Home/history/dialog presentation. `TranscriptStore.markComplete()` commits the producer's terminal success; history restores the exact saved result.

- [ ] Add tests using real temp files: reopen before EOF is `INCOMPLETE`, after successful finalize is `COMPLETE`, legacy is `UNKNOWN`; saving partial result and retrying on the same audio leaves the old saved version partial.
- [ ] Run to verify the new completion assertions fail before implementation.
- [ ] Wire both producer boundaries and persist through manual/automatic saving and Activity/Home restoration; show partial only for inactive incomplete results.
- [ ] Run focused history, session lifecycle and capture/recovery tests; commit checked milestone.

### Task 3: Live labeling

**Files and interfaces:** Update `asr/TranscriptionSession.kt`, `RecognizerManager.kt`, `MicrophoneSession.kt`, worker speaker handling and `DiarizedWindowProcessor.kt`, `home/HomeController.kt` and `HomeScreen.kt`, plus `ui/RecordingPanel.kt`, `RecordingOverlay.kt` and their owners. Use an operation-owned request, read by the inference pipeline at owned window boundaries, and expose a callback from waveform surfaces to set it.

- [ ] Add deterministic tests with distinct words per window for Off → On → Off → On, pending speaker text drain, and a nonzero window origin, then verify red.
- [ ] Implement window-bound activation, drain and reset without stopping capture, with a warning and plain fallback when speaker resources are unavailable.
- [ ] Run focused windows/session tests, then `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`; exercise shared/picked file, waveform Off/On/Off, and partial reopen on a controlled Android target where available.
- [ ] Commit the verified milestone and review the exact branch.
