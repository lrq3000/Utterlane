# Streaming diarization quality, throughput, and recovery

Approved by the maintainer in the OpenCode conversation. Execute continuously in
small conventional commits, each explaining its motivation and semantic scope.

## Required outcomes

- Preserve live streamed ASR, successful wording, overlapping ASR ownership, and
  per-recording speaker state. Never restart diarization on the growing recording.
- Score the supplied local French/multilingual fixtures: one speaker throughout;
  two speakers returning for three turns each. Separate wording, speaker errors,
  unknown fragments, and incomplete transcripts. Do not infer timed DER from text.
- Replace the fixed 90-second request limit with configurable progress-aware
  recovery: connection default 30 s, preparation/inference stall default 300 s,
  optional absolute active-time limit disabled. Genuine completed native work
  renews the stall timer; heartbeats and duplicated updates do not. Preserve sleep
  handling, explicit reset/cancel, idle unloading, and partial recovery.
- Collapsed Advanced settings expose validated operational controls with units,
  defaults, applicability, reset, and effective settings. Snapshot configuration
  safely at worker/session/transfer start. Fixed model/IPC contracts stay fixed.
- Preserve native timing data, assign words over bounded probability intervals,
  stabilize short unknown gaps/turns, inherit punctuation, and retain identities
  through pauses, language switches, transport boundaries, and finalization.
- Fixed one speaker bypasses unnecessary diarizer inference. Auto must separately
  succeed on the one-speaker fixture; fixed multi-speaker options must not blindly
  discard high-confidence native channels.
- Evaluate bounded native catch-up factors 1/2/4/8, threads, and streaming presets
  against accuracy gates and warmed-up throughput. Expose validated experimental
  cache/FIFO/update controls through a reproducible pinned adaptation.
- Show local processing stages, progress age, backlog and performance evidence.
  No implicit uploads, no automatic audio replay, no unbounded diagnostic storage.

## Commit progression / execution checkpoints

1. Fixture scorer and baseline: stdlib Python runner, regression tests, local
   reference input support, coverage and consistent speaker-mapping metrics.
2. Runtime configuration: typed validated options, persistence, snapshot semantics,
   watchdog policy tests, operational-versus-structural parameter inventory.
3. Worker recovery: distinguish progress/results, active-time budgets, queue/start
   stages, opaque native fallback and recoverable diagnostics.
4. Attribution: native word intervals, probability-preserving bounded timeline,
   word/punctuation alignment, continuity and final-boundary regressions.
5. Native throughput: completed-work progress, CPU settings, bounded catch-up and
   versioned experimental context options; preserve model and speaker cache.
6. Advanced UI and capture/transfer wiring: constraints, reset, contextual help,
   same ASR defaults, buffer/recovery settings, and local diagnostics.
7. Fixture/device validation: word parity and expected turns, accurate full tails,
   watchdog non-stall/stall cases, warmed-up speed/memory, final source review.

## Validation

Use JDK 21 and documented pinned-source preparation. Run focused JVM/Python tests
before integration builds, normal incremental mode. Native/device QA uses only
the dedicated UtterlaneCrispQA LDPlayer instance (index 1, ADB 127.0.0.1:5557,
server 5038), with an isolated app ID. Original user fixtures stay intact in
`C:/git/TranSlander/test_material/streaming_diarization_accuracy`.

Working branch `fix/diarization-recovery` starts at current origin/main `1b1c12e`.
Baseline `gradlew.bat testDebugUnitTest --console=plain -q` passed before changes.
The plan is not permission to push or merge main; preserve focused local commits.
