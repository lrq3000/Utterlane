# Managed archival milestone — 2026-10-10

Status: **DONE_WITH_CONCERNS — implemented and build/JVM verified; parent runtime
QA remains required.** The former SAF enumeration decision is resolved by the
controller-approved recovery-only implementation detail below. This is no longer a foundation-only
delivery.

Workspace: `C:\git\TranSlander\.worktrees\progressive-archival`, branch
`feat/progressive-archival`, base `f6975ce9`. Parent build/docs/import changes were
preserved. One implementer, inline self-review, no commit/push/worktree creation,
no emulator/app-data operations, no clean, source preparation or manual cache
deletion. Native configure/build tasks ran as part of the requested Gradle command;
native merge/copy/strip outputs remained up-to-date.

## Delivered behavior

- Default audio storage remains private. Settings now offers a SimpleStorage 3.1
  folder picker and a return-to-private action. New recording/kept-import producers
  snapshot the destination; existing audio keeps its own location and pending plan.
- Audio copies and complete Markdown writes run sequentially at background thread
  priority in a nonexported `:archive` service. IPC carries small descriptors and a
  private source PFD, never complete audio/text buffers.
- The parent alone owns settings, history, durable jobs and the foreground
  supervisor. Secondary Application processes return before DataStore, models,
  recognizer initialization or trigger startup.
- One parent notification covers executing/draining work and active producers,
  with the exact action **Force stop all archival jobs**. Home, the detail dialog
  and Settings expose the same control and retry/status UI, including when
  notification permission or the channel is disabled.
- Stop-all fsyncs one cancellation epoch before stopping the worker. It invalidates
  queued/running jobs and old producers, rejects late publication, and kills only
  the verified same-UID `:archive` PID. Existing published artifacts and unrelated
  capture/recognition remain owned normally. Fresh user operations use a new epoch.
- Foreground ownership is acquired at operation start. Microphone entry points
  attempt acquisition at their user-start boundary, including overlay/IME cases;
  Android still decides whether that start is allowed. Ordinary queue wakeups and
  completion callbacks do not infer a background-start exemption. Denied starts
  leave useful private input and queued work with an explanatory status.
- COPY_AUDIO and SAVE_MARKDOWN are transfers, so the supervisor uses `dataSync`
  on API 29+, with the two-argument foreground API on 26–28. It is non-sticky and
  handles `onTimeout` by pausing executing work, stopping its worker and removing
  foreground ownership. No media-processing timeout exemption is misused.
- Optional automatic Markdown is off by default and has its own folder selector.
  Complete capture/file results, explicit Keep, manual Markdown and applicable
  exit/recovery publication paths retain normal transcript owners. Automatic
  Markdown can retain text with internal autosave off; ordinary text retention
  still applies. Manual Markdown creates a pinned owner when no saved owner exists,
  following existing Keep semantics. Existing saved owners retain their pin state.
- Manual **Save Markdown** sits in the existing Home/detail result action area.
  Large external writes never set `result.saving` or keep Home recording disabled.
  Narrow playback footers use a second action row rather than clipping buttons.
- Markdown is date/model metadata followed by the complete disk-backed UTF-8 body,
  preserving paragraphs/speaker text. Names use source recording/import chronology
  and the actual result model, plus a cryptographic job/generation suffix for
  collision avoidance and retry identity. Model-name byte limits leave room for
  the identity even with Unicode names.
- Every managed external target remains linked to an AUDIO or TRANSCRIPT owner.
  Existing delete/prune paths durably tombstone that owner before cleanup, including
  when its internal row is already absent. Pins/Forever protect ordinary retention;
  audio-only deletion preserves text and its Markdown. Folder changes and disabling
  automatic saving do not forget existing links or release their grants.
- Playback, seeking, sharing/export and retranscription accept retained document
  URIs. While a runtime lease still protects private staging, consumers prefer that
  input so optional provider availability cannot disrupt ongoing recognition.
  Successful audio publication removes private payload parts after the last lease;
  private metadata remains. Cleanup/unlink retries are lease-aware.
