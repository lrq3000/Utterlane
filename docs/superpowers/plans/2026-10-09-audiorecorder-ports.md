# AudioRecorder port implementation plan

> Execute with executing-plans and test-driven-development. Independent auxiliary
> components may use dispatching-parallel-agents in separate, newly created worktrees.

**Goal:** Deliver the approved microphone/HFP defaults and selected floating,
playback and storage improvements while preserving original PCM and the completed
Bluetooth lifecycle hardening.

**Architecture:** Immutable microphone options in DataStore, session-owned transport
and effects, bounded gain processing on the transcription side of the raw disk spool.
Keep platform decisions testable and auxiliary UI/lifecycle changes independent.

**Tech stack:** Kotlin/Compose, Android AudioRecord/BluetoothHeadset/audiofx, DataStore,
coroutines, existing MediaPlayer controller, JUnit/Robolectric/MockK and Android tests.

## Ownership and integration

Primary implementation owns `audio/`, `asr/AudioRecorder.kt`, `asr/AudioCapture.kt`,
`asr/MicrophoneSession.kt`, `asr/RecordingPipeline.kt`, `history/RecordingHistory.kt`,
microphone configuration additions to `settings/SettingsRepository.kt`,
`settings/AudioInputSettings.kt`, app initialization and the manifest.

Auxiliary work stays in separate worktrees based on this plan commit. Commit tested
milestones and return hashes for integration; no agent installs into the shared emulator.

1. Floating work owns `service/FloatingMicService.kt`, floating-related portions of
   `settings/SettingsActivity.kt`/`SettingsRepository.kt`, new geometry/gesture helpers,
   dedicated resource XML and tests. It must not change MicrophoneSession or audio/.
2. Playback work owns `transcribe/AudioPlaybackController.kt`,
   `transcribe/AudioPlaybackControls.kt`, dedicated resources/tests. Expose a Main-thread
   `pauseForCapture()` operation; primary adds its call after microphone admission.
3. Storage work owns new storage error utilities, `transcribe/AudioDecoder.kt`,
   `DialogAudioActions.kt`, storage-reporting portions of `TranscriptionDialogModel.kt`,
   dedicated resources/tests. Primary integrates the utility into microphone failures.

## Milestone A: Configuration and non-destructive gain

- [x] Add pure `audio/MicrophoneConfiguration.kt` enums/data class and HFP/standard
  tuples. Use stable keys, preserve explicit Custom, and default new configuration
  to HFP. Add DataStore tests before implementing atomic updates.
- [x] Add `audio/TranscriptionGain.kt` using ShortArray input and bounded fixed windows;
  test fixed gain, clipping, near-silence, attack/release and arbitrary block splits.
- [x] Persist gain metadata in microphone history with OFF for absent/old/imported
  data. Apply gain only to recognition accepts and flush its tail before finish.
  Verify raw WAV bytes/samples are unchanged and replay produces identical processed PCM.
- [x] Run focused JVM tests, inspect the diff and commit the coherent milestone.

## Milestone B: Capture effects, HFP and diagnostics

- [x] Extend capture configuration handoff so source/effects/route are frozen for
  recording and native reopen. Attach/release best-effort per-recorder effects.
- [x] Add process-local current/last microphone diagnostic snapshots with owner tokens.
  Report requested/observed source, route, mode and effect control truthfully.
- [x] Add a classic HFP transport beside Standard; acquire the profile asynchronously,
  match the selected headset, wait for real audio readiness, and enforce Stop precedence
  and finite setup. Preserve Phone capture and the existing red fallback warning.
- [x] Add required legacy/modern Bluetooth permissions and an explicit settings grant
  action. No background permission prompts, scanning or location access.
- [x] Test success/rejection/missing permission/late proxy/mismatch/disconnect/cleanup
  and effects failures across supported SDK paths, then commit verified behavior.

## Milestone C: Settings UI

- [x] Add Disabled/HFP/Custom selector, HFP default summary, collapsible custom source,
  route/mode/effect/gain controls and copyable diagnostics. Reuse existing theme and
  accessible full-row controls; show that changes affect the next recording.
- [x] Verify preset consistency, custom persistence, permission denial/return and
  active-session immutability. Build resources and commit.

## Milestone D: Auxiliary ports

- [x] Integrate tested floating work, reproducing the existing resize/restart behavior
  and gesture errors before correction. Confirm model absence does not block capture.
- [x] Integrate playback controls and call `pauseForCapture()` only after admission;
  a rejected duplicate start must not disturb playback. Verify preparing/paused states.
- [x] Integrate storage reporting/decoder changes. Use the storage utility for recorder
  write/finalization errors and retain preserved-prefix recovery.
- [x] Inspect each integration diff and run relevant focused checks before proceeding.

User clarification completed in `08a3d9e`: remove the size selector and use only
direct pinch resizing. Preserve legacy saved diameters, test resizing after leaving
Settings, and introduce no Settings navigation or recording-panel visibility change.

## Milestone E: Delivery

- [x] Review combined code for races, ownership, loss of raw PCM and stale diagnostics.
- [x] Run full JVM suite, `assembleDebug` and `assembleDebugAndroidTest` with an isolated
  QA application suffix; run relevant emulator tests and settings/control UI flows.
- [x] Rebuild the standard-identity debug APK, verify its package/hash, update user/QA
  docs and the 28-commit applicability record, and commit all completed work.

Pre-rebase production verification: **694 JVM tests**, **17 API 34 instrumentation tests**,
and both APK variants built. The earlier API 28 combined run passed 16 tests before
the final pinch-only adjustment; current API 28 routing/gesture paths are also covered
in JVM simulation. Physical Bluetooth/headset validation remains outstanding. See
`docs/qa/bluetooth-input.md` and `docs/qa/audiorecorder-port-review.md` for evidence,
reproduction commands, environment incidents and the standard APK hash.

Main-first replay onto `f6975ce` subsequently passed **841 JVM tests**, both QA APK
builds and **31 distinct API 34 cases across the documented batch/focused runs**.
All 25 feature commits are retained (20 equal patches, 5 adaptations), with no new
Settings access restriction. See `docs/qa/audiorecorder-ports-main-first.md` for the
strict hunk checklist, preservation review and rebuilt standard APK checksum.

## Follow-up: new donor choice-enforcement fixes

- [x] Review all four commits in `e1c8020..2c7664a` before the pending squash/push.
- [x] Reproduce and correct API/mode substitutions, cached input evidence, source
  identity reuse, classic/LE confusion and incompatible client declarations.
- [x] Preserve all returned PCM and explicit Phone fallback; bound buffer verification
  separately from capture liveness and retain source-generation protections.
- [x] Resolve review findings with large-buffer, callback-readiness and unrelated-event
  regressions, and obtain follow-up approval.
- [x] Pass the complete 914-test JVM suite, both QA APK builds and 32 distinct API 34
  cases in successful split invocations. See `docs/qa/explicit-capture-selection.md`.
- [x] Rebuild the updated standard APK and record its verified identity/checksum.

Authorized delivery after the verified feature commit: refresh the self-contained
squash on current main and publish both feature and main branches.
