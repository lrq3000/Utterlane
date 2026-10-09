# Blue Notebook Home / dialog D integration

## Baselines and ownership

- Original Home tip: `a84256378b57786d8ad2f6d77fe8047de3602b01`.
- Actual fetched main/rebase base: `d289940c0f345c9dc9dfcfa4e0f789726aa2ee3f`.
- All 34 Home commits were replayed on the unpublished local branch
  `integrate/d-home-latest`, worktree `.worktrees/d-home-latest`.
- Read main's full self-contained commit description,
  `docs/design/transcription-dialog-d.md`, `docs/qa/transcription-dialog-d.md`,
  and `CORE_VALUES.md` before integrating. Shared conflicts start from main's
  contracts; during rebase that is the **ours** side.

## Conflict/intent checklist

Each row accounts for a conflict hunk or a distinct intent within a combined hunk.
Original Home hashes identify the replayed changes, not new main commits.

| Original change / conflicting intent | Disposition | Resolution |
| --- | --- | --- |
| `7a1642a` / `TranscriptHistory.save` signature and metadata construction | Applied with adaptation | Add created/duration/actual-speaker fields inside main's `TranscriptSource.withActiveSource` publication lock. Keep `idForAttempt`, reverse index and discard rejection. Retention starts at save time. |
| `f2837fe` / microphone autosave/result ID | Applied with adaptation | Save `TranscriptMetadata` from drained writer samples, expose saved ID, attach provenance, and retain main's `TranscriptDiscardedException` handling. |
| `f2837fe` / dialog `transcriptPinned` state | Already implemented on main | Keep the existing field alongside main's byte count, deletion request and terminal state. |
| `f2837fe` / audio refresh and result metadata | Applied with adaptation | Keep `LinkedHistory.availableAudio`; preserve the independent metadata snapshot rather than replace main's availability rules. |
| `f2837fe` / retry guard | Applied with adaptation | Preserve all existing guards including `deleting`. |
| `f2837fe` / new attempt store exposure | Applied with adaptation | Attach main's source provenance, reset per-attempt metadata, then use main's `exposeStore` and pager publication. |
| `f2837fe` / dialog autosave | Applied with adaptation | Use the metadata-aware save inside main's discard-aware try/catch; preserve source-ID attachment and terminal text/byte publication. |
| `f2837fe` / public audio/text pin actions | Applied with adaptation | Keep main's `setPinned(DialogPinTarget, Boolean)` and scoped popup. Callers/tests use that API rather than restore obsolete setters. |
| `f2837fe` / text pin persistence and metadata | Applied with adaptation | Preserve source-discard checks and provenance updates; include independent result metadata in saved text. |
| `e11f607` / audio-menu Unpin and old button layout | Already implemented on main | Scoped D pin menu already provides audio/current-text/both Pin/Unpin. Keep the icon header and confirmation controls. |
| `e11f607` / shared reading and transfer controls | Applied with adaptation | Reuse main's continuous `TranscriptReader`/`TranscriptPager`; generalize reader input to a pager. Shared `TranscriptTransferActions(iconsOnly)` retains D icons/tags and Home buttons, both using leased full-store Copy and file Share. Remove obsolete preview-only `TranscriptText`. |
| `e44c9f1` / Keep after history expiry/deletion, stale Unpin | Applied with adaptation | Atomic `setPinnedIfPresent`; a missing saved entry may get a fresh explicit-Keep ID only while its working source remains active. Unpin creates nothing. Keep cannot override a confirmed source discard. |
| `35d9486` / appended Home resource strings | Applied | Retain both D and Home string blocks. |
| `976877a` / history detail navigation and unpin regression | Applied with adaptation | Keep passive row pins and internal-navigation assertion; exercise `dialog_delete` and D's scoped pin popup instead of old text buttons. |
| `976877a` / generic audio-history label | Applied | Retain the new label alongside every D string. |

### Semantic overlaps outside textual conflicts

