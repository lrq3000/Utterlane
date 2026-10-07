# Recording-first transcription and bounded presentation

Approved in conversation on 2026-10-07, including recovery when history is off.

## Required behavior

- Start the microphone after minimal private-storage/settings setup; prepare the
  model concurrently. Show recording and model preparation as independent facts.
- Persist PCM once in app-private files, independent of recognition speed or
  completed-history retention. Bound the capture handoff and reader memory.
- Continue capture after recognition/preparation failure. Stop drains saved audio
  through recognition and enabled speaker labeling; Cancel preserves unfinished
  audio. A real capture/storage failure reports the limitation and retains the
  successfully saved prefix.
- Speaker labeling, once enabled, remains required. Keep ordered per-window
  recognition/diarization and final lookahead draining. Never automatically turn
  it off, omit audio, or replay recognized windows as an optimization.
- History off deletes successful temporary recordings. Failed/interrupted sessions
  remain visibly available for retry, export or explicit deletion until resolved,
  including across application restarts and periodic history cleanup.
- Reuse waveform energy history in fixed audio-time buckets and a fixed-size ring.
  Publish safe snapshots only at the selected maximum refresh frequency. Default
  10 Hz; choices 1, 2, 5, 10, 20 Hz. Terminal/error/control states are immediate.
- Coalesce obsolete percentage/preview notifications, never audio or text deltas.
- Use session-owned WAV readers and bounded reusable conversion buffers.

## Boundaries

`RecordingHistory` owns private audio, retention, recovery metadata and read leases.
An incremental capture pipeline owns producer/writer/consumer completion and
failure isolation; `MicrophoneSession` owns Android callbacks, metrics and model
session ownership. A reader follows a monotonically published saved-sample offset.
Inference never gates the writer. Inference exceptions are recorded and allow the
capture/writer to finish; cancellation ends capture and preserves unfinished work.

Successful recovery follows the current retention policy; completed text is saved
before temporary audio may be deleted. An unfinished/recovery flag is persisted
before capture and cleared only after full transcription success. Existing
historical recordings retain their existing expiration behavior.

## Presentation

Audio-time waveform buckets are independent of callback sizes and rendering rate.
Capture counts and signal detection remain precise; only derived snapshots are
rate-limited. Ring storage is private and never mutated through a published array.
Model preparation and failure are visible even when advanced statistics are off.
Recovery is discoverable from Settings and completion feedback, not dependent on
notification permission.

## Evidence and verification

Reproduce startup gating and queue rejection using deterministic blocked preparation
and slow-consumer tests. Verify exact PCM ordering through disk catch-up, completion
after Stop, inference-failure capture survival, cancellation, restarts, deletion
leases, history-off successful cleanup, and speaker tail finalization. Verify
refresh bounds, fixed-duration waveform history and unchanged stored input.
Run focused JVM tests, normal incremental app build, and scoped Android lifecycle/
UI tests when a dedicated target is available. Measure evidence honestly; emulator
timings do not establish physical-phone neural-inference performance.
