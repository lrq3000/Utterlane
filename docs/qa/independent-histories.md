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
