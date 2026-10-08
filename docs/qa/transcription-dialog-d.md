# Native dialog D verification

## Environment

- Worktree: `.worktrees/transcription-dialog-native-d`; branch:
  `feat/transcription-dialog-d`; main baseline: `cff686d`.
- JDK 21, Android SDK 36, normal incremental Gradle with two workers.
- LDPlayer API 28, serial `emulator-5554`, isolated QA identity
  `io.github.lrq3000.utterlane.dialogd`, version 2.1.0 / code 210.
- The pinned source caches and sherpa AAR are read through local junctions;
  build outputs and edits belong to this worktree.

## Initial native integration milestone

- **314 JVM tests passed**, including eight new linked-history cases and six
  Unicode/provenance cases. Tests cover independent audio/text lifetime,
  single-transcript versus all-linked scopes, exact confirmed IDs, leased deletion,
  history restart, missing payloads, UTF-8 chunk reconstruction, working-result
  recovery metadata, and provenance expiry relative to the last text write.
- **25 Android tests passed** in the final integration batch (22.510 seconds):
  11 `TranscriptionDialogDAndroidTest`, five `TranscriptionDialogAndroidTest`, four
  `AudioPlaybackAndroidTest`, three `HistoryPinsAndroidTest`, and two
  `HistoryListInteractionAndroidTest`.
- App and instrumentation APK builds passed. Installation used `--no-streaming`
  and `-r` on the isolated QA identity.

### Reproductions and fixes

- The original dialog failed to expose existing text when opened from audio
  history. It also failed to restore an unsaved working transcript's audio link.
  New native tests demonstrated both failures before model integration.
- The old UI lacked D's pin control and layout. The native control test failed
  before the new layout was implemented.
- A 4,000-paragraph transcript exposed a scrollbar seek problem. Paging now
  supports direct jumps, and seeking waits for the actual loaded chunk rather
  than using a placeholder's height. The same test reaches the final chunk and
  returns to the first without page buttons or traversing every intermediate page.
- Icon labels are attached to the actionable controls themselves. The playback
  regression waits for the accessible Resume label rather than visible button
  text, and real playback pause/seek/resume/stop tests pass.

### Deletion and UI evidence

- Transcript-origin deletion removes only the selected version, including when
  audio is deleted alongside it. Sibling model results remain readable.
- Recording-origin deletion confirms **all N linked transcripts**, and its help
  text directs users to Transcript history to remove individual versions.
- Audio-only and text-only availability skip the choice menu but still require
  confirmation. Cancel preserves data.
- Creating a third sibling while a two-result confirmation is open causes a new
  three-result confirmation; no files are removed on the stale confirmation.
- Native 320/392 dp window tests verify a single header row, a fitting title, and
  full 48 dp icon targets. Light and dark screenshots were visually inspected.
- Local/ignored evidence: `app/build/outputs/transcription-dialog-d.png`,
  `transcription-dialog-d-320-light.png`, and `transcription-dialog-d-dark.png`.

## Commands

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.dialogd" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
adb -s emulator-5554 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e onboardingTimeoutSeconds 20 -e class io.github.lrq3000.utterlane.TranscriptionDialogDAndroidTest,io.github.lrq3000.utterlane.TranscriptionDialogAndroidTest,io.github.lrq3000.utterlane.AudioPlaybackAndroidTest,io.github.lrq3000.utterlane.HistoryPinsAndroidTest,io.github.lrq3000.utterlane.HistoryListInteractionAndroidTest io.github.lrq3000.utterlane.dialogd.test/androidx.test.runner.AndroidJUnitRunner
```

New strings use English fallback pending the repository's stable release-time
translation batch. These checks exercise native controls, storage, recovery, and
playback; they are not new speech-recognition accuracy or device-performance
benchmarks. The user's subsequent scoped pin-menu request is a separate follow-up
to this initial integration milestone.

## Recovery review follow-up

Two review findings were reproduced with native tests before correction:

- Deleting all linked transcripts while keeping audio left a retired working
  recovery copy readable. Deletion now removes every tracked copy whose identity
  was confirmed, while single-transcript deletion preserves unselected versions.
  Unsaved earlier attempts are included in recording-origin confirmation counts.
- A failed attempt could leave the last committed text unpublished. Terminal
  success/failure/cancellation now refreshes the surviving text and byte count.

A JVM regression additionally proved that an export lease could keep an explicitly
deleted working file reopenable. A durable source-metadata discard marker now
blocks recovery immediately; startup cleanup completes its physical removal after
a restart. Active exports retain their existing leases. Recovery availability IO
runs off the main thread.

The full JVM suite passed **315 tests** after these changes, and both new targeted
native regression tests passed. The provenance test also simulates a fresh-process
registry using a copied, tombstoned artifact and verifies cleanup independently of
the normal cache age.

## Scoped pin submenu

The pin now opens the same Material dropdown component as retranscription. It
offers available audio/current-transcript/both targets, with Pin or Unpin labels
matching persisted state. Mixed states offer Pin both; both-pinned offers Unpin
both. Pinning never includes sibling model versions. The icon is filled when any
available item is pinned, and its accessible state identifies which kind.

- The menu-absence regression failed before implementation.
- Independent pin/unpin, mixed-to-both pinning, unpin-both, sibling preservation,
  and audio-only/text-only menus passed native tests.
- A stale Unpin after external transcript removal initially created a replacement
  saved entry. The regression now passes: Unpin does not create saved data.
- Final scoped-pin batch: **315 JVM tests and 30 Android tests passed**. The Android
  batch contains 16 D-specific tests plus the same 14 existing regressions.
- The native submenu screenshot was inspected at
  `app/build/outputs/transcription-dialog-d-pin-menu.png` (local/ignored).

## Cross-session recovery verification

The follow-up review identified an older-session recovery copy outside the current
ViewModel's tracked files. Two native regressions reproduced that copy surviving
deletion and an unsaved linked recovery result being absent from audio history's
detail view. Both now pass:

- Confirmed IDs invalidate matching copies across the private transcript-cache
  directory before their history entries are removed, preserving other versions.
- Recording-origin availability/counts include linked unsaved recovery results;
  the latest recovery text is shown when no saved transcript is available.
- Provenance updates are serialized and cannot clear a durable discard marker;
  a late-producer JVM regression verifies that boundary.

Saved-history relationship lookup remains indexed O(k). Recovery lookup is an
IO-only metadata scan of the retained working-cache directory on these cold paths,
not a transcript-body read or a per-frame scan. This avoids adding a second mutable
global cache index while covering files from prior processes. The dialog reports
"Checking available items…" during availability checks.

The final cross-session batch passed **315 JVM tests and 32 Android tests**,
including 18 D-specific cases and 14 existing dialog/playback/history regressions.
