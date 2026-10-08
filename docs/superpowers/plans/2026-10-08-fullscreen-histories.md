# Branded full-screen histories implementation plan

> **For agentic workers:** Use executing-plans for inline implementation, with a
> local commit after each working milestone. The user approved the full-screen B
> design, automatic loading on scroll, and the app logo at the top.

**Goal:** Open Audio history and Transcript history as full-screen subpages with
the existing B-style rows and continuous, bounded-memory browsing.

**Architecture:** A shared, non-exported `HistoryActivity` selects the audio or text
repository through an explicit intent extra. A retained ViewModel owns an AndroidX
Paging stream; a chronological keyset index supports both append and prepend in
O(log n + page size), including refresh around the visible entry. Compose displays
only the loaded window, with the existing branded header and independent pin actions.

**Tech stack:** Kotlin, Compose/Material 3, AndroidX Paging 3.3.6, existing private
history stores and native Android instrumentation. Standard application identity:
`io.github.lrq3000.utterlane`, explicitly empty QA suffix during builds.

## Approved UI and navigation

- Rename the settings link and page title to **Audio history**; use **Transcript
  history** consistently for the text page.
- Reuse the real light/dark wordmark and header gradient. Add a 48 dp Back control,
  prominent page title, compact spacing, and a full-height lazy list. Preserve the
  B-style alternating rows, date grouping, transcript previews and audio durations.
- Scrolling near either end requests another page automatically. No Previous/Next
  buttons. Paging holds approximately three pages of decoded row previews, rather
  than accumulating entire transcript previews while browsing a large history.
- Whole-row taps open the existing detail dialog; pins consume their own taps.
  Deletion stays in the opened item. Return and activity recreation retain position.
- Retain loading, empty and retry states. A failed append retains the current rows.
  Pin changes invalidate data around the visible anchor without moving pins to top.
- Internal navigation must retain the current cleanup launch token. Legacy recovery
  intents enter the new audio page once; real external entry still requests cleanup.
- Back from an in-app history returns to its existing Settings instance. A root
  history entry has an explicit route back to Settings.

## Task 1: Efficient repository paging

**Files:** create `history/HistoryIndex.kt` and `history/HistoryIndexTest.kt`; modify
`history/RecordingHistory.kt`, `history/TranscriptHistory.kt`, and repository tests
under the corresponding main/test `io/github/lrq3000/utterlane` source directories.

- [x] Add failing tests for deterministic newest-first ordering with ID tie breaks,
  empty and exact-size boundaries, prepend, refresh around a deleted anchor, inserts
  between pages, and pin/deletion behavior with leased records.
- [x] Use this shared cursor contract:

  ```kotlin
  data class HistoryCursor(val created: Long, val id: String) : Comparable<HistoryCursor> {
      override fun compareTo(other: HistoryCursor): Int =
          other.created.compareTo(created).takeIf { it != 0 } ?: id.compareTo(other.id)
  }
  enum class HistoryDirection { REFRESH, APPEND, PREPEND }
  data class HistoryPage<T>(val entries: List<T>, val before: HistoryCursor?, val after: HistoryCursor?)
  ```

- [x] Back the visible index with `TreeMap<HistoryCursor, T>`. Append reads
  `tailMap(anchor, false)`; prepend reads `headMap(anchor, false).descendingMap()`
  and reverses the bounded result. Refresh includes the anchor and nearby entries,
  filling the window from the opposite side when near a boundary. Keys survive
  anchor deletion. Neighbor keys determine completion without an empty extra page.
- [x] Keep index updates inside existing repository synchronization. Exclude active
  or deleted entries from the visible index immediately while preserving storage
  leases. Keep legacy `list(page, pageSize)` for existing callers.
- [x] Run focused history tests and review the diff: 30 history/reader/recovery
  tests passed, including nine new cases. Commit the index milestone with this plan.

## Task 2: Full-screen presentation and navigation

**Files:** create `history/HistoryActivity.kt` and `history/HistoryViewModel.kt`;
update `history/HistoryScreen.kt`, `ui/BrandComponents.kt`,
`settings/SettingsActivity.kt`, `history/RecordingRecovery.kt`,
`app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, and default strings.

- [ ] Add failing Android navigation/geometry assertions against the current dialog.
- [ ] Add Paging runtime/Compose dependencies pinned to 3.3.6. Configure:

  ```kotlin
  PagingConfig(pageSize = 30, initialLoadSize = 30, prefetchDistance = 6,
      maxSize = 90, enablePlaceholders = false)
  ```

- [ ] Implement a keyset PagingSource and a cached ViewModel flow. Invalidate on
  repository revisions; refresh around `closestItemToPosition(anchorPosition)`.
  Read at most 160 transcript characters per row on IO, with a reader lease.
  Propagate cancellation and expose other load failures through Paging retry state.
- [ ] Extract the existing row/label rendering from its dialog shell. Use stable
  item keys, a retained lazy-list state, automatic append/prepend, accessible date
  headings and loading/error indicators. Preserve the existing retention/pin calls.
- [ ] Extend `BrandHeader` with optional title and Back action while preserving its
  Settings default. The full-page history uses normal app theme, language and insets.
- [ ] Register a standard, non-exported history Activity. Route Settings buttons
  and legacy recovery navigation to it. Opening history/details marks internal
  navigation; recreation must not reopen another copy or release Immediate holds.
- [ ] Rename audio-history wording, remove obsolete dialog-heading strings, and
  update the user guide. Keep new translations in the release-time batch policy.
- [ ] Build and run navigation/pin/recovery checks; commit the UI milestone.

## Task 3: End-to-end verification and delivery

- [ ] Create enough pinned fixture records to cross several pages in both histories.
  Scroll down beyond 90 rows and back up; verify no omissions/duplicates and bounded
  loaded previews. Open an older record, return, pin/unpin, and recreate the Activity.
- [ ] Verify full-screen window geometry, header logo, dark/light colors, Back
  navigation, empty histories, and legacy recovery entry. Record native screenshots
  under ignored build outputs; commit all test source and QA documentation.
- [ ] Adapt existing history tests to activity navigation. Test-only hosts that
  used the old recovery-dialog shortcut must use an appropriate explicit host.
- [ ] Run the full JVM suite and standard-package debug app/test builds:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q
  ```

  Resolve the new dependencies once online, then use `--offline` for subsequent
  incremental checks. Avoid clean/forced rebuilds or shared-daemon termination.
- [ ] Install with `adb -s emulator-5554 install -r`; verify the normal package ID
  and run focused history, recovery and host-regression instrumentation. Report
  commits, APK location and any remaining subjective visual review for the user.

## Scope and values review

The pages reuse the approved design and existing detail workflow. The extra paging
library earns its cost by handling bounded bidirectional loading and scroll state,
instead of adding an unbounded UI list or a bespoke pagination lifecycle. The
repository index keeps later pages efficient; no audio/transcript payload is loaded
wholesale. Input preservation, independent histories, pins, explicit deletion,
privacy, and genuine-entry cleanup rules remain requirements of the new navigation.