- **Applied with adaptation — `a1f3b8e`, pending Keep vs Delete:** Git inserted
  an obsolete `delete` reference into main's parameterless dismissal. Remove
  that stale hunk. Main's confirmed deletion already joins pending saves and
  rechecks exact IDs. The adapted regression first confirms the old ID, pauses
  replacement Keep, requires renewed confirmation for the new ID, then verifies
  deletion of that replacement and survival of a sibling model result. Ordinary
  dismissal still preserves the pending pinned save.
- **Applied — metadata/restoration and recovery wording:** retain the public
  read-only model metadata, Bundle codec and Activity restoration, actual-label
  publication after committed append, and suppression of false interruption for
  successful temporary audio. Remove Home's now-redundant wording filter.
- **Applied with adaptation — legacy recovery association:** old Home journals
  stored IDs in `DialogInput` without `.source` files. Fill absent provenance from
  those IDs before exposing the store, without replacing existing sidecar values
  or clearing discard markers. Metadata from a linked saved entry remains usable.
- **Applied with adaptation — Home detail lifecycle:** replace `dismiss(delete)`
  with ordinary `dismiss()` and D's `onEmpty`. Only D's confirmed deletion API can
  delete history. Include deletion/checking in Home's busy state; release a
  finished empty owner and journal without broadening the deletion scope.
- **Applied with adaptation — live reading:** an app-owned `liveDocument` uses
  the shared pager until the dialog model owns the result. Publish complete
  `store.bytes` after committed input and refresh the live pager. Home's bounded
  reader follows new text during capture/processing without focus; complete
  results remain scrollable/seekable. D keeps its continuous reader and layout.
- **Applied — parent-pending fixes:** journal checkpoints use `model.metadata`,
  removing the local audio-revision heuristic. `has_created` distinguishes real
  epoch zero/negative timestamps from unknown; old zero still means unknown.

Main's `LinkedHistory`, reverse index maintenance, `TranscriptSource`, source-lock
stripes, discard markers, cross-session invalidation, confirmation UI, and
`TranscriptDocument`/paging algorithms are retained. Metadata adds constant-size
fields; linked saved-history queries remain O(k), cold recovery metadata lookup
remains O(n) on IO, and reader memory remains the existing nine-chunk window.

## Verification

- Focused Home/Transcript/LinkedHistory JVM run: **95 tests passed**.
- Final full command: **412 JVM tests, zero failures/ignored**, and Android test
  Kotlin compilation passed:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

- `git diff --check` passed. All original Home and main test classes remain.
  Updated old UI/API assertions; added a JVM explicit-Keep/discard/index regression
  and native cross-owner Keep/discard and journal-compatibility coverage.
- Two early Gradle attempts were blocked by daemon connection/shared journal
  cache contention (owner PID 22064). A normal retry succeeded; no foreign process
  or cache was modified. First Android-test compilation exposed two fixture
  adaptation errors; both were corrected before the successful full command.
- Read the prepared source-built sherpa AAR by copying it to this worktree's
  ignored `app/libs`; `.native-cache` is a junction to the prepared
  `diarization-speed-study` cache. No native preparation or APK tasks were run.

## Parent runtime handoff

Native execution is still pending. Run the adapted metadata/Keep-confirmation
tests, D geometry/pin/delete/long-reader tests, and combined Home navigation,
capture, retry, recovery and complete-reader flows. In particular inspect live
auto-follow/scrollbar behavior and the icon-only shared transfer row at 320 dp.
The native tests added here are compilation-verified, not device-verified.

The parent retains its two pending tests in stash
`e37cdba9d3cefe7b4acf0c7bacc207df2904dfe9` (epoch-zero journal and launcher).
This integration did not apply, pop, drop or alter that stash, access devices,
build APKs, push, merge main, or modify the root/parent worktrees.

Agentic stack: OpenCode with OpenAI GPT-6 Astra (openai/gpt-6-astra).

## Integration review follow-up: two-owner Keep publication

The review found a valid P1 in the initial integration: fresh Keep ID N could be
saved after another owner's fresh check of W but before its working-source marker.
N's later provenance attachment would then fail, leaving N pinned and outside W's
confirmed deletion. Joining only the deleting model's save job did not coordinate
the second owner.

