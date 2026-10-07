# Independent histories and shared transcription dialog

> Execute inline using executing-plans. The maintainer approved the complete
> design in conversation, including local milestone commits. No publication or
> merge is authorized.

## Final requirements (supersede the earlier recovery lifetime)

Implementation and focused verification are complete. See
[the QA record](../../qa/independent-histories.md) for executed checks and their
limits, and the branch's incremental commits for milestone boundaries.

- Explicit Cancel/Discard/temporary-dialog Close deletes temporary work. A crash
  preserves unfinished sessions; persist deletion intent so a crash during
  deletion cannot resurrect discarded input. Never delete a sender's source file.
- Audio and transcript histories have independent automatic-save switches and
  retention durations. Transcript history defaults off, duration 24 hours. Migrate
  existing No history to audio auto-save off, preserving normal saved durations.
- Manual saves are pinned forever. Outline/filled pin controls exist in both
  histories. Unpin resets the retention timestamp without changing creation time.
  Under Immediate, unpin holds the item until the next genuine user-facing launch;
  background jobs, rotation and returning from external pickers do not release it.
- Retained recovery audio expires normally unless pinned. Temporary crash recovery
  is distinct from saved history. Each completed re-transcription creates its own
  transcript entry; manual saves of the same attempt/source are idempotent.
- Prune on startup and system-scheduled intervals matching each duration, never
  inferred app close. Use expiry metadata and coalesced IO, not content scans or
  per-render/per-recording whole-history cleanup. Forever needs no expiration job.
- One overlay-style dialog handles sharing, audio/transcript history and recovery.
  It offers same/other-model re-transcription, audio Save (history/share/files),
  text copy/share/manual history saving, Close and ownership-correct Delete/Discard.
  Keep audio available through successful retries until temporary-dialog dismissal.
- Playback starts collapsed at Play; playing shows Pause/Stop and a seek slider;
  paused shows Resume/Stop; Stop/end collapses and resets. Support one timeline for
  multipart WAVs, paused/playing seeking, audio focus, stale callbacks and leases.
- Preserve capture independence, enabled diarization, bounded PCM/text memory,
  sequential IO and configurable presentation cadence from the initial milestones.

## Implementation milestones

1. **Storage policy and repositories**: shared retention index/mark, indexed due
   candidates, pin/launch holds, durable discard; extend RecordingHistory to owned
   encoded imports and add TranscriptHistory. Validate metadata restart, expiry,
   unpin, pinned manual dedup, leases and independent audio/text deletion.
2. **Settings and scheduled pruning**: independent preferences/migration; update
   JobScheduler and a user-launch coordinator; remove UI/completion bulk pruning.
   Validate default/migration and Immediate/Forever scheduling.
3. **Capture lifecycle integration**: distinguish explicit discard from owner
   cancellation; isolate recognition-only cancellation/close failures; save each
   completed live transcript when enabled. Port useful parallel-capture tests.
4. **Shared dialog and saving**: reusable operation owner, recording-specific
   recovery, direct model picker, repeatable attempts, private shared imports,
   three audio destinations and separate text history; preserve bounded previews.
5. **Playback and history UI**: shared leased player, global multipart timeline,
   play/pause/stop/seek UI and pin-enabled audio/text browsers.
6. **Integrated validation/documentation**: focused JVM tests throughout, one
   normal full suite/build at integration, scoped Android lifecycle/player/UI
   tests and existing cold native fixture when available. Update privacy/user
   docs and the QA report. Commit each checked milestone before the next.

## Verification commands

Use this worktree, JDK 21 and the isolated `.recordingfirst` application identity.
Keep output quiet and normal incremental execution; no clean or forced rebuilds.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*History*Test' --tests '*Retention*Test' "-PqaApplicationIdSuffix=.recordingfirst" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain -q --offline
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.recordingfirst" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain -q --offline
```

Install/run APKs in separate bounded calls. Include source tests and related docs
in local conventional commits with OpenCode / OpenAI gpt-6-astra attribution.
