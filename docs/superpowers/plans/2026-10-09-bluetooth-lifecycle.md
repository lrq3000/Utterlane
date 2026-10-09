# Bluetooth lifecycle verification and hardening

> Execute inline with executing-plans and test-driven-development. The user approved
> this follow-up after the comparison with Dimowner AudioRecorder on 2026-10-09.

**Goal:** Exercise actual Android routing-adapter code on simulated API 28, 31 and
36, correct unrouteable/ambiguous Bluetooth choices, and retain meaningful removal
events through rapid reconnects without changing the approved recording UX.

**Architecture:** Keep application-owned next-input selection and worker-owned
active routing. Use test-only Robolectric/MockK to control Android device ports,
callbacks and route confirmation. Modern selectable Bluetooth identities come
from available communication endpoints; raw input ports are routing evidence,
not additional modern Bluetooth choices. Preserve removal identity before
conflating refresh requests. Never apply an old removal to a newer manual choice.

**Reference inspected:** Dimowner/AudioRecorder commit
`28ef142524f38f66a3793f9a4d2f796c981a5067`, notably `AudioManagerHelper.kt`, its
API-specific Robolectric tests, `HomeViewModel.kt`, `AudioRecordingService.kt`,
`AudioRecordFactory.kt`, `MediaRecorderBase.kt` and `WavRecorderV2.kt`.
The reference inspired lifecycle test scenarios; no upstream implementation is
copied. Retain Utterlane's immediate Phone capture, next-recording preference
changes, actual-route checks and sticky session fallback. Fixed settling delays
and UI-owned routing do not satisfy that contract.

## 1. Establish Android-platform tests

Files: `app/build.gradle.kts`,
`app/src/test/java/io/github/lrq3000/utterlane/audio/AudioRoutingPlatform.kt`,
`app/src/test/java/io/github/lrq3000/utterlane/audio/AndroidCaptureRouteTest.kt`.

- [x] Add pinned, test-only Robolectric and MockK dependencies and Android resources.
- [x] Use an ordinary Application in tests so model/native application startup is
  not required. Mock AudioManager/AudioRecord boundary calls, retain real Android
  Context/receiver/looper behavior and real production routing classes.
- [x] Establish working Phone and Stop-before-start cases across API 28/31/36;
  assert that these do not acquire or clear a communication route.
- [x] Run `gradlew.bat :app:testDebugUnitTest --tests "*.AndroidCaptureRouteTest"
  "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q`.
  Commit the working test infrastructure before unrelated fixes.

## 2. Canonical, unambiguous Bluetooth catalogue

Files: `audio/AndroidAudioInputDevices.kt`, `audio/AudioInputPolicy.kt`,
`audio/AndroidCaptureRoute.kt` under the main package; corresponding Android-platform
catalogue and route tests under `app/src/test/java/io/github/lrq3000/utterlane/audio/`.

- [x] Reproduce raw-source entries without a communication ID on API 31+ and a
  source assigned to multiple communication endpoints when addresses are absent.
- [x] Keep modern Bluetooth candidates exclusively in the communication inventory.
  Match source ports by a unique exact type/address, or an unambiguous one-to-one
  remaining association where an address is absent. Different known addresses must
  never match. Use indexes so catalogue construction is O(devices).
- [x] Preserve raw Bluetooth source selection on legacy Android, wired/USB inputs,
  Phone, and stable identities. Confirm routing from fresh Android evidence even
  when an input port appears after the initial communication endpoint.
- [x] Verify and commit with regression cases for duplicate names, blank addresses,
  changed ports, activation ordering, and output-only devices.

## 3. Preserve disconnects through conflation

Files: `audio/AudioInputController.kt`, `audio/AndroidAudioInputDevices.kt`,
`audio/AudioInputPolicy.kt`, `audio/AudioInputControllerTest.kt` and platform tests.

- [x] Reproduce disconnect followed by reconnect before the refresh coroutine runs.
  Auto-off must reset to Phone; auto-on can choose the newly connected device.
- [x] Retain removal identity in bounded state, not an unbounded event queue. A
  modern SCO input-port teardown is not removal of its still-available communication
  endpoint. Events for unrelated devices do not reset the selection.
- [x] Serialize manual choices with removal reconciliation; stale events cannot
  overwrite a newer manual selection, including selecting the same headset again.
- [x] Verify startup, background operation, removal/selection races and commit.

## 4. Routing lifecycle and ownership

Files: `audio/AndroidCaptureRoute.kt`, `audio/CaptureRoutePolicy.kt`,
`asr/AudioRecorder.kt` only if a reproduced case requires it, and platform tests.

- [x] Exercise successful/delayed/rejected activation, timeout, Stop during setup,
  active-headset loss while another remains, rapid reconnect, stale callbacks,
  repeated sessions and cleanup after partial platform failures on API 28/31/36.
- [x] Require observed input and unsilenced PCM before confirming capture. Preserve
  actual state, sticky fallback, Stop precedence and worker-owned native lifetimes.
- [x] Fix only reproduced issues; validate and commit coherent corrections.

## 5. Final evidence

- [x] Run the full unit suite, `assembleDebug` and `assembleDebugAndroidTest` in
  normal incremental mode; exercise the existing isolated API 28 device tests.
- [x] Build the standard debug APK and verify its application identity and hash.
- [x] Update `docs/qa/bluetooth-input.md` with exact checks and distinguish JVM
  Android-simulation, emulator PCM/UI evidence, and remaining physical-radio checks.
- [x] Review the complete change, commit all test/source/documentation files and
  report the follow-up branch, commits, APK and validation limits.

**Outcome:** 423 full-suite tests passed, including 76 Android-platform simulations;
seven API 28 instrumentation tests and the rendered Settings flow passed. Independent
review found four additional ordering/binding cases, all reproduced and corrected. Exact
commands, transient host-test observations and the physical-radio verification
boundary are recorded in `docs/qa/bluetooth-input.md`.