- Extracted the actual Keep publication and confirmed-delete operations into
  `TranscriptHistory.saveWorking` and `LinkedHistory.confirmDeletion`. Two JVM
  regressions **failed before the fix**: marker-before-attachment and a pinned
  orphan after a real sidecar publication failure.
- Complete fresh Keep publication now holds the existing history monitor through
  source attachment. Confirmation uses that same monitor for fresh identity lookup,
  discard marking and exact-ID deletion. No coroutine suspension or producer join
  happens inside it; history-before-source lock ordering is preserved.
- A failed provenance attachment rolls back only the fresh ID owned by that
  incomplete Keep. A completed Keep instead receives renewed confirmation when
  its identity expands the selected scope; unconfirmed siblings remain untouched.
- The model reads durable working identity rather than another owner's stale
  in-memory provenance. Pinning an already-kept shared working file reuses that
  identity. Selection of the current store is captured before marking it discarded.
- **16 focused JVM tests passed** (including all three new publication races/error
  cases), and Android instrumentation Kotlin compiled. Added a two-real-ViewModel
  regression holding the keeper's store monitor between save and attach; native
  execution remains with the parent.

Focused command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*TranscriptKeepPublicationTest' --tests '*LinkedHistoryTest' --tests '*TranscriptRepinTest' :app:compileDebugAndroidTestKotlin "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
```

## Runtime fixture follow-up

The parent's prior combined APK run reported 34/37 passing Android tests. Both
reported source patterns remained in this integration:

- The invalid-file Home test observed `model != null && !busy`, which can match
  the explicit `importing=false` -> `running=true` handoff in the model's Main
  turn. The production `HomeServiceIdleStop.recheck` already yields and rechecks
  that handoff. The fixture now also waits for the expected terminal error message;
  its permission, owned-source, recovery and retry assertions remain intact.
- The legacy Settings links and fixed bottom navigation share visible labels.
  Add `settings_audio_history` / `settings_transcript_history` native tags to the
  existing section links. The two affected tests scroll to and click those tags,
  preserving Activity identity, Back, geometry and retained-anchor assertions.
  Navigation callbacks are unchanged. Runtime rerun remains with the parent.

## Borrowed Home detail ownership and final review checks

- Home's detail `onClose` now hides only the overlay, matching system Back's
  `onDismissRequest`. The Home owner, temporary source and journal survive this
  navigation. `onEmpty` after confirmed deletion and More -> Dismiss still clean
  up explicitly; external `TranscribeActivity` still dismisses its own model.
- Added `HomeDetailOwnershipAndroidTest`: import an invalid local WAV (no model
  weights required), await its terminal failure, open detail, close by its Back
  arrow and system Back, and assert the same model, unchanged owned bytes,
  temporary retention and journal. Finally use More -> Dismiss and await cleanup.
- Final review command passed **415 JVM tests, zero failures/ignored**, plus Android
  instrumentation Kotlin compilation:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

- The first compilation of the new ownership regression exposed Kotlin's inferred
  internal `HomeState` return type; declaring `runBlocking<Unit>` corrected the
  test signature before the successful final command.
- All review fixes use the same worktree/base; no further rebase was performed.
  Native two-owner publication, fixture reruns and Home overlay execution remain
  pending with the parent. The pending-test stash remains untouched.

## Restore-migration quality review follow-up

The subsequent review identified two valid defects in legacy Home association
fill: it copied the store constructor's stale provenance outside the publication
transaction, and a failed attachment leaked the newly constructed, unexposed
owner's text/provenance leases.

- `TranscriptHistory.migrateWorking(store, legacy)` now consumes the unexposed
  owner and reads durable provenance, fills only absent fields, and attaches the
  merged source under `withPublicationLock`, the same transaction used by Keep
  and confirmed deletion. A newer published identity is preserved, and a durable
  discard is rejected. Lock ordering remains history -> store -> source, with no
  source lock held while waiting for either outer monitor.
- Success returns the same live owner for immediate `exposeStore`; failure calls
  `keepForRecovery` in `finally`, releasing both leases without marking valid
  text discarded. Both restored working files and newly copied saved-history
  views use this ownership handoff.
- Added four controlled JVM cases in `TranscriptMigrationTest`. **Three failed
  before correction**: restore constructor W followed by another owner's complete
  Keep N (identity reversal), migration crossing a held confirmation transaction,
  and real sidecar-write failure retaining an unexposed lease. The fourth verifies
  successful missing-field fill and exactly one live owner's lease transfer.
- After correction, **20 focused JVM tests passed**, covering migration, Keep
  publication, repinning and linked scopes. The full run passed **419 JVM tests,
  zero failures/ignored**, and Android instrumentation Kotlin compiled:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests '*TranscriptMigrationTest' --tests '*TranscriptKeepPublicationTest' --tests '*TranscriptRepinTest' --tests '*LinkedHistoryTest' :app:compileDebugAndroidTestKotlin "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

This follow-up is additive on `e5b13af` for parent cherry-picking. The parent's
reapplied tests, worktree and stash were not touched; no APK, native build or device
operation was performed.

## Native fixture rerun on the parent's 0397643 target

The parent authorized test-APK-only installation on `emulator-5556` (API 28,
`.dhome`). Built `:app:assembleDebugAndroidTest` with
`-x :app:mergeDebugAndroidTestNativeLibs -PqaApplicationIdSuffix=.dhome` and the
usual offline/in-process/two-worker/quiet flags, then installed only
`app-debug-androidTest.apk`. The target's last-update timestamp remained
`2026-10-09 03:43:23`.

- `HomeFlowAndroidTest#eachHistoryRetainsItsOwnScrolledPositionAcrossTabsAndNewIntents`
  **passed** after reading bounds only once they remain unchanged for 500 ms.
  The original 2 px assertion is intact. Native log evidence: audio bounds
  `Rect(28, 530 - 1892, 656)` before/after and transcript bounds
  `Rect(28, 884 - 1892, 940)` before/after were exactly equal. Power inspection
  showed Awake/display ON/stay-on with a 2147483647 ms timeout, so no global
  power/UI modification or test keep-awake override was needed.
