# Microphone history and bounded incremental transcription

## Status and intent

The user approved the recommended approach on 2026-10-02: retain Parakeet
TDT v3, add optional microphone audio history, and replace whole-recording
processing with bounded incremental processing. The default retention is No
history. LDPlayer is the requested runtime test target. This document records
that design for the required written-spec review before implementation.

## Evidence and limits

The baseline is commit `db74ab2` (version 1.2.4).

- `AudioRecorder` retains all PCM chunks and copies them into a complete array
  on stop. Recognition starts after recording stops in all microphone callers.
- `AudioDecoder` retains all decoded source PCM, merges it, and then performs
  whole-file downmixing and resampling.
- `ParakeetRecognizer` allocates floats for the entire input and submits one
  offline recognition stream.
- The shared recognizer currently permits simultaneous readers. Incremental
  sessions must serialize native decoding rather than relying on that lock to
  make concurrent inference safe.

These source observations demonstrate unbounded duration-dependent audio
memory; they do not prove the cause of the user's particular crash. Runtime
reproduction and before/after measurements remain required. NVIDIA's NeMo
streaming/local-attention examples do not imply that the existing exported
ONNX files expose the same streaming state or attention configuration.

References inspected:

1. [NVIDIA Parakeet v3 model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
   including NeMo chunked inference and long-form local attention.
2. [Sherpa-onnx Parakeet v3 documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html#sherpa-onnx-nemo-parakeet-tdt-0-6b-v3-int8-25-european-languages),
   including simulated streaming and long-audio segmentation.
3. [Pinned v1.12.23 Kotlin recognizer API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.23/sherpa-onnx/kotlin-api/OfflineRecognizer.kt),
   exposing text, tokens, timestamps, and TDT durations.

## User-visible behavior

### Audio history

Settings offer exactly these retention choices:

| Choice | Retention after recording completes |
| --- | --- |
| No history | No audio file is created for new recordings |
| 1 hour | 1 hour |
| 6 hours | 6 hours |
| 1 day | 24 hours |
| 7 days | 7 days |
| 30 days | 30 days |
| 90 days | 90 days |
| Forever | No age-based expiry |

The initial/default setting is No history. Only microphone sessions create
history. Shared/opened/monitored files are read from their original source.

A history screen accessible from Settings lists recordings newest first with
date, duration, and completion/failure status. Users can play, share/export,
delete, and retranscribe an entry. Recognition failure does not delete its
saved audio. Incomplete recordings after process termination are recoverable
when possible and clearly labelled as interrupted. Interrupted entries use
their last successful audio-write time as the retention reference.

Retention is snapshotted when recording starts; a change applies to new
sessions and immediately prunes eligible completed entries. Selecting No
history removes completed history. Active capture and active playback or
retranscription hold a lease; cleanup of their files waits for lease release.
The settings description explains the active-session rule.

Cleanup runs on startup, on history access, after recording finalization, on
retention changes, and through a persisted background job. Expired entries
are filtered before presentation. Android can defer scheduled physical
deletion while asleep or when the application is force-stopped; do not claim
an exact wall-clock deletion guarantee.

### Incremental results

Shared-file transcription shows completed text segments during processing,
with progress when source duration is known, an indeterminate state otherwise,
and explicit cancellation. Already completed text remains available after a
later segment fails.

Microphone inference runs concurrently with capture. The IME commits newly
completed text once, and stays active until stop. Accessibility and floating
microphone integration insert completed segments into the original target;
focus changes must not redirect later text into an unrelated field. If
insertion becomes unavailable, completed text is retained for clipboard or
export rather than discarded. Clipboard fallback represents the session text,
not just the latest segment.

`SpeechRecognitionService` supplies partial results when the caller requests
them and one final result at completion. `VoiceInputActivity` can show progress
but its activity-result contract still returns a final result. Remove its
unconditional ten-second auto-stop so manual long recordings are supported.
Android IPC, clipboard, and external editor limits still apply: overly large
single-result transfers must report a useful error and preserve an exportable
transcript instead of risking a transaction failure.

## Architecture and bounded resources

Implement shared, encapsulated components rather than duplicating recording
and transcription logic across the five microphone entry points.

1. **PCM source:** microphone capture or `MediaCodec` decoding emits small,
   immutable PCM blocks. File decoding downmixes and resamples incrementally.
   Resampler phase, the previous sample, and incomplete channel frames carry
   across codec buffers. Honour decoder output-format changes, PCM encoding,
   buffer offset/size, unknown duration, and cancellation. Release each codec
   buffer promptly and all codec/extractor resources in `finally`.
2. **Segmenter:** retain a bounded working window, prefer pause boundaries,
   and force a boundary during uninterrupted speech. Start with a ten-second
   maximum owned interval and at most one second of context on each side.
   Every inference input is therefore at most twelve seconds. Short recordings
   flush immediately on stop; silence alone must not cause a session error.
   Initially prefer a boundary after 600 milliseconds of low-energy audio once
   the owned interval is at least three seconds. A lightweight pause detector
   is only a boundary aid and never discards audio. These internal timing
   constants can be tuned from accuracy/latency evidence without changing the
   memory cap or adding user-facing settings.
3. **Recognizer:** decode one bounded window at a time through a shared
   inference mutex. Release its native stream immediately. Initialization,
   decoding, and release use a coherent lifecycle; release cannot free native
   state used by an active decode. Cancellation is observed between native
   calls; do not pretend synchronous JNI decoding can be interrupted safely.
4. **Boundary/result assembler:** each window owns a disjoint time interval.
   Context is recognized but not emitted twice. Use timestamped tokens grouped
   into complete words and test words straddling boundaries, repeated phrases,
   punctuation, and multilingual scripts. Do not remove genuine repetitions
   with an unrestricted suffix/prefix text heuristic. Apply dictionary rules
   before committing text; retain a bounded pending tail when a replacement
   phrase spans segment boundaries.
5. **Microphone session:** owns capture, bounded queues, segmentation,
   result delivery, history, and an explicit state machine. Stop ends capture,
   joins the capture producer, flushes the tail, drains pending inference, and
   finalizes once. Cancel joins/releases producers and consumers without late
   callbacks or concurrent `AudioRecord` release. Overlapping starts are
   rejected visibly. A session holds its original output target identity.
6. **History repository/writer:** write 16 kHz mono PCM16 WAV on a dedicated
   background writer through a bounded queue. This avoids real-time audio
   compression overhead. Files live in app-private storage excluded from
   automatic backup and are shared through scoped content-URI grants. Metadata
   is finalized atomically. Split audio into hourly WAV parts under one logical
   session to avoid classic WAV size-field overflow for very long sessions.
7. **Transcript store:** append completed text to a temporary local text store,
   with bounded UI pages/preview and streaming file export. Do not rebuild or
   retain the entire transcript on every update. Temporary text is not audio
   history and is cleaned after the result is dismissed or exported; session
   errors preserve it until the user has a chance to recover it.

For imported files, backpressure suspends decoding until downstream capacity
exists. For microphones, capture cannot wait indefinitely for inference. With
history enabled, already-written history parts can also serve as a disk-backed
backlog; use offsets and published written lengths, not another duplicate audio
file. The capture-to-writer queue is still bounded. If storage/writer capacity
fails, show the history failure and continue recognition only while its
bounded live queue has capacity.

With No history, create no temporary or retained microphone audio files. Use
a finite live queue (initially two inference windows beyond the active window).
If it fills, stop capture and visibly report that recognition cannot keep up,
then finish already accepted audio and expose its transcript. Do not silently
drop samples, grow the queue, or restart recognition as if nothing happened.

Audio memory is O(window size + bounded queue capacity), independent of total
duration. Sample processing is linear in source length with bounded context
overhead. The model itself still has a nontrivial fixed/native working-memory
cost. History writing consumes resources; the goal is measured low overhead
and no writer/pruner work on the UI or inference path, not a zero-cost promise.

## Failure handling

Differentiate model initialization failure, microphone permission/capture
failure, unsupported or malformed audio, inference failure, history write
failure, insufficient disk space, overload, cancellation, and no recognized
speech. Do not collapse all of them into null/no-speech. Propagate cancellation
rather than swallowing it as an ordinary error.

Log session lifecycle, sample counts, segment durations, bounded backlog size,
and failure causes. Preserve pertinent existing logging. Catchable errors get
localized UI or API errors; prevention, runtime memory measurement, and Android
exit diagnostics are needed for process kills or native crashes that cannot
be made safe by catching an exception.

## Verification and delivery

- Reproduce baseline long-audio behavior on LDPlayer with the real model and
  repeatable short/long spoken fixtures; capture relevant logcat and memory
  snapshots. Distinguish an actual reproduction from static evidence.
- Test segmentation caps/flush, context ownership, real repetitions,
  multichannel conversion, noninteger resampling ratios, unknown duration,
  cancellation, queue overflow, and stop/start races.
- Test all eight retention choices, exact expiry boundaries, policy changes,
  active leases, history-off creating no audio, writer failure, interrupted
  WAV recovery, and imported audio creating no history.
- Test incremental delivery across all five microphone entry points and
  shared-file transcription, including focus changes and dictionary phrases.
- Run focused automated tests and `gradlew.bat assembleDebug` incrementally.
  Keep development test fixtures/harnesses in the repository for review.
- On LDPlayer verify playback, sharing, retranscription, pruning, incremental
  output before end-of-input, and multi-hour file processing with bounded
  audio buffers. Compare Java/native/PSS trends after model warmup; report
  emulator timing separately from claims about physical-phone performance.
- Report any hardware/native-library or microphone-injection test limits
  explicitly rather than treating installation/UI launch as full ASR coverage.

Keep the work on `feat/audio-history-streaming` in its new local worktree,
with focused changes and no source edits to the main worktree. Commits, push,
and PR publication require an explicit user request. Existing source-built
release/F-Droid workflows remain authoritative; a version-matched upstream
AAR may be used solely as an ignored local validation dependency if needed.
