# Transcription controls implementation plan

> **For agentic workers:** Use the existing queue-live worktree, bounded independent units and integrated verification.

**Goal:** Let people operate an individual transcription, find it from Home or History and hide its reader without losing control of its work.

**Architecture:** Keep job state with the queue, use the job ID across every surface, reuse the current source ownership and review mechanisms. Separate recording, playback, and transcription commands.

**Tech stack:** Kotlin, Android, Compose Material 3, coroutines, disk-backed queue journal.

**Spec:** `docs/superpowers/specs/2026-11-26-queue-controls-design.md`

## Global constraints

One active file recognizer; live microphone priority; offline operation. A notification, Activity teardown, or another completed job cannot consent to resume, delete or acknowledge. No destructive operation on an external original or sibling attempt.

## Review focus

Manual pause across completion of other files, live microphone use and cold start; cancel before first text or during finalization; transcript-only import with temporary staging; denial or pending decisions during background submission; compact or large-font screens and the paused-only route back into a job.

## Tasks

- [x] Queue engine: add journal-backed PAUSED/STOPPED states and ID-guarded pause, continue, stop and restart. Test scheduling after a pause, stop-to-partial and restart identity, stale callbacks and recovery.
- [x] Deletion: test transcript-only setup against the history deletion planner, separate retained audio from processing input, and share a confirmed job cancellation between detail and history. Verify cancel before first output and ownership of a shared source.
- [x] Reader: build processing controls into `TranscriptionProgressFooter`, secondary duration beside percentage, bottom background icon and persistent transcript typography. Exercise compact layout and playback/processing action labels.
- [x] Navigation: reuse history row components for an exact-job queue list, Home monitor and History actions. Verify visibility, paused-only reachability and identity routing.
- [x] Import: reuse `FileTranscriptionSetupActivity` staging and notification recovery, apply the independent switch to share and picker; test background transition without bypassing retention decisions.
- [x] Integrate: run focused JVM checks, full unit suite and debug build; exercise a two-file import, pause/continue, live dictation, deletion and Activity recreation on a controlled emulator. Record exact evidence.

## Acceptance evidence

The API 28 two-file journey exercised a real recognizer: at 344,064 processed samples the first file had 381 characters. It remained paused while its sibling completed and the foreground service detached. Explicit Continue from History finished the same job with 1,342 characters and its original prefix intact. Cancelling one job left both provider files unchanged. The independent font test checked the actionable accessibility nodes, measured transcript-only reflow, and verified the selection on reopening. These runs used the `.dhome` APK with SHA-256 `183ac48f414539b9927f7d51d022b15df456e103d10658c4c37ba3157f8b4795`.

Subsequent review added coverage for stale History deletion consent, saved-ID aliases, checkpointed sources during archival conversion, duplicate Restart taps, and blocked model switches. The final full JVM run passed all 1,180 tests in 153 suites, without skips; the standard debug APK assembled and verified with APK Signature Scheme v2 (SHA-256 `0dfd9bfc830e681e5a8acc152e71e9013139f8389c49e21db8959974a4c699c7`).

On the final `.dhome` build (`7a197b2f75f6aacfd7bbb19e0a6e2e97d1bfc948d79b5b49e5733a9c599d5ba1`), the API 28 live-capture fixture produced 503 characters from synthetic microphone PCM using offline recognition. The paused file remained unchanged across live completion and service shutdown. Restart created exactly one new attempt at zero processed samples on the same audio; Stop retained its 191-character partial and the first attempt's 381-character partial. Both readers, the completed live result and the original source checksum were verified. The test passed in 153.239 seconds. The QA packages were removed and the test guest shut down.
