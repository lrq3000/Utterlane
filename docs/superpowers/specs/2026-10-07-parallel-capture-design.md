# Capture independently of model preparation

## Approved intent

Apply CORE_VALUES.md to the complete microphone workflow: begin listening without
waiting for model preparation, retain input with bounded RAM, contain recognition
failures, and offer recovery after capture ends. The maintainer approved the shared
disk-backed pipeline and private temporary audio even when history is disabled.

## Lifecycle and boundaries

- Keep the existing shared MicrophoneSession and native recognition worker. A
  testable coordinator runs capture, disk writing, and recognition independently.
- Capture starts after its own settings/storage setup, never after model loading.
  The ready callback follows actual microphone startup. Stop before startup is
  sticky, and Stop during loading closes capture but lets preparation/backlog finish.
- A bounded capture-to-writer queue absorbs short disk scheduling delays. Published
  sample offsets advance only after writes succeed. Recognition reads those offsets
  in bounded blocks; it never owns the recording's lifetime.
- Preparation/inference exceptions are recorded without cancelling capture or the
  writer. Completion is reported only after capture and writer finish. Explicit
  cancellation still cancels the operation and cleans up every resource.
- A storage failure is distinct from model failure: preserve the written prefix,
  report the actual failure, and stop if no lossless destination remains. Preserve
  the block that exceeds the writer queue while stopping rather than silently
  dropping that already-read input.
- Existing partial transcript recovery and editor ownership remain authoritative;
  retry produces a separate transcript rather than reinserting duplicate text into
  an old editor.

## Audio ownership

Reuse the existing split-WAV history writer and reader. With history enabled the
recording follows the user's retention policy. Otherwise store it in a separate
private, backup-excluded temporary area, never in the history list. A recording
handle owns finalization, read leases, and disposal. Successful/cancelled temporary
sessions are deleted; failed ones transfer to recovery until that screen closes,
including while viewing the result of a successful retry.
Switching to model settings and activity recreation must not dispose the audio.
Abandoned temporary files from a previous app process are cleaned up before the
first temporary recording is created. Document that temporary buffering is
different from retained history.

## Presentation and recovery

Recording state and model-preparation state are independent. While capture runs,
show Listening plus model-loading status, a live waveform, and an enabled Stop.
If recognition fails, show a nonmodal indication that audio is still recording;
do not open the failure dialog until capture ends. After Stop, show the failure,
what audio remains, Retry transcription, and Choose another model. The latter
opens Settings at its model selector and returns to the same recovery operation.
Use the existing transcription screen for retry and copy/export. Make pending
recovery accessible from Settings and a content-free notification for background
entry points where Android may block opening an activity.

## Verification

Use a deterministic AudioCapture and gated/failing recognition preparation to
prove recording starts before readiness. Check exact sample order and count across
the load boundary, failure survival, Stop-before-ready, immediate Stop, cancellation,
queue capacity, writer failure, temporary cleanup, and recovery ownership. Exercise
the shared Android entry-point adapter and recording/recovery UI with injected
capture where practical. Run focused JVM tests, normal incremental debug and test
APK builds, and targeted Android instrumentation. Do not claim native model timing
or physical microphone fidelity from deterministic injected PCM.
