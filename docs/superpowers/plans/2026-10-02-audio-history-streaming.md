# Audio history and bounded transcription implementation plan

> **For agentic workers:** Use `executing-plans` inline, task by task. The user approved the written specification and requested execution. Do not commit or publish without an explicit request.

**Goal:** Preserve optional microphone audio and transcribe long audio incrementally without duration-dependent audio allocations.

**Architecture:** Shared bounded segmentation, stateful conversion, and serialized inference serve file decoding and microphone sessions. A background WAV history writer doubles as the retained session's disk backlog. A file-backed transcript supplies bounded previews and export.

**Tech stack:** Kotlin, coroutines, Android MediaCodec/AudioRecord/JobScheduler/MediaPlayer, Compose, DataStore, sherpa-onnx 1.12.23, JUnit 4, Python/adb QA.

**Execution status (2026-10-03):** Tasks 1–6 implemented and verified. Final result:
22 JVM tests passed; 12 Android tests passed and one pre-existing LDPlayer
SpeechRecognizer binding refusal was explicitly skipped. The detailed QA report
at `docs/qa/audio-history-streaming.md` records coverage, adaptations, and limits.
No commits, push, PR, or merge were requested.

## Task 1 — Reproduction and independently testable primitives

Files: create `tools/qa/audio_fixture.py`, `app/src/test/java/com/translander/asr/AudioPipelineTest.kt`, `app/src/test/java/com/translander/history/HistoryTest.kt`; add JUnit to `app/build.gradle.kts`.

- [ ] Generate repeatable short and long WAV fixtures, using a small upstream spoken WAV as the source; keep the fixture generator in the repository.
- [ ] On the baseline APK, open a long high-rate stereo WAV and capture the resulting process/logcat/memory evidence before editing the pipeline.
- [ ] Write tests first for bounded windows, exact sample ownership, flushing, low-energy cuts, resampling across uneven blocks, retention boundaries, WAV headers/recovery, and transcript preview/export.
- [ ] Run `gradlew.bat testDebugUnitTest --console=plain --quiet` and verify the missing-feature failure.

Core contracts used by subsequent tasks:

```kotlin
data class AudioWindow(val samples: ShortArray, val startSample: Long,
    val ownedStart: Long, val ownedEnd: Long)
// AudioSegmenter.accept/finish invoke a suspend consumer with each bounded window.
// StreamingResampler.accept/finish return bounded mono PCM16 blocks.
// HistoryRetention.expired(referenceMs, nowMs) includes equality at the deadline.
// WavFile.append/read/finish use sample offsets rather than accumulating PCM.
// TranscriptStore.append/preview/readForTransfer/export use bounded previews.
```

Test commands: `gradlew.bat testDebugUnitTest --tests '*AudioPipelineTest' --tests '*HistoryTest' --console=plain --quiet`.

## Task 2 — Bounded source conversion, segmentation and native inference

Files: create `asr/AudioSegmenter.kt`, `transcribe/StreamingResampler.kt`, `asr/TranscriptStore.kt`; modify `transcribe/AudioDecoder.kt`, `asr/ParakeetRecognizer.kt`, `asr/RecognizerManager.kt`, `asr/DictionaryManager.kt`.

- [ ] Implement the tested pure-Kotlin primitives: at most ten seconds owned audio plus one-second left/right context, pause preference, complete tail flush, continuous resampler state.
- [ ] Replace full-file decoding with `suspend fun decode(uri: Uri, onSamples: suspend (ShortArray) -> Unit, onProgress: (Int?) -> Unit)` and a path overload. Release output buffers before downstream suspends; use output PCM format rather than assuming input format.
- [ ] Return timestamped `OfflineRecognizerResult` for one bounded window; do not allocate floats for a complete session.
- [ ] Serialize initialization, each native decode, and release through one lock on the IO dispatcher. Callers never unload the model synchronously on the UI thread.
- [ ] Assemble timestamp-owned whole words and hold a bounded dictionary phrase tail across windows. Test repetition and boundary words rather than blindly deduplicating text.
- [ ] Expose a shared `TranscriptionSession.accept/finish` used by files and microphones, with completed segment callbacks and a file-backed transcript.
- [ ] Run the focused JVM tests and `gradlew.bat assembleDebug --console=plain --quiet`.

## Task 3 — History storage, pruning and settings

Files: create `history/HistoryRetention.kt`, `history/WavFile.kt`, `history/RecordingHistory.kt`, `history/HistoryCleanupService.kt`, `history/HistoryScreen.kt`; modify `settings/SettingsRepository.kt`, `settings/SettingsActivity.kt`, `TranslanderApp.kt`, `AndroidManifest.xml`; add default strings and backup-exclusion XML.