- Original imported files/URIs are never registered as owned targets or deleted.
  No codec conversion, recording-quality UI or live spool was introduced.

## Durable authority and isolation

The private journal uses fsynced temporary files plus atomic replacement. Each job
has an immutable UUID, stable owner kind/ID, source/destination/model/date snapshot,
generation, state, exact returned URI and pending cleanup/reconciliation records.

The journal is the **single durable URI authority**. PUBLISHED commits before the
history's memory projection is changed. Restart rebuilds audio part references from
that authority, avoiding a two-file commit gap that could otherwise make stop-all
delete an already-published audio representation. Parent history keeps metadata and
ordinary disk-backed transcript files, not transcript bodies in memory.

Lock order is history publication lock -> archival metadata lock. Provider/codec IO
never runs under either. The Android availability check used by deletion rechecks
is metadata-only; actual provider access occurs in consumers outside these locks.
Source leases and PFDs are assigned before cancellable dispatch returns, with
non-cancellable cleanup. Last-reader release wakes deferred external cleanup.

An archival-journal failure cannot roll back a successfully published text history
file or turn completed capture into an ASR failure. Destination intent remains in
owner metadata for restart recovery. Archival completion never settles an import's
`ProcessingAttempt`; the original ASR/text-commit boundary remains authoritative.

## Controller-approved SAF recovery trade-off

Fresh creation does **not** enumerate a folder. The pending filename, including a
random UUID and generation, is journaled first. The worker returns the exact URI;
the parent persists ownership and acknowledges it before the first output byte.
Normal publication follows writer close and output existence/known-size verification.

Creation-before-URI process death leaves an unresolved identity. Recovery then:

1. Uses only that recorded folder and exact generated pending name.
2. Requests a provider-side exact-name filter where available.
3. If the provider ignores filtering, walks the child metadata cursor with bounded
   app memory. It does not call `listFiles()` or collect the folder into an array.
4. Accepts one exact identity; ambiguity, partial/loading listings and errors abort.
   Ordinary names, loose prefixes and unrelated files are never deletion targets.
5. Records the recovered URI before deleting it through normal exact-URI cleanup.

This exceptional enumeration was approved by the controller as an implementation
detail preserving the requested goals, not through a separate end-user approval.
It can be O(folder size)
because generic `DocumentsProvider` child filtering is optional. A provider must
honor the valid requested pending name for lost-receipt reconciliation; changed
names observed after a receipt are rejected and cleaned by their recorded URI.
Known container MIME types provide compatible suffixes to avoid ordinary provider
extension rewriting; bytes and actual compression metadata are not converted.

Definitive failure **before** entering `createDocument` retires the pending-name
intent without a scan. A lost/unknown receipt remains conservative: a provider can
outlive the killed worker's Binder request. If recovery currently finds nothing,
the coordinator retains that small intent for a later user-entry/retry check rather
than falsely declaring that a delayed creation can never appear. Known targets
already deleted externally count as successful cleanup. Revoked/unmounted folders
keep cleanup pending independently of visible history rows.

