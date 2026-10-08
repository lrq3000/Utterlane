# Bluetooth Input Implementation Plan

> **For agentic workers:** Use executing-plans to implement inline, task-by-task.

**Goal:** User-selected microphone input with automatic Bluetooth preference,
continuous phone fallback and accurate capture-panel feedback.

**Architecture:** One application-owned selection controller maintains the next
input; one capture-worker-owned route handles the active session. Pure policies
make device-event sequences deterministic and testable without Bluetooth hardware.

**Tech Stack:** Kotlin, Android AudioManager/AudioRecord (API 26+), DataStore,
StateFlow, Compose settings, existing native Android recording panel, JUnit 4.

## Milestone 1: Selection policy and persistent settings

Files under `app/src/main/java/io/github/lrq3000/utterlane/`:
`audio/AudioInputPolicy.kt`, `audio/AudioInputController.kt`,
`settings/SettingsRepository.kt`, `UtterlaneApp.kt`.
Tests under matching `app/src/test/java/.../audio/` paths.

- [ ] Add policy tests for missing selected device, auto off/on reconnect, manual
  non-Bluetooth override, multiple headsets, empty startup inventory, and stable IDs.
  Example assertion: `assertEquals(AudioInput.PHONE_KEY,
  policy.reconcile(InputPreferences("headset", false), emptyList()).selectedKey)`.
- [ ] Run `gradlew.bat :app:testDebugUnitTest --tests "*.AudioInputPolicyTest" -q`;
  establish missing feature before implementing policy and persistence.
- [ ] Add immutable descriptors (`key`, port ID, type, name, Bluetooth flag),
  preferences (`selectedKey`, `preferBluetooth`) and indexed inventory. Reconcile
  missing selection to Phone; in auto mode retain the selected connected Bluetooth
  input or pick the minimum stable key in a single pass. Never persist port IDs.
- [ ] Use one DataStore transaction for selecting a device and disabling auto;
  controller holds one mutex across refresh/reconcile/persist/publication. Await
  initial settings before session selection. Device callbacks refresh a snapshot.
- [ ] Repeat focused checks, inspect diff, commit the coherent selection milestone.

## Milestone 2: Worker-owned routing and recoverable fallback

Files: `audio/CaptureRoutePolicy.kt`, `audio/AndroidCaptureRoute.kt`,
`asr/AudioRecorder.kt`, `asr/AudioCapture.kt`, `asr/CaptureReadLoop.kt`,
`asr/MicrophoneSession.kt`, `asr/CaptureMetrics.kt`, `AndroidManifest.xml`.
Tests: `audio/CaptureRoutePolicyTest.kt`, existing `asr/SleepRecoveryTest.kt`.

- [ ] Add failing sequence tests: connect during active phone capture does not
  promote; initial headset negotiation may promote; loss is sticky phone fallback;
  rejected route and deadline expiry degrade; no-frame recovery is finite; stop
  wins; actual route and unsilenced PCM gate successful fallback feedback.
- [ ] Run `gradlew.bat :app:testDebugUnitTest --tests "*.CaptureRoutePolicyTest"
  --tests "*.SleepRecoveryTest" -q` before and after implementation.
- [ ] Route state accepts only the session's frozen target. Deadline expiry or loss
  changes its desired input permanently to Phone. Preserve warning reason separately
  from current actual input. Keep startup and fallback deadlines distinct from wake.
- [ ] Implement modern communication-device selection and legacy SCO activation;
  request only while capturing, release on fallback/stop/error. Phone recording
  starts immediately and is explicitly preferred. Read actual `routedDevice` for
  feedback. Callbacks invalidate state; only the read worker touches AudioRecord.
- [ ] Integrate fallback into the existing nonblocking read/reopen loop; drain
  available PCM before a needed reopen, preserve the shared pipeline/queue, and
  keep Stop/Cancel checks at resource acquisition boundaries. Do not switch to a
  newly connected device or restore Bluetooth after wake.
- [ ] Extend CaptureObserver and synchronized metrics with input/warning state.
  Confirm continued fallback only with actual Phone routing and unsilenced PCM.
- [ ] Run focused regression tests and commit routing with its tests.

## Milestone 3: Settings, waveform feedback and documentation

Files: `settings/AudioInputSettings.kt`, `settings/SettingsActivity.kt`,
`ui/RecordingPanel.kt`, `res/values/strings.xml`, `res/values/ids.xml`,
`docs/user-guide.md`, `docs/qa/bluetooth-input.md`.
Tests: `app/src/androidTest/java/io/github/lrq3000/utterlane/CapturePanelAndroidTest.kt`
and `AudioInputAndroidTest.kt`.

- [ ] Add UI assertions for the actual input with statistics off; red warning
  persists through PCM updates, stop/drain, and rebuilding/rebinding the panel.
- [ ] Implement the existing settings-section style with a radio-list dialog and
  switch. Show effective next input, react to inventory updates, and revalidate a
  stale selection. Explain that manual non-Bluetooth selection disables auto mode.
- [ ] Add 12sp actual-input label and separate semantic red warning with accessible
  text, dark/light colors, and no modal interaction. Never call a requested route
  the actual input before confirmation.
- [ ] Document selection, next-session behavior, startup activation, fallback and
  hardware limitations. Defer locale translations to the documented release batch.
- [ ] Run `gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest -q`
  incrementally. Install under an isolated QA application ID on an explicit target;
  run focused panel/routing instrumentation and inspect settings and waveform UI.
- [ ] Record exact checks and hardware limitations, inspect diffs and commit.

## Final verification

- [ ] Review all approved scenarios against code/tests, verify no unrelated changes
  or uncommitted task files, and list local commits with build/device-test results.
- [ ] Physical Bluetooth verification: connect after launch, select/record, remove
  headset during speech, verify continuing phone audio and warning, reconnect and
  verify no promotion until next recording. Repeat with auto disabled, multiple
  headsets, screen-off, and rapid Stop/Cancel. Report unavailable hardware honestly.