- [ ] Implement all eight retention choices, with No history as default; snapshot active recording policy and apply setting changes to completed history.
- [ ] Write PCM16 WAV in hourly parts under one session ID, with sample-offset reads and interrupted-header repair. Finalize metadata atomically; retain failed/interrupted audio.
- [ ] Track active leases in a keyed map; exclude live sessions from pruning and defer deletion until release. Index completed entries for paginated newest-first access.
- [ ] Persist a JobScheduler cleanup job; prune on startup, policy change, finalization, and history access. Job work runs on IO and does not keep the application awake indefinitely.
- [ ] Add retention selection and history browsing/playback/share/delete/retranscribe. History content stays private and excluded from backup, with grants through the existing FileProvider.
- [ ] Test retention/deletion/leases/disabled-history/WAV parts and errors; verify the settings screen on LDPlayer.

## Task 4 — Unified microphone lifecycle and backpressure

Files: replace capture internals in `asr/AudioRecorder.kt`; create `asr/MicrophoneSession.kt`, `service/StreamingTextTarget.kt`; modify `service/FloatingMicService.kt`, `service/TextInjectionService.kt`, `service/SpeechRecognitionService.kt`, `service/VoiceInputActivity.kt`, `ime/VoiceInputMethodService.kt`.

- [ ] Capture immutable 200 ms blocks without keeping a recording-long list. Stop flips state and ends AudioRecord; its capture worker owns release and is joined before final flush.
- [ ] A bounded live queue holds twenty seconds beyond the active inference window. If history is enabled, a bounded writer queue publishes offsets to the disk-backed reader. Reject overlapping microphone starts globally.
- [ ] Show overload/storage/capture errors; stop rather than drop samples or allocate an unbounded queue. Drain accepted audio and preserve recoverable transcript/audio.
- [ ] Use one lifecycle controller in all five integrations. Stop drains; cancel prevents late results and releases workers. Preserve pertinent logs and prevent repeated starts during drain.
- [ ] IME commits deltas to the original connection. Accessibility/floating output pins the original field and verifies its focus/identity before insertion; clipboard fallback represents the complete bounded-transfer result.
- [ ] SpeechRecognitionService honours partial-result requests and returns a bounded final bundle. VoiceInputActivity removes the ten-second cutoff, shows partial previews and preserves the final-result contract.
- [ ] Test no-history microphone capture, history capture, overload, quick stop/start, cancellation, focus switching, and dictionary corrections on LDPlayer.

## Task 5 — File UI, recoverable results and export

Files: modify `transcribe/TranscribeActivity.kt`; share transcript helpers with microphone/history screens; modify FileProvider paths/default strings as necessary.

- [ ] Show processing progress and a bounded transcript preview while decoding/recognition interleave. Copy is limited to a safe transfer size; share/export streams the complete text file.
- [ ] Offer paging through the transcript without reconstructing the entire text in Compose state. Keep completed text after an error and offer export before dismissal.
- [ ] Respect cancellation separately from errors; do not wait forever for a readiness flow after initialization failed.
- [ ] History retranscription uses a lease and all WAV parts in session order, without duplicating original audio.
- [ ] Verify short/long WAV, AAC/MP3, multichannel/resampling, unknown duration, cancel, error recovery, playback and text export on LDPlayer.

## Task 6 — Verification and documentation

Files: update `README.md`, `PRIVACY_POLICY.md`, and the QA report in `docs/qa/audio-history-streaming.md`; retain all test source files.

- [ ] Run `gradlew.bat testDebugUnitTest assembleDebug --console=plain --quiet` and `git diff --check` after the final source changes.
- [ ] Install the resulting APK and verify package version, ARM64 compatibility, permissions, process state and crash logs.
- [ ] Compare Java/native/PSS snapshots on warmed-up short and long audio; check actual progress before end-of-input and correct final text, not just UI launch.
- [ ] Verify retention policies, active leases, interrupted recording recovery, background cleanup, disabled-history creating no audio, and all five microphone integrations.
- [ ] Document exact commands, measured evidence, artifact locations and any emulator-only limitations. Report any remaining spec gaps explicitly; do not claim a physical-phone crash fixed from static inspection alone.

## Review

The plan covers the approved spec's history, all input paths, bounded queues/inference,
boundary context, dictionary corrections, lifecycle/error handling, text export,
pruning and runtime evidence. Source-built release/F-Droid packaging stays
authoritative; the ignored version-matched AAR is for local validation only.