References used when evaluating this boundary:
- [DocumentsProvider API](https://developer.android.com/reference/android/provider/DocumentsProvider)
- [AOSP implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/provider/DocumentsProvider.java)

## Picker and permission details

`ArchivalFolderContract` uses SimpleStorage 3.1's public picker-intent API and keeps
the raw returned SAF URI instead of applying legacy path conversion/root-grant
heuristics. Persistent read/write grants and settings updates happen off Main.
SimpleStorage's API 26–28 picker requires legacy runtime storage permission, which
is requested only when selecting a folder on those versions.

The inspected merged debug manifest confirms minSdk 26 / targetSdk 36,
`WRITE_EXTERNAL_STORAGE` capped at **28**, nonexported archival services, worker
process `:archive`, parent supervisor type `dataSync`, and **no
MANAGE_EXTERNAL_STORAGE** permission. The debug-only test provider's
`MANAGE_DOCUMENTS` attribute protects that provider; it is not an app storage grant.

## APIs for the progressive-encoding stage

Main paths below are relative to `app/src/main/java/io/github/lrq3000/utterlane/`.

### Producer/foreground ownership

- `ArchivalCoordinator.producer(manual = false, userInitiated = false)` returns a
  closeable producer with immutable `settings`, `epoch`, and `textPlan`.
- Register at the visible/user-start boundary. `userInitiated` is used by
  `MicrophoneSession.start`, not by arbitrary later coroutine completion.
- `Producer.audio(id)` snapshots eligible retained audio; `Producer.text(id, folder)`
  plans managed text publication. Close the producer after its final descriptors
  are published, independently of their eventual worker completion.
- `state`, `retry()` and `forceStopAll()` drive in-app control. Retry never revives
  cancelled IDs. Runtime control generations fence delayed drain teardown so it
  cannot stop a newer supervisor/worker.

### Job/ownership journal

- `ArtifactOwner(AUDIO | TRANSCRIPT, id)` is the stable deletion identity.
- `ArchivalRequest(owner, operation, source, destination, displayName, mimeType,
  part, created, model)` describes one immutable private source part/file.
- `currentEpoch()` / `enqueue(request, producerEpoch, retryPaused)` prevent an old producer from
  adding work after stop-all. Non-cancelled requests deduplicate by owner,
  destination, operation and part. The result is `ArchiveSubmission(id, state)`;
  a retired execution record is not needed to acknowledge an already-published URI.
  Explicit manual saves retry eligible paused work. Old producer epochs are rejected
  before retry/deduplication, and fresh explicit operations can create new IDs.
- `claim()` durably registers generation and creation intent and permits only one
  RUNNING/READY job. Restart turns abandoned RUNNING work into PAUSED.
- `target(id, generation, uri)` persists even late receipts; its boolean authorizes
  writing. `notCreated` accepts only definitive pre-creation failure information.
- `ready` follows successful close/verification. `publish` requires a validated
  history owner/publication lock and updates only the memory projection afterward.
- `pause`, `retry`, `cancel`, `cancelAll`, `deleteOwner`, `readerReleased`,
  `revision` and `failure` expose control/recovery. `releaseExecution(id, generation)`
  acknowledges a stopped/finished worker; terminal retirement cannot precede it.
  Global cancellation and owner tombstones are durable before per-job projections;
  stopping a hung worker does not wait for a per-job fsync sweep.
- `ArchivePlan(folder, epoch)` is persisted in recording/transcript metadata and
  working transcript provenance. History startup repairs missing enqueue steps.
- Construct exactly one parent store per root. Never construct it in the worker.
- `job(id)`, `nextReady()` and `summary()` replace whole-history `jobs()` snapshots.
  `forEachPublished(owner, callback)` streams compact references for one owner.
  `nextCleanup()` yields one `ArchivalCleanup` record; `resolvedCleanup`,
  `completeCleanup` and `deferCleanupForLease` update that exact private record.
- `maintain(budget = 32)` advances bounded private-metadata work. Bulk retry,
  cleanup-pass reindexing, cancellation retirement, owner deletion and lease wakeups
  use owned disk cursors. The coordinator yields between batches. `retryPaused()`
  and `beginCleanupPass()` request these passes rather than processing all records
  in the caller. Derived indexes/counts rebuild from unsettled records after restart.

### Bounded storage layout and retirement (P1)

The lifetime `entries`/`byOwner`/`deleted` maps and coordinator attempt/lease sets
were removed. The only job cache is an access-ordered **32-entry LRU**. Status uses
scalar derived counts; there is no all-job-list API or historical materialization
in refresh, claim, cancellation, or cleanup selection.

Private layout under `managed-artifacts/`:

- `work/*.job`: unfinished execution descriptors only.
- `targets/<job-id>/*.target`: one pending URI or creation intent per record;
  repeated failures cannot grow an unbounded set inside a loaded job.
- `owners/<owner-key>/*.pub`: compact per-artifact PUBLISHED URI authority. Source
  filenames, model/date snapshots and execution errors are not retained here.
- `owners/<owner-key>/*.slot`: direct deduplication pointers or minimal same-epoch
  cancellation fences. A slot does not replace the independent URI authority.
- `deleting/*.deleted` / `deleted/*.deleted`: pending and settled owner tombstones,
  addressed directly by owner key instead of loaded into a lifetime set.
- `index/`: disposable state queues, pending-target/owner indexes and lease wakeups.
  Published history and settled tombstones are not scanned to rebuild this index.

Execution records retire only after their worker is quiescent and all remaining
creation/cleanup intents have settled. PUBLISHED references remain until their
owner is deleted and exact provider cleanup is acknowledged. Cancellation epochs
and compact fences survive retirement, so an old producer cannot recreate work.
Owner tombstones remain as minimal durable deletion protection, not cached history.
On-disk authority necessarily grows with retained artifacts/deleted owner identities;
its payload is not redundantly retained as settled execution records or heap maps.

Legacy flat journals migrate one record at a time. Large legacy target sets are
read property-by-property in two streaming passes. Migration preserves duplicate
published representations, pending cleanup and deletion markers. Publication/index
crash gaps are repaired from the authoritative `.pub` file before execution metadata
is retired. Missing permission keeps target records pending without resident sets.

### Worker/IO boundary

- `ArchiveWorkerClient.execute(job, onTarget)` transfers a private read-only PFD
  and waits for completion. `find` and `delete` are separate bounded descriptors.
- `ArchiveWire` carries HELLO, RUN, TARGET/ACK, DONE/ERROR, STOP, DELETE and FIND.
  Handler messages are snapshotted before executor dispatch because Android recycles
  them. Handshake/request identities reject stale connection responses.
- `ArchiveWorkerFailure.creationStarted` distinguishes proven no-creation failures
  from unknown outcomes. A connection timeout is retryable failure, not an ordinary
  coroutine cancellation that could strand a RUNNING row.
- `ArchivalTransfer.execute` implements COPY_AUDIO and SAVE_MARKDOWN with 64-KiB
  buffers and a committed-byte limit; truncated input and provider/close failures
  propagate. `SafArtifacts` owns actual provider calls and metadata verification.
- `ManagedArtifactCleanup` is the synchronous provider-port helper used in tests
  where original provider calls are known settled. The production coordinator's
  IPC cleanup conservatively retains absent lost-receipt names as described above.

### Audio/history boundary

- `HistoryEntry.asset(i)` returns `AudioPart(local, uri, mimeType, pcmSamples)`.
  `uri` is ownership identity; `readUri` chooses the document only after leased
  private staging is gone. `archiveExtension` is naming metadata, not a codec test.
- `AudioAssetIO.available` must remain metadata-only. `open` runs outside locks
  while the caller holds a source lease. `AndroidAudioAssetIO` supplies URI streams.
- `publishArchive`, `planArchive`, `hasLeases`, and normal delete/prune methods are
  the repository boundaries. `HistoryExports` streams through the same part API.
- `part(i)` remains the legacy private/staging path; it does not resolve a URI.
  Current captured parts are still 16-kHz PCM WAV. The PCM reader can sequentially
  skip to an offset on non-seekable streams with bounded memory, not constant-time
  seek. Imported containers use the existing bounded Android decoder.
- ENCODE/live-spool must add real per-part output format/duration metadata and
  consumer cursors. Do not simply queue active WAV files: current capture recovery
  and header finalization assume archival begins after parts/capture are finalized.
- Actual encoding will require `mediaProcessing` on appropriate newer SDKs and an
  accurately justified older-SDK service type; the current transfer-only `dataSync`
  declaration is intentionally not a codec exemption.

## Verification and self-review

**Latest review verification: 576 JVM tests passed, zero failures/errors/skips** —
519 supplied baseline plus **57 archival tests**. Debug and Android-test APK builds
passed. Latest full command: **BUILD SUCCESSFUL in 31s**, 101 tasks
(9 executed, 92 up-to-date).
`git diff --check` passed. Existing compiler/deprecation warnings remain.

New JVM suites use real persistence files and file-backed/fault-injecting provider
ports, including an actual two-thread blocked-write/deletion test:

| Suite | Tests |
|---|---:|
| `ArchivalJobStoreTest` | 14 |
| `ManagedHistoryTest` | 13 |
| `ArchivalTransferTest` | 7 |
| `ManagedArtifactCleanupTest` | 3 |
| `ArchivalConcurrentDeletionTest` | 1 |
| `ArchivalWorkingSetTest` | 10 |
| `ManualMarkdownSaveTest` | 2 |
| `ArchivalCoordinatorLifecycleTest` | 7 |

### Lifecycle spec re-review — claim handoff and failure containment

Both reported regressions were reproduced before the fixes using shared helpers
that now execute in the actual coordinator:

- `ArchivalExecution.runNext` establishes its cleanup region before dispatching
  `claim()` to IO. IO assigns the claimed descriptor to the ownership holder before
  returning. Prompt cancellation on the return dispatch can no longer discard the
  only handle capable of releasing the runtime reservation.
- The same owner acquires its source lease inside IO before returning, quiesces
  dispatched work before release, and settles only its exact ID/generation in
  non-cancellable IO. Every cleanup action is attempted even if an earlier cleanup
  fails. An original cancellation remains a cancellation; secondary cleanup errors
  are reported separately/suppressed rather than becoming an uncaught app error.
- `interruptExecution` only pauses an owned RUNNING reservation. It cannot undo an
  explicit queued retry or a READY/PUBLISHED result. `releaseExecution` clears only
  its matching runtime reservation **before** fallible metadata IO, allowing retry
  after storage access returns without restarting the parent.
- `ArchivalFailureBoundary` and `consumeArchivalWakes` contain non-cancellation
  initialize/maintenance/refresh/drain-start failures. The same boundary covers the
  revision observer and drain child. Failure latches archival paused, stops only
  archival runtime ownership, reports the error and suppresses automatic wakeups
  until retry. Cancellation still propagates. No scope-wide cancellation affects
  capture or sibling work, and reporter/teardown failures cannot escape the boundary.
- Wake turns recheck control generation after cancellable IO so a stale turn does
  not restart archival after another boundary has paused it. Drain-finally work
  does not perform more fallible IO after a failed body.

Seven deterministic JVM lifecycle regressions cover cancellation after IO claim
but before Main resume (stop-all, timeout and plain cancellation), cancellation
after actual history-lease acquisition, quiescing a dispatched suspended worker,
lease-close failure, stale-generation release, queued-retry preservation, and
failure/retry while sibling work remains healthy. They use controllable dispatch
queues and real journal/history files; the maintenance test injects an actual file
read failure into `maintain()`, not just an exception before calling it.

That stronger fault test exposed partial maintenance-cursor state surviving an IO
failure and skipping deletion work on retry. Maintenance now invalidates/closes
partial cursors and rebuilds disposable state from durable records. The regression
verifies that the published URI still reaches cleanup after repair/retry.

Focused ownership/cancellation audit:

| Boundary | Result |
|---|---|
| Claim return to Main | Fixed by the IO-published ownership holder under `try/finally` |
| Source lease and input PFD returns | Lease/PFD assigned before dispatch return; non-cancellable close retained and regression-tested |
| Ready-publication return | Durable READY survives a discarded return; interrupted cleanup does not demote it |
| Cleanup selection, URI resolution, delete acknowledgement | Durable intent survives a lost return and remains retryable; no runtime reservation is acquired |
| Worker target acknowledgement | Captures the original response deferred; an old callback failure cannot fail a newer request |
| Worker stop / supervisor teardown | Bindings/waiters and service ownership are released in `finally`; failures are contained |
| Wake loop, revision observer, drain start/finally | Shared latched failure boundary prevents uncaught Main exceptions and busy-spin |

The first two lifecycle tests failed against the extracted original behavior.
Additional RED regressions caught queued-retry demotion and the real maintenance
cursor-retry defect. After corrections, the focused suite and full checks passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ArchivalCoordinatorLifecycleTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*archive*Test" --tests "*RecordingReaderTest" --tests "*LinkedHistoryTest" --tests "*ProcessingObligationTest" --tests "*ImportRetention*Test" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

All commands used the 600000-ms tool timeout. Latest focused run: successful in
24s. Latest full run: 576 passed and both APK builds successful in 31s. No
non-incremental workaround was needed during this lifecycle pass. One compiler
invocation hit the known temporary `kotlin-backups...` directory error; repeating
the focused command with `--stacktrace` passed without a cache/source workaround.
Android runtime tests remain compiled, not executed; ready for spec re-review.

### Spec-review corrections P1–P3

- **P1:** Regression coverage creates 300 settled publications plus 300 deleted
  owners and verifies the cache stays bounded, execution descriptors retire, and
  restart/status/empty claim do not load historical jobs. Further tests check a
  250-job cancelled backlog with eight-record maintenance budgets, immediate fresh
  epoch claiming, 200 individually stored late targets, deletion scheduling crash
  recovery, publication-pointer repair, same-epoch cancellation fences, and legacy
  migration including duplicate published URIs and enqueue/index recovery over an
  older cancelled slot. The first two scale/retirement tests
  failed against the previous lifetime-map implementation before the redesign.
- **P2:** `ManualMarkdownSave` freezes a `MarkdownSelection` and acquires its lease
  synchronously via `UNDISPATCHED` launch before producer/folder preparation can
  suspend. Publication uses that selected store's model/audio/metadata snapshot.
  UI updates recheck both current and displayed store identity inside StateFlow's
  CAS loop. A delayed-preparation/retry test uses real files, prunes the old working
  cache while the save waits, and verifies old bytes/model/chronology survive while
  the new result's UI identity remains unchanged. It failed first for the missing
  early lease, then for the missing identity fence, before both fixes passed.
- **P3:** Explicit repeated saves requeue eligible PAUSED jobs before reporting
  queued. Published duplicates report already saved. Journal and shared-operation
  tests verify failure/retry, stable owner/job identity, increased execution
  generation, and rejection of cancelled producer epochs. This reproduced the old
  false queued acknowledgement before correction. Producer cancellation is also
  fenced across settings preparation before an epoch can be read after stop-all.

The P1 refactor exposed an optional cleanup-wakeup failure escaping an audio lease
close after journal IO failure. That path is now isolated, and its existing
capture-preservation regression passes. Scheduling tests use actual claimed IDs;
disk queue traversal does not promise filesystem insertion order.

Review commands (all with 600000-ms tool timeout):

```powershell
# P3 RED, then GREEN with history publication tests.
.\gradlew.bat :app:testDebugUnitTest --tests "*ArchivalJobStoreTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
# P2 RED for lease, RED for identity fence, then GREEN.
.\gradlew.bat :app:testDebugUnitTest --tests "*ManualMarkdownSaveTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
# P1 RED: both initial scale/retirement tests failed.
.\gradlew.bat :app:testDebugUnitTest --tests "*ArchivalWorkingSetTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
# Focused GREEN plus Android integration-test compilation.
.\gradlew.bat :app:testDebugUnitTest --tests "*archive*Test" :app:assembleDebugAndroidTest "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
# Final focused GREEN before the full suite.
.\gradlew.bat :app:testDebugUnitTest --tests "*archive*Test" --tests "*RecordingReaderTest" --tests "*LinkedHistoryTest" --tests "*ProcessingObligationTest" --tests "*ImportRetention*Test" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

The review's first full run contained 568 tests and reproduced the same eight
`RecordingPipelineTest` generated `$stable` failures. XML plus `javap` confirmed it;
the authorized targeted `:app:compileDebugKotlin -Pkotlin.incremental=false` passed
in 25s, followed by the normal incremental full command passing all 568 tests and
both assemblies. This is a compiler workaround, not an application-source fix.
The final enqueue-pointer recovery regression brought the total to 569; focused
tests and the full suite/APK command passed again with the normal incremental
setting and no further workaround.

Observed RED phases included: initial journal/history/stream/cleanup scaffolds;
fresh-transfer enumeration; durable owner plans; journal failure undoing text/capture;
cancel/publication tombstoning a surviving owner; Unicode filename byte overflow;
partial-target cleanup; older audio destination mutation; container suffix mismatch;
private-reader fallback; last-lease cleanup wakeup; pre-creation intent retirement;
and unnecessary rewriting/fsync of historical jobs during startup.
Each was corrected and followed by focused GREEN verification. Additional concurrency
coverage verifies deletion completes while provider writing is blocked and the
original imported file survives.

Self-review also corrected cancellation-boundary lease/PFD handoff, recycled Handler
message capture, worker readiness ordering, stale handshakes/drains, connection-timeout
classification, and user-visible foreground ownership for exit/recovery publication.
Journal initialization now restores only unsettled work with bounded memory;
published URI authority is read by owner as needed, not globally materialized.

All Gradle commands used a 600000-ms tool timeout from this worktree. Representative
exact focused commands (repeated during their RED/GREEN phases):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ArchivalJobStoreTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*ManagedHistoryTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*ArchivalTransferTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*Archival*Test" --tests "*Managed*Test" --tests "*RecordingReaderTest" --tests "*LinkedHistoryTest" --tests "*ProcessingObligationTest" --tests "*ImportRetention*Test" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

The full command initially reproduced the known generated-code issue: eight
`RecordingPipelineTest` failures from missing `RecordingHistory$Recording.$stable`
(the resumed checkpoint had 553 tests). The XML stack trace and `javap` confirmed
the missing field. Only then was the authorized targeted workaround applied:

```powershell
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -classpath "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes" 'io.github.lrq3000.utterlane.history.RecordingHistory$Recording'
.\gradlew.bat :app:compileDebugKotlin "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

Before the review, targeted recompilation succeeded in 13s. The subsequent full run passed 554 tests
and both assemblies in 42s. After the final pre-creation/connection refinements,
normal incremental verification passed **555** tests and both assemblies. The final
startup read-only regression/optimization passed **556** tests and both assemblies;
no second workaround was needed for those pre-review refinements. The latest full
verification uses the same exact command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

One intermediate test-compilation invocation also reported a temporary
`kotlin-backups...` directory error. Repeating the same focused command with
`--stacktrace` succeeded without a source/cache change; its underlying cause was
not established. No cache cleanup was used for either tooling issue.

Artifacts:
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## Android tests and remaining parent QA

`ManagedArchivalAndroidTest` contains **five compiled, not executed** tests:

1. Real DocumentsProvider + isolated worker writes the full Unicode Markdown stream;
   pin/prune/delete cleanup preserves an unrelated document.
2. Connection deadline becomes retryable PAUSED state without a stranded RUNNING job.
3. Optional actual-volume test checks persisted read/write grants, MIME, complete
   content and removable targets (`archivalTreeUri` instrumentation argument).
4. Provider permission denial preserves private input, avoids unnecessary recovery
   scanning, and retries with a new generation.
5. Real parent supervisor force-stop kills a worker blocked inside a separate
   provider process, retaining parent PID and private input.

The debug-only `ArchiveTestDocumentsProvider` is absent from release sources. It
has opaque IDs and real ContentResolver/SAF calls, plus deterministic permission and
blocking faults. Use an isolated, model-free QA installation with a clean archival
queue. No emulator, device, or other app data was operated on during this work.

Parent runtime checks still needed:
- Run the compiled class on the parent's designated QA target; supply
  `-e archivalTreeUri <persisted-tree-URI>` for the optional real-volume case.
- Exercise the actual SimpleStorage picker and legacy permission path on API 26–28,
  current API folder grants, removal/revocation/restoration and provider full disk.
- Record/import while a large copy runs; confirm usable text and new Home recording
  are independent of archival completion. Check microphone entry points when app UI
  is absent and Android denies foreground acquisition.
- Test notification action, notification/channel denial with in-app control, Android
  timeout, process death around creation/receipt/publication and subsequent cleanup.
- Verify automatic Markdown with internal autosave off, manual-only text, model
  changes, audio-only deletion, retention, pins/Forever and older-folder links.
- Exercise actual multipart playback/seek, URI retranscription/share/export, narrow
  layouts/large fonts, and private-part unlink after all readers release.

Provider behavior and OEM foreground-service policy have not been runtime-certified.
The known incremental Compose compiler defect can recur on later source edits.
English resources await the normal translation batch. Encoding/quality/live-spool
remain the explicitly separate next milestone.

## Files in this milestone

New main files under `archive/`: `ArchivalCoordinator.kt`, `ArchivalJobStore.kt`,
`ArchivalPublication.kt`, `ArchivalSettings.kt`, `ArchivalFolderContract.kt`,
`ArchivalControls.kt`, `ArchiveSupervisorService.kt`, `ArchiveWorkerService.kt`,
`ArchiveWorkerClient.kt`, `ArchiveWire.kt`, `SafArtifacts.kt`,
`PendingArtifactName.kt`, `ManagedMarkdown.kt`, `ManagedArtifactCleanup.kt`.
Review additions: `ArchiveDisk.kt`, `ArchiveRecords.kt`, `ArchiveCatalog.kt`,
`ArchiveTargets.kt`, `ArchiveSummary.kt`, and `ManualMarkdownSave.kt`.
Lifecycle re-review additions: `ArchivalExecution.kt`, `ArchivalFailureBoundary.kt`
and JVM `ArchivalCoordinatorLifecycleTest.kt`; coordinator, worker-client and
journal boundaries were updated to use/complete these ownership contracts.

Also new: `history/AudioAsset.kt`, `res/values/archival_strings.xml`,
`app/src/debug/AndroidManifest.xml`, debug `ArchiveTestDocumentsProvider.kt`,
Android `ManagedArchivalAndroidTest.kt`, and the eight JVM suites listed above
(with scoped test-record helpers in `ArchiveTestRecords.kt`).

Modified main paths: `UtterlaneApp.kt`, `asr/MicrophoneSession.kt`,
`asr/TranscriptSource.kt`, `history/RecordingHistory.kt`,
`history/TranscriptHistory.kt`, `history/TranscriptMetadata.kt`,
`history/LinkedHistory.kt`, `history/HistoryExports.kt`, `home/HomeScreen.kt`,
`settings/SettingsActivity.kt`, `settings/SettingsRepository.kt`,
`transcribe/TranscriptionDialog.kt`, `transcribe/TranscriptionDialogModel.kt`,
`transcribe/DialogExitRetention.kt`, `transcribe/DialogAudioActions.kt`,
`transcribe/AudioPlaybackController.kt`, `transcribe/AudioPlaybackControls.kt`,
and `app/src/main/AndroidManifest.xml`.

This report is the only documentation file changed by this milestone. Existing
parent/import plans, QA reports, build configuration and unrelated edits remain.
