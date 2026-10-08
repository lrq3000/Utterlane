# Native transcription dialog D

## Approved behavior

Implement the browser design D from `design/transcription-dialog-mockups`
(`af0f01d`) on the current main baseline `cff686d`, including the full-screen
history work already merged there.

- Top row: Back, an auto-fitting Transcription title, retranscribe, audio
  download/share, and red outlined Delete. Keep one row at 320 dp with 48 dp
  targets; shrink only the title when necessary.
- Preserve audio retention/status, processing progress, recovery messages and
  optional statistics. Let the bordered transcript occupy remaining height.
- Show a persistent visible scrollbar. Continuous text scrolling replaces the
  page buttons, using bounded disk-backed chunks rather than loading whole files.
- Bottom row: playback and pin/unpin, copy, and transcript share. Preserve playback
  pause/resume/stop/seek, same/other-model retry, and the existing audio export menu.
- The pin toggles the current transcript's actual persisted retention. Unpinning
  respects the established retention duration and Immediate next-launch safeguard.

## Relationships and deletion scope

Saved transcripts already persist an `audioId`. Add a reverse index in
`TranscriptHistory`, derived from that authoritative metadata at initialization,
so audio-to-transcripts lookup costs O(k) for the k related entries, not O(n) over
all history. Maintain it on save/update/delete and keep independent lifetimes:
audio-only deletion must not cascade into transcripts. Retain source association
for working/recovered text as well, including live microphone results.

The user explicitly chose these scopes:

| Dialog origin | Transcript deletion target |
| --- | --- |
| Transcript history | Only the currently displayed transcript; preserve sibling versions |
| Audio history / new audio transcription | All transcripts linked to this recording, including the current working result |

Determine available audio and transcript targets on IO when Delete is tapped.
If both kinds exist, offer Audio / Transcript(s) / Both. If only one exists, skip
the choice menu. **Every path still requires a confirmation.**

- Single-transcript confirmation says "this transcript".
- Recording-origin confirmations explicitly say **all N linked transcripts**.
- The latter explain that individual versions can be deleted by opening them in
  **Transcript history**, so users can retain preferred model results.
- Snapshot the confirmed IDs. Recheck availability before execution; do not expand
  a confirmed deletion to newly created sibling transcripts. Keep unselected data.
- Finish or cancel this dialog's in-flight work before executing a confirmed
  deletion, so a pending save cannot recreate deleted results. Respect read/export
  leases and durable discard markers. Report errors and preserve surviving data.
- Imported originals outside private app storage are never deletion targets.

## Implementation milestones

1. **Relationships and deletion model.** Extend `history/TranscriptHistory.kt`;
   introduce a small history-level deletion planner/executor and unit tests for
   scope, multiple attempts, pruning/restart, leases, stale confirmations, and
   unrelated-entry preservation. Keep UI strings and Android dependencies out of
   this layer. Commit after focused history tests pass.
2. **Bounded document access and provenance.** Add a UTF-8-safe chunk reader and
   paging adapter. Preserve every byte across chunk boundaries and bound loaded
   text; record working-source metadata alongside recovery text. Test multibyte
   boundaries, growing text, empty files, and many chunks. Commit after checks.
3. **Dialog integration.** Update `TranscriptionDialogModel.kt`,
   `TranscriptionDialog.kt`, `AudioPlaybackControls.kt`, and `TranscribeActivity.kt`
   to use those contracts. Add English fallback strings under the repository's
   release-time translation policy. Add Android model/UI tests for both origins,
   both/single-availability paths, cancellation, pin toggling, and title/control
   geometry. Update tests that previously expected textual Delete controls.
4. **Native verification.** Build debug/app-test APKs, run the focused Android
   batch on an available emulator, inspect native screenshots in light/dark and
   narrow layouts, and record exact results/limits. Commit the verified milestone.

All implementation and checks run in `.worktrees/transcription-dialog-native-d`,
branch `feat/transcription-dialog-d`. Use an isolated `.dialogd` QA application ID
for emulator tests, keeping other agents' installed application data separate.
Native source caches and the pinned sherpa AAR may be read from existing local
artifacts; all build outputs remain in this worktree.

## Validation commands

JDK 21 and the configured Android SDK are required. Use normal incremental builds
with bounded output and workers:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*History*Test' --tests '*Transcript*Test' "-PqaApplicationIdSuffix=.dialogd" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.dialogd" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
```

Run new behavior tests before its implementation to establish failing evidence.
Use the existing `OnboardingTestUi` accessibility helper for native UI assertions.
Record device serial, actual test classes/counts, APK paths, and screenshots in the
final QA note. No cloud services, remote transcript transfer, or history policy
changes are part of this work.
