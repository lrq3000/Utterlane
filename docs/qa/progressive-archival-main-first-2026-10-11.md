# Progressive archival: main-first intent transfer and publication

## Context and authorization

The maintainer requested committing the validated implementation, rebasing it onto
the latest `origin/main` with explicit intention transfer, pushing the feature
branch, merging into main with a self-contained message, and pushing main.
All operations use the project checkout `C:\git\TranSlander`.

Implementation base: `72dc059cc9684e64b5f38f10d02daa54c8158086`, which introduces
explicit microphone/HFP/source choices, gain, pinch controls and playback updates.
Feature branch: `feat/progressive-archival-project`. The original implementation
already underwent source-level integration with that base and runtime verification;
see [root integration](progressive-archival-root-integration-2026-10-11.md) and
[final verification](progressive-archival-final-2026-10-11.md).

The four maintainer-local two-speaker audio/transcript fixtures are unrelated and
remain untracked. Model weights, native caches, APKs, private speech-derived evidence
and local commit-message files remain ignored. No version bump is part of this work.

## Canonical-main replay rule

Fetch `origin/main`, read every newly introduced main commit's complete explanation,
and compare its contracts before replay. If a conflict occurs, use the main-based
version of the entire conflicted file as the starting point (`ours` during rebase),
then reapply this feature's intentions. Preserve earlier replayed changes on that
side. Account for every original feature hunk in a conflicted file, including its
automatically merged hunks; classify each as applied, adapted, already implemented,
or explicitly superseded with a reason. Do not restore the old feature file wholesale.

Clean application is still checked for semantic overlap. In particular, retain
main's source/device/mode enforcement, gain ordering, Home ownership, recovered
provenance, retention-aware exit, progress/EOF and playback-speed contracts.

## Explicit intention-transfer ledger

Paths abbreviate `app/src/main/java/io/github/lrq3000/utterlane/`.

| ID | Intention and preserved invariant | Primary implementation / verification |
|---|---|---|
| I01 | Shared normal import retention, strict >10-minute or unknown/short >50-MiB prompt, independent remembered KEEP/DISCARD; never modify the external original | `history/ImportRetentionPolicy`, `transcribe/ImportRetentionGate`, dialog/Home candidate handoff; policy, lifetime and real UI tests |
| I02 | Protected import publication and per-attempt durable recovery; a sibling's completion cannot discard unfinished work; explicit deletion remains authoritative | `RecordingHistory`, `ImportedAudioCopy`, processing-obligation and provider-close tests |
| I03 | Optional compression off by default; native 16-kHz mono PCM16; truthful supported format/rate/channels/bitrate controls and exact requested help text | `archive/AudioQuality*`, capabilities, settings and resources; tuple and native format tests |
| I04 | Original PCM remains authoritative; normalize/gain only for ASR, preserve main's explicit microphone/HFP/effects configuration and frozen reopen settings | `AudioRecorder`, `MicrophoneSession`, `RecordingPipeline`, route/configuration code; actual-frame routing, gain replay and sample-equality tests |
| I05 | Encode before Stop in an independent low-priority worker; bounded rolling recovery storage reclaims only after required consumers and durable encoded coverage | rolling PCM/journal/encoder units; pre-Stop checkpoint, slow-consumer and worker-stop native tests |
| I06 | Preserve AAC priming and final input tail, split bundled AMR frames, stream container metadata, recover encoded prefix plus original tail | calibration/padding, AMR parser, disk-indexed MP4 and recovery units; native source-edge and exact-count checks |
| I07 | Isolated archival failure/control, bounded disk-backed job scheduling, durable cancellation and pending fallback retry, persistent notification with stop-all | coordinator/job-store/worker/supervisor; dispatch-cancellation, IO failure, timeout, force-stop and retry regressions |
| I08 | SimpleStorage-selected retained audio destination, exact URI ownership, representation-versioned replacement and lease-safe private reclamation | asset/source descriptors and history publication; original-URI reconciliation, export snapshot and representation replacement tests |
| I09 | Complete streamed Markdown named by datetime and actual model; every managed file follows transcript Delete/prune/pin/Forever through existing controls | managed/manual Markdown and transcript history; delayed-save identity, real SAF content/delete/expiry tests |
| I10 | Playback, seek, export/share, retry and Home stay usable while optional archival drains; metadata and payload belong to one leased representation | playback/decoder/dialog/export integrations; new capture during blocked archival and picker-replacement tests |
| I11 | Supported SimpleStorage toolchain without dropping Android 8+ support or altering native/model pins, target SDK, package identity or app version | Gradle/Kotlin/SDK configuration, CI, sherpa helper and signing tool; 1,090 JVM tests, release build and artifact checks |
| I12 | Preserve user's exact general core-value wording and labelled examples; document privacy, measured results and unresolved platform limits honestly | `CORE_VALUES.md`, guides and QA reports; no fabricated native determinism, speed, F-Droid or modern-device claims |

## Verification baseline before Git publication

- 1,090 JVM tests and 13 publishing/build-helper tests passed.
- Debug, instrumentation APK, minified release APK and AAB built; six ARM64
  libraries in each app artifact passed 16-KiB ELF checks and release ZIP alignment.
- 24 distinct Android functional cases passed on API 28, with additional replay
  and fidelity-gate evidence. C2 AAC is absent on that target and explicitly skipped.
- A debug-key-signed local copy of the minified release completed real-model file
  transcription and retained the final words. It is not a production-signed release.
- OFF/ON ASR-window times were 50.715 / 50.458 seconds for identical input windows.
  The native-word variation was reproduced without archival; the exact cause remains
  unknown, and bitwise transcript determinism is not claimed.
- Full F-Droid execution, newer Android foreground-service behavior, C2 and physical
  Bluetooth/OEM behavior retain their documented verification boundaries.

Replay hashes, dispositions and final publication verification are appended after
the authorized Git operations establish those facts.
