# Shared import-retention milestone — 2026-10-10

Status: **DONE_WITH_CONCERNS**, ready for controller re-review. All **519 JVM tests
pass**, including the P1/P2 review regressions below. The earlier
controller-authorized targeted non-incremental debug Kotlin recompilation resolved
the generated-code mismatch without an app-source fix for that tooling issue. The
review corrections do change application source, and passed using the regular
incremental default. Debug and Android-test APK builds pass. No device tests were
run; the compiler workaround and verification boundaries are recorded below.

Workspace: `C:\git\TranSlander\.worktrees\progressive-archival`, branch
`feat/progressive-archival`, base `f6975ce9dbc1565325ab461e2ec680a2e7b12018`.
The parent-owned toolchain/documentation changes were preserved. No clean, blanket
forced rerun, manual cache deletion, commit, push, or worktree creation was
performed. The contained follow-up used the explicitly authorized
`-Pkotlin.incremental=false` exception and preserved native outputs, shared sources,
other worktrees, and shared caches.

## Implemented contract

- Home, external Share/Open, and monitor requests continue through the same dialog
  model and source-copy path. Metadata inspection is separate and off Main, before
  the retention question or large copy. The external original is never mutated.
- Strict thresholds: duration greater than **600000 ms** wins; otherwise size
  greater than **52428800 bytes** selects the size question. Unknown facts remain
  nullable. Disabled history never asks. Duration and size have independent
  remembered KEEP/DISCARD choices, with a settings reset.
- One shared Compose question offers Keep audio, Transcript only, Cancel, and the
  exact checkbox text `Remember my choice and do not ask again`.
- Home renders its pending candidate's question while preparing, without replacing
  the prior result or journal. Cancellation and invalid/empty/failed source copies
  do not authorize the existing ownership handoff.
- Kept copies are ordinary unpinned history entries. The repository publishes a
  protected lease atomically with the completed nonempty copy, then the model
  transfers protection to its existing dialog owner. Active model preparation and
  ASR also hold pruning protection. Normal retention applies after release;
  pending recovery is not an indefinite pin for ordinary saved audio.
- Transcript-only/history-off imports remain temporary. Each processing attempt
  has its own durable token. Success acknowledges only that token; recovery clears
  after all obligations settle, and the last lease release removes the private copy.
  Initialization also cleans successful temporary copies left by process death.
  Closing one view does not discard input still leased by another owner.
- Failed or uncommitted temporary imports remain recoverable. Their snapshotted
  DISCARD choice cannot be turned into an automatic permanent save by later global
  settings. Existing exit confirmation and explicit pin behavior remain operative.
- Successful ordinary imports do not acquire recovered provenance. Actual failure
  or restart with uncommitted input does. Legacy direct `importAudio` callers keep
  their previous temporary-until-dismissed semantics.
- Original bytes are copied unchanged. Track MIME determines compression metadata;
  an extension alone is never used to classify a codec. The bounded decoder is
  unchanged. No storage-folder, encoder, or archival-service feature was added.
- A provider-close failure after copy publication releases the acquired import
  lease before propagating the error. Completed private bytes remain available for
  recovery; the failed caller never strands pruning/deletion protection.

## Verification

All Gradle commands ran from the worktree root with a **600000 ms tool timeout**.
The common arguments were:

```powershell
"-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

### Spec-review corrections P1/P2 — latest verification

**P1: attempt-aware processing obligations**

- `beginProcessing` atomically persists a unique token before model preparation.
  `ProcessingAttempt.complete()` removes only its own token. Failure or close
  without completion leaves that token pending; coroutine cancellation closes the
  handle on non-cancellable IO. A sibling's success cannot acknowledge it.
- Initialization restores the token set independently of runtime leases and
  treats any surviving token as a recovery obligation, even if an older boolean
  flag is false. Successful temporary cleanup requires an empty token set.
- A new retry atomically replaces only non-live obligations with its own token.
  Other live attempts keep their tokens. This transfers responsibility for failed
  or process-abandoned work without a crash gap or an ever-growing retry ledger.
- Normal exit/dismissal of retained audio and legacy untracked completion cannot
  clear tracked obligations. Explicit deletion still wins against late callbacks.
- Nine repository tests cover A-start/B-start/A-success/restart, failure and
  cancellation both before and after sibling success, all-success/last-lease
  cleanup, restart recovery/retry, live sibling exclusion, repeated retries and
  stale callbacks, explicit deletion, legacy calls, and failed metadata writes.

**P2: source-close ownership handoff**

- Extracted the actual provider-independent copy/close path to
  `transcribe/ImportedAudioCopy.kt`; `DialogAudioActions.import` calls it directly.
- The helper holds the published `OwnedImport` locally until provider close
  succeeds. If close throws, it releases the lease, retains the original error,
  and preserves complete input for recovery. Secondary cleanup errors are
  suppressed rather than replacing the original failure.
- Three tests execute this real helper with the real repository and throwing
  streams: post-publication close failure for both retention choices, successful
  ownership transfer, and read-plus-close failure with correct error suppression.
  The failing-close test verifies actual pruning/physical deletion, not a mock
  lease counter.

Exact review commands (all use a 600000 ms tool timeout):

```powershell
# P1 RED: 7 tests, 5 assertion failures with unscoped completion behavior.
.\gradlew.bat :app:testDebugUnitTest --tests "*ProcessingObligationTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

# P2 RED: 3 tests, 1 assertion failure exposing the stranded lease.
.\gradlew.bat :app:testDebugUnitTest --tests "*ImportedAudioCopyTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

# Final focused verification: 68 passed, zero failures/skips, 2.350 s test time.
.\gradlew.bat :app:testDebugUnitTest --tests "*ProcessingObligationTest" --tests "*ImportedAudioCopyTest" --tests "*ImportRetention*Test" --tests "*HomeFileHandoffTest" --tests "*HistoryExitPolicyTest" --tests "*HistoryPinsTest" --tests "*EmptyAudioImportTest" --tests "*RecordingPipelineTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

# One full suite/build run after both fixes. Existing native outputs are reused.
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -x ":app:configureCMakeDebug[arm64-v8a]" -x ":app:buildCMakeDebug[arm64-v8a][utterlane_crisp]" -x ":transcribe-native:configureCMakeDebug[arm64-v8a]" -x ":transcribe-native:buildCMakeDebug[arm64-v8a][utterlane_transcribe]"
```

Final result: **519 tests passed, zero failures/skips**, 4.684 s test time;
**BUILD SUCCESSFUL in 18s**, 97 tasks (7 executed, 90 up-to-date). Android-test
Kotlin compilation and app dex/packaging executed; the Android-test APK remained
up-to-date. The four native configure/build tasks were excluded, with native
merge/copy/strip outputs reused. No non-incremental workaround, clean, or forced
rerun was needed for these review corrections. This adds 12 tests to the previous
507-test result, for 33 new JVM tests over the supplied 486-test baseline.

### TDD / focused runs

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ImportRetentionPolicyTest" --tests "*ImportRetentionLifecycleTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

.\gradlew.bat :app:testDebugUnitTest --tests "*ImportRetentionPolicyTest" --tests "*ImportRetentionLifecycleTest" --tests "*HistoryExitPolicyTest" --tests "*HistoryPinsTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

.\gradlew.bat :app:testDebugUnitTest --tests "*ImportRetentionGateTest" --tests "*ImportRetentionPreferencesTest" --tests "*HomeFileHandoffTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

.\gradlew.bat :app:testDebugUnitTest --tests "*ImportRetentionLifecycleTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

.\gradlew.bat :app:testDebugUnitTest --tests "*ImportRetention*Test" --tests "*HomeFileHandoffTest" --tests "*HistoryExitPolicyTest" --tests "*HistoryPinsTest" --tests "*EmptyAudioImportTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

- Initial policy/lifecycle RED: **14 tests, 11 assertion failures** against the
  legacy-behavior API scaffold. After implementation, those tests and existing
  exit/pin tests passed (**22 tests**).
- Preference/gate RED: **24 tests, 5 assertion failures** before persistence and
  question waiting were implemented.
- Additional lifecycle RED runs each caught one assertion failure: premature
  disposal while another owner remained, then bypassing ordinary expiry for a
  pending retained import. Both behaviors were corrected.
- One focused compilation attempt caught a wrong Cancel resource identifier; the
  shared existing `action_cancel` resource corrected it.
- Final focused run: **48 tests, zero failures/skips**, 1.580 s test time,
  `BUILD SUCCESSFUL in 4s`.

### Full suite and builds

The requested combined command was executed once:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

Initial result: **507 JVM tests, 499 passed, 8 failed**. All failures were in
`RecordingPipelineTest`; Gradle stopped before the two assembly targets.
At that stage there were 21 additional JVM tests over the supplied 486-test baseline.

The remaining assembly targets were then run once:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

Result: **BUILD SUCCESSFUL in 23s**, 95 tasks (13 executed, 82 up-to-date).
`compileDebugAndroidTestKotlin` executed, including the two new real-activity
integration tests for external Open/Share and Home pending-candidate prompts,
remembered choices, cancellation, and actual PCM track inspection.
Existing native tests that require temporary imports now explicitly disable audio
history and restore the prior setting. These tests were **compiled, not run**.
Existing/new native test deprecation and unnecessary-assertion warnings remain.

Artifacts:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/test-results/testDebugUnitTest/TEST-io.github.lrq3000.utterlane.asr.RecordingPipelineTest.xml`
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