- `HistoryNavigationAndroidTest#legacyAudioTextAndRecordRoundTripRetainsBothAnchorsAndSettingsParent`
  **passed** with a fresh scoped recreation monitor and a wait for the old
  Activity's destruction. The launch monitor may still hold an old onResume.
  Both anchors remained exactly `Rect(28, 462 - 1892, 588)` across navigation
  and recreation; parent, launch-token and actual-new-instance assertions remain.
- The three-method batch ran in **44.941 s: 2 passed, 1 failed**. The remaining
  explicit-Keep/discard case still lost the actual working file despite a new
  external reader lease. Runtime logging exposed the input alias `/data/user/0/`
  versus the restored store's canonical `/data/data/` path. This is being
  investigated at the cache lease identity boundary rather than hidden by a weaker
  assertion or by leasing an unrelated fixture file.

### Confirmed cache-identity production defect

The explicit external reader still failed on the installed 0397643 target:
`Input=/data/user/0/io.github.lrq3000.utterlane.dhome/cache/transcripts/...`,
`working=/data/data/io.github.lrq3000.utterlane.dhome/cache/transcripts/...`.
Restoration canonicalizes the working path, while recovery scans use `cacheDir`'s
alias. `CacheArtifacts` indexed absolute path strings, so the aliased deletion
missed the canonical reader's count and physically removed both text and marker.

- The native fixture now explicitly leases `producer.model.state.value.store`
  and checks that store's file and discard marker. Its original restore input,
  logical deletion and no-repin assertions are preserved. The lease closes in
  `use`/finally, including on assertion failure.
- Added three JVM regressions for working text/sidecar deletion via another
  pathname, readers acquired through either alias, and pruning through an alias.
  **All three failed before the production fix.** Dot-segment aliases exercise
  canonical identity without requiring Windows symlink privileges.
