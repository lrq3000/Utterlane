# Blue Notebook native home implementation plan

> **For agentic workers:** Use the executing-plans workflow inline, in the isolated
> `.worktrees/d-home` worktree. Complete and verify each coherent milestone before
> committing it. The maintainer has approved implementation, a final squash merge
> to main using the self-contained context template, and pushing main.

**Goal:** Implement approved design D as the post-onboarding launcher, with useful
in-app recording/transcription and primary audio/text histories.

**Architecture:** Keep Android's existing capture, recognition, paging, independent
history retention, and recovery implementations. Extract shared waveform and text
presentation before using them on Home. A microphone foreground service owns Home
capture independently of activity recreation and navigation; the UI observes bounded
state. Settings remains an activity reached from the gear. Metadata survives deletion
of linked audio so transcript history stays independently useful.

**Tech stack:** Kotlin 2.0.21, native Android views for the shared waveform, Jetpack
Compose/Material 3, existing DataStore and Paging 3, JUnit and Android instrumentation.

## Approved design contract

- Preserve the final D reference in `docs/design/home-preview/`: pale-blue canvas,
  centered source-derived logo, transparent title row, a square local-audio-file
  action matching the title/subtitle height, rounded transcript surface, compact
  unboxed speaker-label switch, gradient waveform, and persistent primary navigation.
- Empty text instructions, in order: tap the waveform; or load an audio file with
  the matching inline icon; the transcript appears here. Loading is local.
- Idle waveform is flat and starts capture. The same control stops capture, displays
  measured processing progress/ETA, and returns to New recording afterward.
- Copy/share transfer the actual transcript, not only its bounded on-screen preview.
  Keep existing file-based sharing for large text. Preserve paging for long results.
- Speaker labels write the same application-wide preference as Settings. Optional
  setup must be understandable, and failed recognition must preserve recorded audio.
- History items have no generated titles. Audio uses a neutral recording label;
  transcripts preview the first nonblank line. Metadata is date, recorded time,
  duration, optional speaker labels, optional small pin indicator. No inline pin
  toggle. Existing save/pin operations and retention semantics remain available.
- History navigation stays outside scrolling content, retains each page's position,
  and does not cancel capture. File loading is offered on Record, not in history.

## Baseline and integration

- Latest fetched main: `54a3dca`; design commits rebased cleanly onto it.
- Implementation branch: `feat/blue-notebook-home`, initially `59a49e8`.
- Speed-study backup: `perf/diarization-speed-study` is already on origin at
  `a661b9c`; the requested push reported Everything up-to-date. Its committed report,
  CSV and test helpers are backed up; ignored private speech artifacts are excluded.
- Keep unrelated root-worktree recordings and all other worktrees intact.

## File responsibilities

| Area | Files / responsibility |
| --- | --- |
| Shared recording presentation | `ui/WaveformButton.kt`, `ui/RecordingPanel.kt`: extract native PCM drawing, theme support, flat idle state and click semantics; preserve existing overlay behavior |
| Shared result presentation | `transcribe/TranscriptContent.kt`, `transcribe/TranscriptionDialog.kt`: bounded scrollable text and full-store copy/share actions |
| Home ownership | `home/HomeRecording.kt`, `home/HomeRecordingService.kt`: service-backed microphone lifetime, state, result ownership, safe next recording and save operations |
| Home Android entry | `home/HomeActivity.kt`, `home/HomeScreen.kt`, `home/HomeNavigation.kt`: launcher/onboarding gate, permission request, document picker, D layout, primary destinations |
| Settings/entry lifecycle | `settings/AppEntryServices.kt`, `settings/SettingsActivity.kt`, `onboarding/OnboardingActivity.kt`: share foreground-service restoration and return from onboarding to Home |
| History presentation | `history/HistoryScreen.kt`, `history/HistoryViewModel.kt`, `history/HistoryActivity.kt`: D rows, persisted metadata, retained pagers, primary navigation |
| History data | `history/RecordingHistory.kt`, `history/TranscriptHistory.kt`: backward-compatible duration/speaker metadata and explicit temporary-result ownership |
| Capture integration | `asr/MicrophoneSession.kt`, `asr/MicrophoneSessionFactory.kt`, `asr/TranscriptionSession.kt`: expose current audio/result IDs and actual labeled output; retain Home audio until explicit dismissal |
| Dialog integration | `transcribe/TranscriptionDialogModel.kt`: save result metadata and retain detail-level save/pin behavior |
| Registration and copy | `AndroidManifest.xml`, `UtterlaneApp.kt`, `res/values/strings.xml`: new entry/service, one application-owned Home controller, localized English source strings |

