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