### Generated-code mismatch — resolved by targeted recompilation

First failure:

```text
java.lang.NoSuchFieldError: Class io.github.lrq3000.utterlane.history.RecordingHistory$Recording
does not have member field 'int $stable'
    at io.github.lrq3000.utterlane.asr.RecordingPipeline.<clinit>(RecordingPipeline.kt)
```

The other failures cascade from this static initialization error (one is wrapped
by the existing cancellation-outcome assertion). `RecordingPipeline.kt` and its
tests were not changed. Bytecode inspection used:

```powershell
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -classpath "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes" 'io.github.lrq3000.utterlane.history.RecordingHistory$Recording'
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -c -classpath "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes" 'io.github.lrq3000.utterlane.asr.RecordingPipeline'
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -classpath "app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes" 'io.github.lrq3000.utterlane.history.RecordingHistory$Recording'
```

Before the workaround: debug `Recording` had no `$stable` field, while the debug
pipeline contained a `GETSTATIC RecordingHistory$Recording.$stable` instruction.
The earlier parent-built release `Recording` did contain that field. Work initially
paused at the explicit no-clean/no-forced-rerun constraint. The controller then authorized
a contained non-incremental debug Kotlin recompilation to test the diagnosis.

Exact workaround and verification, in order:

```powershell
.\gradlew.bat :app:compileDebugKotlin "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

# Inspect the regenerated callee and caller before rerunning tests.
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -classpath "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes" 'io.github.lrq3000.utterlane.history.RecordingHistory$Recording'
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -c -classpath "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes" 'io.github.lrq3000.utterlane.asr.RecordingPipeline'

.\gradlew.bat :app:testDebugUnitTest --tests "io.github.lrq3000.utterlane.asr.RecordingPipelineTest" "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

.\gradlew.bat :app:testDebugUnitTest "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

# Refresh the APK from corrected bytecode, reusing existing native outputs.
.\gradlew.bat :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -x ":app:configureCMakeDebug[arm64-v8a]" -x ":app:buildCMakeDebug[arm64-v8a][utterlane_crisp]" -x ":transcribe-native:configureCMakeDebug[arm64-v8a]" -x ":transcribe-native:buildCMakeDebug[arm64-v8a][utterlane_transcribe]"
```

Results:

- `:app:compileDebugKotlin` actually executed, **BUILD SUCCESSFUL in 20s**,
  17 tasks (2 executed, 15 up-to-date). No init script, output deletion, or
  `--rerun-tasks` was needed. No native configure/build tasks were in this graph.
- Repeating the same bytecode inspections now shows
  `public static final int $stable;` on `RecordingHistory$Recording`. The pipeline's
  `GETSTATIC RecordingHistory$Recording.$stable` instruction remains and now
  resolves to the regenerated field.
