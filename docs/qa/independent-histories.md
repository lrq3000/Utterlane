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
