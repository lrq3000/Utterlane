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