## Task 1 — establish meaningful failing checks

- [ ] Prepare the existing pinned native sources/AAR for this worktree without
  editing shared source caches. Use JDK 21 and the installed SDK/NDK.
- [ ] Add JVM cases to the history tests for retained temporary microphone audio,
  independent transcript duration/speaker metadata after reload, and unchanged
  default dismissal/deletion behavior. Core expected behaviors:

  ```kotlin
  val recording = history.begin(HistoryRetention.NONE, automatic = false,
      keepUntilDismissed = true)
  recording.append(ShortArray(16000))
  recording.finish(false)
  assertTrue(history.get(recording.entry.id).temporary)
  assertTrue(history.get(recording.entry.id).part(0).isFile)
  history.dismiss(recording.entry.id)
  assertFalse(recording.entry.directory.exists())
  ```

- [ ] Add `HomeScreenAndroidTest`: inspect the real launcher intent, expect the
  `home_screen` accessibility tag, start from configured onboarding state, and
  verify tab/gear/local-file actions. Use existing `OnboardingTestUi` and private
  fixtures; restore preferences and remove only test-owned files.
- [ ] Observe the old launcher/UI and missing ownership contract fail before their
  implementation. Do not replace a failure with a mock of the expected UI.

## Task 2 — extract shared components

- [ ] Move the native `WaveformButton` out of `RecordingPanel.kt` into its own public
  file. Keep its drawing bounded to the incoming 64 samples and preserve the native
  button/ripple. Add an explicit idle presentation drawing a flat line. Both Home
  and the existing panel must instantiate this class.
- [ ] Extract transcript display and copy/share into `TranscriptContent.kt`. Copy
  must retain the existing IO/transfer limit contract:

  ```kotlin
  val text = withContext(Dispatchers.IO) { store?.readForTransfer() }
  if (text != null) clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
  else Toast.makeText(context, R.string.stream_use_export, Toast.LENGTH_LONG).show()
  ```

- [ ] Wire `TranscriptionDialog` to the extracted components. Preserve its playback,
  retries, audio export, full-file text sharing, paging, error and dismissal paths.
- [ ] Run focused transcript/store tests and existing waveform/panel instrumentation.
  Commit the verified extraction before Home consumes the components.

## Task 3 — preserve history metadata and current-result audio

- [ ] Add optional metadata with safe defaults for existing properties files:
  audio `speakerLabels`; transcript `durationMs` and `speakerLabels`. Allow transcript
  saving to retain the source recording timestamp separately from retention time.
- [ ] Capture actual speaker-output capability from `TranscriptionSession`, not the
  current Settings toggle. Save metadata from both microphone and file decoding.
- [ ] Extend `RecordingHistory.begin` with `keepUntilDismissed = false`; Home opts in.
  A completed temporary result remains available for explicit save/export, and
  unexpected interruption remains recoverable. Explicit dismissal still deletes it.
- [ ] Expose readonly microphone audio/result identifiers. Add defaulted factory
  options so Home can keep audio and show failures inline instead of automatically
  launching a recovery dialog over its screen.
- [ ] Verify old metadata loading, source deletion independence, pin/retention rules,
  retained current-result audio and explicit disposal. Commit this verified contract.

