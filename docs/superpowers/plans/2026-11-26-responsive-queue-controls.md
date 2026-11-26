# Responsive transcription controls Implementation Plan

> **For agentic workers:** Verify each slice before its commit. Keep the device tests and publication tied to the exact candidate, and check the course after each slice or at 30 minutes of active work, whichever comes first.

**Goal:** Make file setup fit small screens and let people immediately suspend and explicitly resume, restart or retire an individual offline transcription.

**Architecture:** Keep Compose changes separate from the journal and producer lifecycle. Reuse the native-call arbiter's cancellable await instead of terminating a shared worker; retire file input at a trustworthy checkpoint and keep manually stopped jobs out of the active monitor.

**Tech Stack:** Android, Kotlin, Compose Material 3, coroutines, Gradle/JUnit and AndroidJUnitRunner.

**Spec:** [`2026-11-26-responsive-queue-controls-design.md`](../specs/2026-11-26-responsive-queue-controls-design.md).

## Global constraints

No new recognizer or concurrent native turns. Preserve live microphone priority, committed text, retention consent, original audio, exact attempt identity and no automatic resume. Leave unrelated worktrees and tags untouched.

## Review focus

Long translations in a narrow/short dialog; API 28 names on independently actionable icons; cancellation during a native call and at EOF; buffered speaker/correction output; cold start and cleanup with a stopped-but-resumable source; release code independent of display version.

## Tasks

- [x] Stack options with both overflow axes in `FileTranscriptionSetupActivity`; add `FileTranscriptionSetupAndroidTest` instrumentation coverage without changing retention prompts. Setup commit: `55107d77`.
- [x] Reuse `DialogAction` for named processing icons and add playback-separation coverage to `TranscriptionProgressAndroidTest`. Icon commit: `6a198a99`.
- [x] Seal the last committed window, preserve the source and history route, and support explicit Continue or independent Restart after Pause and Stop. Cover EOF, previous-release checkpoint compatibility, late native replies and retention.
- [ ] Run the full Gradle suite and build the APKs. Exercise setup and reader UI, direct Stop→History→Continue and live dictation on the isolated Android guest when the coordinator is available.
- [ ] Check the intended base and outgoing commits, verify the single `2.3.3` release commit with the next Android code, and fast-forward `main` without publishing a tag or uploading to stores.

The JVM regressions cover the exact resumed PCM boundary and storage invariants. The Android instrumentation suite compiles, but executing the setup and reader scenarios is blocked until the shared LDPlayer allocator clears another guest’s unresolved shutdown.
