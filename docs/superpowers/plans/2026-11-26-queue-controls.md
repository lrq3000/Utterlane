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

- [ ] Queue engine: add journal-backed PAUSED/STOPPED states and ID-guarded pause, continue, stop and restart. Test scheduling after a pause, stop-to-partial and restart identity, stale callbacks and recovery.
- [ ] Deletion: test transcript-only setup against the history deletion planner, separate retained audio from processing input, and share a confirmed job cancellation between detail and history. Verify cancel before first output and ownership of a shared source.
- [ ] Reader: build processing controls into `TranscriptionProgressFooter`, secondary duration beside percentage, bottom background icon and persistent transcript typography. Exercise compact layout and playback/processing action labels.
- [ ] Navigation: reuse history row components for an exact-job queue list, Home monitor and History actions. Verify visibility, paused-only reachability and identity routing.
- [ ] Import: reuse `FileTranscriptionSetupActivity` staging and notification recovery, apply the independent switch to share and picker; test background transition without bypassing retention decisions.
- [ ] Integrate: run focused JVM checks, full unit suite and debug build; exercise a two-file import, pause/continue, live priority, deletion and rotation on a controlled emulator when available. Record exact evidence and unresolved checks.
