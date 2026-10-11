# Progressive Archival Implementation Plan

**Goal:** Retain imported audio normally, provide optional independently cancellable
progressive compression, and save audio/managed Markdown to selectable folders.

**Architecture:** Preserve the existing incremental recognition pipeline. Add
durable artifact/job ownership outside an isolated archival worker, rolling PCM
recovery with independent consumer cursors, and a URI-aware storage boundary.

**Tech stack:** Kotlin/Compose, Android MediaCodec/MediaMuxer, foreground/bound
services, private atomic metadata, SimpleStorage SAF access, existing JVM/native QA.

Approved product contract: `../specs/2026-10-10-progressive-archival-design.md`.
Original workspace: `.worktrees/progressive-archival`, base `f6975ce`.
On 2026-10-11 the user requested work in the project checkout. The implementation
was transferred to `C:\git\TranSlander`, branch `feat/progressive-archival-project`,
and integrated with `72dc059c` (microphone/HFP processing and pinch controls).
Final execution evidence: `../../qa/progressive-archival-final-2026-10-11.md`.
All application paths below are under
`app/src/main/java/io/github/lrq3000/utterlane/` unless specified.

## 1. Baseline and documentation

- [x] Create user-approved worktree; preserve root untracked fixtures.
- [x] Read CORE_VALUES in full and add user's general principle verbatim, with the
  rolling-streaming/live-archival bullets explicitly labelled as examples.
- [x] Record consolidated approved specification and baseline JVM verification.

## 2. Toolchain and dependency migration

- [x] Upgrade root/app/native/helper Gradle configuration, wrapper/checksum,
  AGP Kotlin DSL, CI SDK prerequisites, and developer/distribution docs.
- [x] Add SimpleStorage 3.1.0; preserve minimum API 26 and target API 36.
- [x] Preserve recognition algorithms through compiler/toolchain migration.
- [x] Verify JVM tests, Android test compilation, debug build and minified release.
- [x] Independently review spec compliance, then code quality/build reproducibility.

## 3. Shared import policy

- [x] Add a pure `ImportRetentionPolicy` with 600000-ms/52428800-byte strict
  thresholds and independent remembered KEEP/DISCARD preferences.
- [x] Split source inspection/copy in `transcribe/DialogAudioActions.kt`; publish
  normal retained ownership only after a complete nonempty private import.
- [x] Present the decision in shared Home/external dialog workflows; handle
  cancellation and replacement without abandoning prior owned work.
- [x] Preserve newer dialog-exit recovery/confirmation contracts and pruning leases.
- [x] Add/run focused policy and import-lifetime tests, build, then two-stage review.

## 4. Managed storage and archival control

- [x] Add focused storage/artifact metadata units for local files and exact SAF
  URIs, with durable cleanup intent independent of visible history rows.
- [x] Add bounded durable job scheduling, snapshots, generation/cancellation and
  safe publication; parent remains sole repository/settings owner.
- [x] Add isolated archival worker and foreground notification supervisor with
  stop-all action, proper API-specific service type and timeout handling.
- [x] Integrate initialization without reinitializing model/data stores in workers.
- [x] Add/runtime-test force-stop and late callbacks; verify durable restart in repository tests.
- [x] Independently review requirements, then quality/failure isolation.

## 5. Folder settings, audio publication, and linked Markdown

- [x] Add SimpleStorage audio/transcript folder selectors and persistent grants.
- [x] Adapt history readers/playback/export/retranscription to URI-backed retained
  audio while preserving legacy files and avoiding a permanent duplicate.
- [x] Add bounded Markdown generation with datetime/model filenames, exact URI
  registration, retries, and linked Delete/prune through existing UI controls.
- [x] Wire imports and transcript completion/manual save to durable jobs; retain
  usable local sources on provider failure and prevent cancelled/deleted re-save.
- [x] Test folder failures, collisions, EOF, text completeness, pinning and races.
- [x] Independently review requirements, then quality/lifecycle correctness.

## 6. Compression and rolling capture integration

- [x] Add snapshotted quality settings and format/device capability validation,
  collapsed optional UI and exact requested explanatory strings.
- [x] Implement bounded AAC/AMR writers, recoverable encoded checkpoints, and
  independent streamed conversion of uncompressed imports only.
- [x] Generalize source/capture format without changing native default ASR samples.
- [x] Introduce rolling PCM parts and independent consumer cursors; delete PCM
  only after safe encoded persistence and required reader advancement.
- [x] Keep original PCM for active ASR; fallback on failed/stopped encoder;
  release capture independently of archival completion.
- [x] Test tail, starvation, cancellation, worker termination, recovery, actual format,
  prompt new recording, bounded buffers and delayed consumers.
- [x] Independently review requirements, then quality/recovery correctness.

## 7. Integrated verification and delivery

- [x] Run focused JVM and Android suites, `assembleDebug`, minified release/APK
  bundle, release signing-helper regressions, and native alignment checks.
- [x] Perform a representative speed comparison; diagnose native word variation
  separately with a single archival-free replay control, retaining all evidence.
- [x] Record commands, results and limits in `docs/qa/progressive-archival-final-2026-10-11.md`
  and linked milestone reports; update user/privacy documentation.
- [x] Final independent spec, integration, code-quality and QA-evidence reviews; fix findings.
- [x] Inspect final diff/status. Leave changes on feature branch; no commit/push/
  PR without explicit user instruction.
- [ ] External release gate: full F-Droid build. No F-Droid CLI/checkout is available;
  the historical SDK/Gradle recipe requires maintainer-approved updates as documented
  in `docs/distribution/README.md`. A successful app build does not establish this.