## Task 4 — implement Home ownership and UI

- [ ] Add one app-owned `HomeRecording` state owner. Its scope outlives activities;
  generation/session checks reject stale callbacks. Capture and inference continue
  across tabs/recreation; completed text stays bounded on screen and full on disk.
- [ ] Add `HomeRecordingService` with microphone foreground type, notification
  return/stop actions, immediate foreground promotion, and cleanup on service loss.
  Starting another operation must not release an active session or late callback.
- [ ] Add `HomeActivity` and launcher registration. Reuse the onboarding gate and
  cleanup launch markers. The gear launches Settings with internal-navigation intent
  metadata; the audio picker uses `OpenDocument` and the existing transcription path.
- [ ] Implement the D layout and fixed `Record / Audio history / Transcripts` bar.
  Reuse the same waveform and transcript components; retain separate history pagers
  and scroll state. Use the shared theme and original logo resources.
- [ ] Add the global speaker switch, optional-model setup explanation, actual
  capture/processing warnings, recovery/model-selection action and result actions.
  Saving audio/text must use existing independent repositories and retention choices.
- [ ] Share entry-service restoration with Settings. Onboarding completion returns
  new users to Home; replay keeps its Settings return behavior. Preserve legacy
  explicit Settings/recovery intents.
- [ ] Verify launcher, permission denial/retry, local picker, global preferences,
  busy-session rejection, recording through navigation, rotation, model failure,
  current-result preservation and a second recording. Commit the working Home flow.

## Task 5 — apply the history design

- [ ] Keep `HistoryViewModel`'s bounded bidirectional paging and IO preview reads.
  Project one nonblank preview line without reading an entire transcript. Use saved
  duration/speaker metadata and graceful unknown values for legacy records.
- [ ] Replace zebra/day-group/pin-toggle presentation with D's rounded surface,
  neutral audio label or first-line text preview, ordered metadata, small optional
  pin indicator and chevron. Keep retry/empty/error states and recovery indication.
- [ ] Share history content between Home tabs and legacy `HistoryActivity`. Preserve
  internal launch markers and navigation to the existing detail activity.
- [ ] Update current history UI tests for indicator-only rows, while preserving
  long-list navigation, return/recreation position and retention tests. Detail-level
  save/unpin actions must remain possible without adding a list pin button.
- [ ] Verify both histories with pinned/unpinned and speaker/plain fixtures, metadata
  after audio deletion, long lists, narrow layouts, larger text and dark mode.
  Commit the verified history presentation and associated tests.

## Task 6 — final verification and delivery

- [ ] Run focused tests incrementally during development. Final canonical build:

  ```text
  gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PqaApplicationIdSuffix=.dhome -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 --console=plain -q --offline
  ```

- [ ] Use the idle LDPlayer QA instance `emulator-5556` and isolated `.dhome` package;
  do not replace the regular app or another agent's package. Install with
  `adb -s emulator-5556 install --no-streaming -r <apk>` and run focused instrumentation
  classes using `am instrument -w -e class ...`. Use UI-tree-derived coordinates.
- [ ] Inspect native Record/history screenshots, actual launcher resolution and
  relevant app logs. Run representative real recognition using public bundled PCM
  and the existing source-pinned engine where the emulator models are available.
- [ ] Assemble the normal application identity with `-PqaApplicationIdSuffix=` after
  QA. Record evidence/limits in a committed QA note and update the user/development
  documentation and unreleased changelog.
- [ ] Fetch origin, verify base freshness, inspect every included commit and the full
  diff, then perform the explicitly requested squash merge into main. The squash
  message must include motivation, achieved purpose, exhaustive changes, technical
  rationale, verbatim included commit descriptions/authors, agentic stack and checks.
- [ ] Push main normally, verify the remote hash, and report the squash hash, APK,
  checks, any remaining limitations, and the separately backed-up performance branch.
