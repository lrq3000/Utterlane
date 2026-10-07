# Independent history and dialog validation

This records incremental checks for the approved final lifecycle. It supersedes
the initial recovery-lifetime discussion in `recording-first-backpressure.md`.

## Storage milestone

- Reproduced two old-policy failures: retained recovery did not expire, and an
  explicit discard could reappear after a restart before its reader lease closed.
- Added shared ordered retention metadata, durable discard markers, permanent pins,
  fresh unpin timestamps and Immediate holds released only by a new user launch.
- Audio imports retain a private encoded copy; dismissing it does not affect the
  sender's original. A temporary dialog retains its source through successful retry.
- Transcript entries are independent, with stable per-attempt IDs for manual-save
  deduplication and distinct entries for distinct recognition attempts.
- Focused history/reader/recovery tests passed using normal incremental Gradle.
  A test-compilation timeout was diagnosed from the daemon log; the same bounded
  test selection completed with a longer timeout, without forced rebuilds.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*History*Test' --tests '*RecordingReaderTest' --tests '*RecordingRecoveryTest' "-PqaApplicationIdSuffix=.recordingfirst" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain -q --offline
```

## Policy and scheduling milestone

- Independent audio/text auto-save and duration preferences preserve legacy audio
  opt-out. Transcript defaults are off / 24 hours; manual saving remains independent.
- Audio/text periodic jobs use their finite durations. Immediate and Forever do not
  create polling jobs; unchanged schedules are not restarted on each opening.
- Only user-facing entry activities release persisted Immediate-unpin holds.
  Internal navigation and same-process activity recreation are excluded.
- Removed bulk pruning from history rendering and microphone completion. Startup
  and background requests share serialized, coalesced metadata-based cleanup.
- Focused scheduling, settings-transaction and recording-pipeline tests passed.

## Capture ownership milestone

- Reproduced recognition-only cancellation escaping the capture pipeline. It now
  becomes a recognition failure while capture/writing continue; whole-operation
  cancellation still stops capture and drains accepted writer blocks.
- Consumer-close failures cannot bypass audio finalization. Explicit Cancel records
  discard intent, while service destruction and model reset preserve recovery.
- Successful live transcription saves an independent text entry when enabled;
  Immediate text retention avoids creating a retained result.
- Focused pipeline, history-lifetime and transcript-finalization tests passed.
  An Android Cancel/discard regression is included for the integrated device run.

## Unified dialog milestone

- Sharing, audio/text history and recovery use one retained operation owner, with
  private encoded imports, same/other-model retries and bounded preview updates.
- Audio Save offers pinned/deduplicated history, an independent share snapshot,
  and streaming document/folder export. Text can be copied/shared or manually pinned.
- Recovery links carry recording IDs and model selection opens the actual picker.
  Original failure details survive restart. Closing a transcript viewer never
  deletes its independently owned source audio.
- App/test APKs built; focused JVM storage/pipeline tests passed. Seven Android
  dialog/capture tests passed, including explicit Cancel, failed retry followed by
  discard, imported-source ownership and idempotent pinned manual saving.
- The capture-panel test now waits for its requested state before asserting button
  visibility; asserting against the legitimate initial loading frame was a race.

## History UI milestone

- Audio and transcript auto-save/duration controls have their own Settings section.
  Both browsers share accessible outline/filled pin controls and bounded pagination.
- Opening an entry uses the shared dialog; metadata and pin state are displayed
  without reading whole transcripts. Duration decisions read persisted preferences.
- Two real-window Android tests passed: audio pin/unpin with an Immediate background
  cleanup check, and independent transcript pinning. Screenshots are recorded in
  the QA application's `files/onboarding-qa/` directory.
- Focused pin/settings JVM tests and debug app/test APK builds passed.

## Playback milestone

- A shared, owner-token-guarded MediaPlayer controller provides on-demand playback,
  audio focus/noisy-route pausing, independent read leases and rate-limited progress.
- Play expands to Pause/Stop and one seek timeline. Pause retains position, resume
  continues, and Stop/end reset and collapse. Drag seeks are submitted on release;
  native requests are coalesced while a seek is in flight.
- Hourly PCM parts use O(1) global-time addressing with Long durations; encoded
  imports use their original format and the native player's available duration.
- Two JVM timeline tests and three actual-player Android tests passed. Android
  coverage includes paused seeking, resume/stop controls, older-owner isolation,
  lease-protected deletion and Stop before preparation. A paused-seek screenshot
  is in the QA application's `files/onboarding-qa/` directory.
- Device fixtures use four seconds of silence: these tests establish player and
  control behavior, not a subjective assessment of acoustic fidelity.

## Integration and ownership review

- A model-preparation owner can derive a sequential reader from its existing lease
  even if normal retention became due meanwhile. New readers are still rejected.
- Restored dialogs persist audio ownership independently of whether the latest
  transcript attempt was saved. A transcript viewer cannot discard linked audio.
  Working-text loading/publication remains on IO and source-model provenance survives.
- Recovery notification cleanup uses indexed recovery counts. File export reports
  failed child creation instead of treating a directory URI as an output file.
- Full JVM suite: **288 tests passed**, zero failures/skips. App and test APKs built.
- Final non-native device batch: **21 tests passed** (22.225 seconds), including real
  AudioRecord capture during model failure, history launch grace, URI export,
  restored ownership, settings, recording panels and playback including EOF/replay.
- Two additional real-native tests passed separately. A cold ternary Parakeet model
  loaded after capture began and processed the complete nine-second public-domain
  sample while keeping text but deleting temporary audio. Two subsequent dialog
  attempts created distinct saved transcripts; closing removed only their temporary
  source audio. Tests took 14.760 and 23.249 seconds respectively, including setup;
  these are not isolated inference timings or physical-phone benchmarks.
- The native fixture/setup and recovery-test ideas were adapted from the parallel
  implementation at `fdee5c6`, with the approved retention and separate-text semantics.
- Screenshots wait for semantic assertions and a short compositor settling interval;
  no behavioral assertion relies on that screenshot-only delay.
- A final player regression reproduced an overestimated import duration (9 seconds
  of metadata for a real 4-second WAV). A positive prepared-player duration now
  replaces that estimate. Nine targeted player/dialog tests passed after the fix;
  **24 distinct targeted Android tests** passed across the focused batches.
- Import initialization no longer simultaneously reports that audio is unavailable
  or that recognition has already started; its progress label describes preparation.
- Explicit dismissal writes its disposition before waiting for recognition/export
  owners. Existing leases protect pending IO without leaving a crash window that
  would reclassify a deliberate dismissal as unfinished recovery.

## Defaults and high-refresh follow-up

- Transcript history now defaults on with the same 24-hour retention. The settings
  migration test first failed against the previous default, then passed; explicit
  saved opt-outs remain off.
- Added 30/60/90/200 Hz and changed the unset/default visual rate to 60 Hz, preserving
  explicitly saved rates. Reproduced unsupported/default-rate failures and the old
  100 ms waveform-tip delay before implementing the changes.
- Fixed history buckets retain their audio-time axis while an immutable provisional
  tip reflects fresh PCM. Integer scaled deadlines preserve fractional 60/90 Hz
  cadence without rounding drift or replaying missed updates.
- Extended deterministic publication-budget tests cover every selectable rate and
  exact input counts. Old snapshots remain immutable and repeated reads reuse them.
- Follow-up verification: **290 JVM tests passed**, debug app/test APKs built, and
  **10 relevant Android tests passed** (six Settings/panel tests and four playback
  tests). The Settings test selects all four added rates, including scrolling to
  200 Hz. Actual rendered frame rate remains limited by the display and fresh audio.

## Compact history design B follow-up

- Both list-interaction regressions first failed because the old lists exposed
  deletion buttons. Recording and transcript rows now open on the whole entry;
  their independent pin toggle does not navigate. Delete remains in the detail dialog.
- Applied compact grouped design B with the requested removal of visible retention
  captions and persistent pin-feedback text. Kept 48 dp pin targets and accessible
  checked/retention state, localized date grouping, short durations and bounded
  two-line transcript previews. Pin changes preserve item ordering.
- A compact dialog surface avoids the unused action/footer area of AlertDialog.
  Zero inherited tonal elevation preserves the intended white/light and navy/dark
  surfaces so alternating rows are visibly distinct. Pagination appears only when
  useful and retains space on smaller windows.
- **290 JVM tests passed** and app/test APKs built. **Eight targeted Android tests
  passed** after the final UI change, including pin launch grace, independent row
  navigation, absence of listing deletion/captions, and Settings behavior.
- Inspected native multi-entry audio/text screenshots and the dark text-history
  variant under `app/build/outputs/history-design-b-*.png`. These generated QA
  images and the earlier design mockups are local/ignored, not tracked artifacts.

## Waveform progression correction (2026-10-08)

- The fixed 100 ms buckets described above unintentionally changed scrolling speed
  and amplitude detail, beyond the requested publication-rate limit. The current
  waveform instead retains the last 64 nonempty microphone callback RMS levels,
  matching the pre-limiter implementation at `1237054`. Refresh frequency controls
  snapshot publication, not point insertion; displayed duration depends on block size.
- Three regressions failed on the old buckets before the correction: two short
  callbacks did not create independent points, RMS differed from the old algorithm,
  and low-rate publication contained the wrong history. The replacement tests cover
  rollover, mixed callback lengths, silence/PCM extrema, immutable snapshots and all
  nine refresh choices with 10/20/50 ms callbacks. Existing publication-budget and
  exact-input-count checks remain in place.
- **291 JVM tests passed**, zero failures/skips. An interrupted full run reported a
  local Gradle worker connection timeout; focused and full incremental runs then
  passed with `--max-workers=2`, without changing project build configuration.

## Reproduction commands

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.recordingfirst" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain -q --offline
adb -s emulator-5554 install -r "app/build/outputs/apk/debug/app-debug.apk"
adb -s emulator-5554 install -r "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb -s emulator-5554 shell am instrument -w -e onboardingTimeoutSeconds 15 -e class io.github.lrq3000.utterlane.RecordingFirstAndroidTest,io.github.lrq3000.utterlane.CapturePanelAndroidTest,io.github.lrq3000.utterlane.TranscriptionDialogAndroidTest,io.github.lrq3000.utterlane.HistoryPinsAndroidTest,io.github.lrq3000.utterlane.HistoryListInteractionAndroidTest,io.github.lrq3000.utterlane.AudioPlaybackAndroidTest,io.github.lrq3000.utterlane.RecordingSettingsAndroidTest io.github.lrq3000.utterlane.recordingfirst.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class io.github.lrq3000.utterlane.NativeHistoryAndroidTest io.github.lrq3000.utterlane.recordingfirst.test/androidx.test.runner.AndroidJUnitRunner
```

The native tests require the pinned Redux ternary GGUF already present at
`/sdcard/Download/parakeet-qa/parakeet-redux-0.6b-TQ1_Q8_0.gguf`; tests never download
weights. Native cases are also runnable individually to keep harness output bounded.
New strings remain English fallback strings pending the repository's release-time
translation batch. No release publication or physical-phone performance guarantee
is part of this verification.
