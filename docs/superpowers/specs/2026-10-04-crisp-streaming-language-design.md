# Custom speech models, streaming speakers, and app language

Approved direction: streaming-first speaker labels for files and microphones,
optional (default off), with Auto or 1–8 speakers. Keep existing unlabeled output.

## Model imports

Use the pinned CrispASR generic session API in the isolated recognition process.
Import local model files (including companion files) into private storage, keeping
their original names; select the primary file explicitly. Store a manifest with
sizes, SHA-256 hashes, and an optional explicit tokenizer/codec role. Register that
companion through the native setter before warm-up; other companions use sibling
discovery. Publish only complete imports atomically. Native loading
and warm-up validate compatibility; unsupported models must produce a recoverable
error, never be substituted with a catalog model. Speech recognition is the scope
of “custom model”; TTS and music models are not transcription models. Keep the
existing verified downloads and ONNX Parakeet installation usable.

Backends with word timings retain overlapping-window ownership. Backends without
timings transcribe disjoint owned audio, without fabricated word timestamps.
Diarization for these backends uses speaker-turn audio slices for attribution.

## Streaming speakers

Use Nemotron-3-Diarization's persistent native stream (16 kHz mono). Every sample
is submitted exactly once despite ASR overlap. One session owns its cache and
speaker history; release it on completion/cancellation. Keep only a bounded recent
frame timeline. Hold output until its speaker decision is available, and flush the
native stream for the final audio. Labels are anonymous and session-local.
At EOF, coalesce a pending boundary and its insufficient right context into one
final bounded window. Preserve the ordinary path's previous EOF cuts when Off.

Auto uses the eight native arrival-order tracks. A specified count constrains
decoding to that many arrival-order tracks; it does not invent voices that have
not spoken. Explain this constraint in Settings. Do not claim an offline global
clustering guarantee. Unknown speech remains explicitly unknown instead of being
silently assigned to a random speaker. Dictionary corrections apply to speech,
never to label syntax, and cannot cross a speaker change.

Install the separate diarization GGUF with a verified atomic download or local
import. No model download or native diarization allocation when disabled. Snapshot
the setting at session start; changes affect the next recording.

## App language

Offer System and all 24 packaged UI languages. UI language is independent of speech
recognition language. Android 13+ uses the system per-app locale API; older Android
versions persist and apply a context locale before inflation. Restore system
locales when System is selected. Existing English fallback covers untranslated
new settings according to project translation policy.

## Verification

JVM tests cover frame ownership, fixed/automatic counts, returning speakers,
unknown speech, overlap deduplication, final flush, immutable emitted labels,
manifest validation, and supported locales. Build the ARM64 debug APK and test
real native model imports/diarization on an available adb target. Report separately
what was verified in JVM tests, native builds, and actual audio inference.
