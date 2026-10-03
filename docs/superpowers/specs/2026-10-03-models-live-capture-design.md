# Alternative Parakeet models and immediate capture feedback

The user approved this design and requested implementation without further
validation prompts. The base is `669a5a3`, now fast-forwarded into local main.
Work takes place only in the new `.worktrees/models-live-capture` worktree.

## Models

Keep NVIDIA Parakeet v3/sherpa-onnx as the default. Add Moondream Ultra and
Redux using pinned CrispASR native Parakeet code and verified GGUF artifacts.
Offer Q8_0 (quality default, 674,342,400 bytes) and Q4_K (402,226,496 bytes)
for each alternative. Display the actual artifact size and quantization;
do not attribute Photon's 178 MB packing or benchmark speeds to this runtime.

Each model has isolated private storage, checked download size/SHA-256,
atomic publication, cancellation, removal, and persisted selection. A shared
recognizer backend interface provides bounded timestamped results. Switch only
outside an active session, release the old model before loading the new one,
and preserve the existing microphone-history and bounded-input pipeline.
Native sources are pinned and built using the installed Android NDK, not
downloaded as unreviewable prebuilt binaries. CPU/ARM64 is the initial target.

## Capture and processing states

Use a shared, larger bottom panel for IME, voice activity, floating microphone,
and accessibility capture. Accessibility uses its service overlay; other
overlay callers use their existing permission. Panels do not steal editor focus.
The central waveform region is the large Stop button, labelled Tap to finish;
Cancel remains a separate accessible action. Respect navigation/cutout insets.

Meter the actual captured PCM at low latency, storing only a fixed visual
history. No artificial waveform animation suggests input when audio is flat.
Distinguish loading, capturing, low/no signal, capture blocked (if reported by
Android), stopping, processing, completed, cancelled, and failed. A sustained
low signal gets an inline message during capture and clears when sound returns.
Signal level measures acquisition, not semantic speech detection; do not claim
another app owns the microphone unless the platform reports silencing.

After capture stops, freeze the accepted-sample total. Progress reflects
completed owned audio intervals over that total, including already processed
live audio. Show processed/remaining audio and a measured time estimate only
when available. During loading/live capture with an unknown final total, use
an activity state instead of fabricated percentages. Keep processing below
100% until tail inference/corrections and result finalization complete.

## Verification

Test catalog identities/artifacts/default migration, download integrity and
cancellation, backend-switch exclusion, bounded windows and timestamps, meter
RMS/clipping/silence/recovery, queue limits independent of capture-block size,
and progress/estimation. Build and run both alternative GGUFs with real native
ASR on LDPlayer. Validate all supported capture panels with injected deterministic
PCM plus actual AudioRecord silence tests. Preserve the documented pre-existing
LDPlayer SpeechRecognizer binding limitation. Report CPU/memory measurements
and actual supported formats instead of advertising upstream Photon results.