- `CacheArtifacts.acquire`, `deleteWhenReleased`, and `prune` now resolve canonical
  identity before entering the registry monitor. Reader release and deferred
  deletion retain that captured identity. This unifies aliases without changing
  discard intent, clipboard limits, or history deletion scopes.
- **46 focused JVM tests passed**, followed by **422 full JVM tests, zero
  failures/ignored**, plus Android instrumentation Kotlin compilation:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests '*CacheArtifactIdentityTest' --tests '*Transcript*Test' :app:compileDebugAndroidTestKotlin "-PqaApplicationIdSuffix=.dhome" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin "-PqaApplicationIdSuffix=.dhome" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

The third native method is **not yet green**: the installed target still contains
the proven pre-fix lease registry. No target/app/native build or target install
was authorized or performed. The parent must rebuild/install the target with
this cache fix, then rerun the already-installed updated test method (or the same
three-method batch). The two navigation methods are native-green as recorded
above. No parent worktree, stash, or pending tests were touched.

## Preserve Home input across failed local-file replacement

The parent found that `serviceStarted` dismissed the existing result and cleared
its journal immediately after a URI was selected, before private copying could
succeed. A revoked, missing or empty provider could therefore destroy prior
temporary audio and unsaved text without producing replacement input.

### Ownership and foreground contracts

- Keep the visible `ResultOwner` and its journal while one bounded pending
  `ResultOwner` imports through the existing dialog model. No second import or
  second model is created when it is accepted.
- `HomeFileHandoff` observes only the candidate. On IO, accept a completed private
  nonempty audio copy or useful, committed, non-discarded text. URI availability,
  an allocated ID, partial copying, empty bytes, decoding success and model
  availability are not interchangeable acceptance evidence.
- On acceptance, checkpoint that candidate, move it into the visible slot, and
  publish its **current** model state together with `preparing=false` in one
  update. This retains the existing FGS import-to-running handoff. Only then
  retire the previous owner under ordinary dismissal semantics.
- On rejection, show the selection error while preserving prior model, text,
  audio and checkpoint. Keep preparation/busy until the failed candidate's
  cancellation and cleanup finish; then clear its bounded slot. Retire collectors
  on both success and cleanup failure using the shared owner-retirement path.
  Foreground-service failure/loss also retires an unaccepted candidate.
- Empty streams are rejected by `RecordingHistory.importAudio` before readiness
  publication or model preparation, and their private import directory is removed.
  A corrupt **nonempty** copied source is still accepted for retry even when
  decoding or ASR later fails. Existing history/provenance/scoped-delete rules
  continue to govern retained and explicitly dismissed data.
- While preparation holds the previous result on screen, show indeterminate
  progress instead of reusing the previous result's completed percentage.

### Verification and remaining native work

- Added 12 JVM cases across `HomeFileHandoffTest` and `EmptyAudioImportTest` for
  pending/missing/revoked/empty/partial/missing-payload input, useful text,
  discarded text, corrupt nonempty audio, stale/cancelled callbacks, bounded
  cleanup holds and atomic current-state publication. The initial nine checks
  failed under eager acceptance / the old empty-import contract before correction.
- Focused Home/import tests passed. Full offline verification passed **434 JVM
  tests, zero failures/ignored**, plus Android instrumentation Kotlin compilation:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

- Added `HomeFileReplacementAndroidTest#missingAndEmptyFilesPreservePriorOwnerAudioTextAndJournal`:
  start with a copied nonempty corrupt source, repeatedly select missing and
  empty files, and assert retained model/audio/store/preview/checkpoint plus no
  extra history entries. This fixture is compilation-verified; native execution
  and the parent's recognized-text reproduction remain pending after cherry-pick.
- No device, test-APK install, target/native build, parent worktree or stash
  operation was performed during this follow-up. The parent's new
  `HomeRecognitionAndroidTest` and `OnboardingRecognitionAndroidTest` were not
  touched. Candidate state is constant-sized; validation reads file metadata and
  bounded preview/provenance, never the full input into memory.