- Focused `RecordingPipelineTest`: **8 passed, zero failures/errors/skips**,
  1.114 s test time, **BUILD SUCCESSFUL in 14s**. The app compiler was up-to-date;
  the JVM-test Kotlin compiler executed as needed.
- Full JVM verification: **507 passed, zero failures/skips**, 4.973 s test time,
  **BUILD SUCCESSFUL in 7s**. Both Kotlin compiler tasks were up-to-date.
- APK follow-up: **BUILD SUCCESSFUL in 9s**, 91 tasks (5 executed, 86 up-to-date).
  App dex/packaging executed with the corrected classes. Android-test Kotlin
  compilation executed; its resulting dex and APK remained up-to-date. All four
  native configure/build tasks were explicitly excluded; native merge/copy/strip
  tasks remained up-to-date.

This confirms a **local incremental Compose/Kotlin generated-code mismatch**:
the same source produces a compatible field/caller pair and passes the previously
failing tests when compiled non-incrementally. It is a build workaround, **not an
application source fix**. No source was edited to invalidate compilation, and the
project's default incremental setting was not changed. The precise upstream
compiler invalidation defect has not been reduced to a standalone reproducer;
future incremental source edits may encounter it again.

## Remaining concerns and retention boundaries

- Native UI, provider metadata behavior, Activity recreation, and real on-device
  ASR were not executed. The two new UI integration tests compile, and the relevant
  pure policy, ownership, persistence, and exit-policy JVM tests pass.
- Normal retained imports remain subject to the user's existing expiry setting
  after active ownership ends, including uncommitted input recovered after process
  loss. Recovery is not an implicit permanent pin. Transcript-only/history-off
  failures instead remain temporary and require the existing explicit disposition.
- Providers can omit duration, byte size, or track metadata. Missing facts stay
  unknown; no duration/size warning or compression claim is invented from a name.
- Next-milestone callers must preserve the protected-lease handoff and the actual
  processing commit boundary, completing their own `ProcessingAttempt` rather than
  using legacy untracked completion. Archival completion alone must not clear
  pending ASR recovery or release audio still owned by another consumer.
- After a provider-close failure the completed private copy may remain a recovery
  entry according to its retention choice. Releasing the stranded lease does not
  silently delete recoverable input; ordinary pruning or explicit disposition
  remains authoritative.
- The demonstrated incremental compiler failure remains a tooling concern. The
  exact targeted workaround above avoids cleaning/rebuilding native dependencies.
- English-only strings intentionally await the normal translation batch.

## APIs and durable fields for the next milestone

- `ImportRetentionPolicy.decide(durationMs: Long?, sourceBytes: Long?, preferences)`
  returns `ImportRetentionDecision.Use(choice)` or `.Ask(category)`.
  Constants: `DURATION_THRESHOLD_MS`, `SIZE_THRESHOLD_BYTES`.
- `ImportRetentionChoice`: `KEEP`, `DISCARD`.
  `ImportRetentionCategory`: `DURATION`, `SIZE`.
  `ImportRetentionPreferences(audioHistoryEnabled, durationChoice, sizeChoice)`.
- `AudioImportMetadata(displayName, mimeType, durationMs, sourceBytes,
  trackMimeType, isCompressed)`: every field is nullable. `mimeType` describes the
  provider/container; `trackMimeType` and `isCompressed` come from the actual track.
  Use the nullable duration here rather than interpreting the legacy display
  `HistoryEntry.importedDurationMs == 0` as known metadata.
- `HistoryEntry.importRetention: ImportRetentionChoice?` (null means legacy),
  `importMetadata: AudioImportMetadata?`, `processingAttempts: Set<String>`, and
  `completedTransientImport` (which also requires no processing obligations).
  Properties persisted: `importRetention`, `importMetadata`, `importName`,
  `importMime`, `importDurationMs`, `importBytes`, `importTrackMime`,
  `importCompressed`, and comma-separated UUID tokens in `processingAttempts`.
  Existing status/recovery/recovered fields retain their roles; initialization
  derives pending recovery from either the legacy flag or a nonempty token set.