## File-promotion disposition race follow-up

Spec review found that the initial gate checked an earlier `audio` DTO and its
physical bytes, then promoted current model state later on Main. A cross-owner
deletion could leave those bytes leased/nonempty while the authoritative entry
was discarded and the candidate no longer had any usable input.

- Two controlled queued-dispatcher regressions **failed before correction**:
  deletion while a reader keeps the old ready source physically present, followed
  by (a) current state losing audio/text and (b) a not-yet-refreshed ready DTO.
  Both now preserve the prior workspace/checkpoint and reject promotion.
- `HomeFileHandoff` rereads current state inside the transcript publication guard
  on IO. Audio acceptance uses `RecordingHistory.withCompletedImport`, which
  checks the live index/deferred-discard set and nonempty owned payload while
  holding the same recording monitor as deletion. Text-only acceptance uses the
  existing working-source active/discard guard. A stale DTO/file cannot authorize
  replacement. Independently useful undiscarded text can still survive deletion
  of its audio and authorize acceptance.
- Promotion itself occurs synchronously before those guards are released:
  retire the prior visible ownership/observer, replace the journal, and publish
  the candidate plus its current running state with preparation released in one
  update. Observer installation and physical old-owner cleanup are queued on Main
  afterward. No storage guard waits for Main; no full-body input IO runs on Main.
- Lock order follows deletion: transcript publication -> recording index **or**
  working-source disposition -> a short Home ownership gate. The Home gate does
  only bounded state/journal publication and never acquires history/source locks.
  Audio lease acquisition remains outside this gate and outside text promotion.
- IO promotion makes stale controller callbacks relevant, so the retained result
  observer rechecks owner identity inside its atomic state update. Old-owner
  checkpoints check identity under the Home gate, and completion targets its
  captured owner rather than whichever owner is now visible. Rejecting a candidate
  atomically marks it retiring so a concurrent acceptance cannot win afterward.
- Additional deterministic tests block audio deletion and text discard at the
  actual promotion boundary and prove they cannot complete until publication.
  Coverage also checks independent text fallback and stale-observer publication.
- Focused Home/import/linked tests passed. Final verification passed **440 JVM
  tests, zero failures/ignored**, and Android instrumentation Kotlin compilation:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

No HomeScreen, TranscriptContent, parent recognition test, device, APK, native
build, parent worktree or stash changes were made in this follow-up. The parent's
successful real-ASR old-target reproduction and new-target native acceptance
remain parent-owned verification; this commit claims JVM/compile evidence only.

## Keep negative validation and terminal rejection on the same snapshot

Quality review found a second dispatcher boundary: IO could validate an importing
candidate with no source, then Main could resume after import and a fast decoding
failure had produced valid nonempty owned audio. Combining the old `false` result
with the new terminal flags wrongly rejected that retryable candidate.

- Guarded validation now returns the actual immutable state snapshot it inspected
  alongside its promotion result. Before rejecting, the observer repeats guarded
  IO validation if current state differs from that snapshot. Terminal flags and
  error text are read from the validated snapshot, never mixed across phases.
- Revalidation happens within the current observer iteration rather than waiting
  for another StateFlow emission. This also handles a change back to a value equal
  to the last emitted state, which StateFlow can conflate away.
- Added independently queued Main/IO regression coverage. The reported
  pending-import -> owned-nonempty-terminal-failure case **failed before the fix**
  and now accepts the same candidate for retry. A second case verifies an equal-
  state rebound still completes its correct terminal decision.
- Existing guarded atomic promotion, latest-running-state publication, bounded
  candidate cleanup and cancellation behavior remain covered. No guard waits for
  Main; repeated checks retain only one snapshot and one active observer.
- **26 focused JVM tests passed**, followed by **442 full JVM tests, zero
  failures/ignored**, and Android instrumentation Kotlin compilation with the
  same quiet offline full command recorded above. No UI files, parent worktree,
  recognition tests, APK/device/native build, or stash were touched.