- `RecordingHistory.importAudioOwned(input, extension, metadata, retention)` returns
  `OwnedImport(entry, lease)`. Transfer/close the protected lease on IO before
  crossing a cancellable dispatch boundary. Legacy `importAudio(...)` is unchanged
  at its public call site.
- `RecordingHistory.beginProcessing(id): ProcessingAttempt` durably registers the
  attempt and transfers responsibility for abandoned/failed obligations at start.
  The handle exposes `audioId`, `token`, `complete()`, `fail(message, kind)`, and
  `close()`. Complete only at the actual ASR/text-commit boundary. Always close in
  `finally` (non-cancellable IO when using coroutines); incomplete close records
  interruption. Terminal callbacks are idempotent and cannot revive deleted data.
  Runtime live-owner membership prevents taking over a sibling's active token;
  after restart all persisted tokens are abandoned and eligible for retry takeover.
- Legacy `completeRecovery(id, retention)` remains available for untracked direct
  callers, but deliberately cannot settle any tracked token. The shared dialog
  model now uses its own attempt handle for success/failure/cancellation.
- `HistoryExitPolicy.keeps(HistoryEntry, now)` and
  `requiresConfirmation(HistoryEntry, now)` respect persisted import intent.
- `DialogAudioActions.inspect(uri, path)` returns
  `InspectedSource(uri, metadata)`; `import(source, retention)` returns the owned
  publication. Both are blocking IO APIs.
- Internal `copyImportedAudio(history, source, extension, metadata, retention)` is
  the shared JVM-testable copy/provider-close/lease-transfer boundary. It closes
  the provider and returns a protected owned copy, or releases that acquired lease
  before propagating a post-publication close failure.
- `SettingsRepository.importRetentionPreferences`,
  `rememberImportRetention(category, choice)`, `resetImportRetentionChoices()`.
  Durable preference keys: `import_retention_duration`, `import_retention_size`.
- `ImportRetentionRequest(category, metadata)`;
  `TranscriptionDialogState.importQuestion` / `importCancelled`;
  `TranscriptionDialogModel.chooseImportRetention(choice, remember)` / `cancelImport()`.
  Internal `HomeState.pendingImport` exposes only the waiting candidate's question.

## Files in this milestone

Under `app/src/main/java/io/github/lrq3000/utterlane/`:

- New: `history/ImportRetentionPolicy.kt`, `transcribe/ImportRetentionGate.kt`,
  `transcribe/ImportRetentionQuestion.kt`, `transcribe/ImportedAudioCopy.kt`.
- Modified: `history/RecordingHistory.kt`, `history/HistoryExitPolicy.kt`,
  `history/HistoryScreen.kt`, `settings/SettingsRepository.kt`,
  `home/HomeController.kt`, `home/HomeFileHandoff.kt`, `home/HomeScreen.kt`,
  `transcribe/DialogAudioActions.kt`, `transcribe/DialogExitRetention.kt`,
  `transcribe/TranscriptionDialogModel.kt`, `transcribe/TranscriptionDialog.kt`.
- Default English resources: `app/src/main/res/values/strings.xml`.

Tests under `app/src/test/java/io/github/lrq3000/utterlane/`:

- New: `history/ImportRetentionPolicyTest.kt`, `history/ImportRetentionLifecycleTest.kt`,
  `history/ProcessingObligationTest.kt`, `settings/ImportRetentionPreferencesTest.kt`,
  `transcribe/ImportRetentionGateTest.kt`, `transcribe/ImportedAudioCopyTest.kt`.
- Modified: `home/HomeFileHandoffTest.kt`.

Tests under `app/src/androidTest/java/io/github/lrq3000/utterlane/`:

- New: `ImportRetentionAndroidTest.kt`.
- Modified: `HomeFileReplacementAndroidTest.kt`, `HomeDetailOwnershipAndroidTest.kt`,
  `TranscriptionDialogAndroidTest.kt`.

This report is the only documentation file added by this milestone. Default
English strings intentionally await the project-wide translation batch.
