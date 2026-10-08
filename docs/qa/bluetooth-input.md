# Bluetooth microphone selection and fallback verification

Date: 2026-10-08. Base: `54a3dca`. Feature branch: `feat/bluetooth-input`.
The [approved design](../superpowers/specs/2026-10-08-bluetooth-input-design.md)
and [user guide](../user-guide.md#microphone-input-and-bluetooth) describe the contract.

## Automated evidence

- Full `:app:testDebugUnitTest`: **324 tests, zero failures or ignored tests**.
- `assembleDebug` and `assembleDebugAndroidTest`: passed, using the existing
  `qaApplicationIdSuffix=.bluetoothqa` property for an isolated installation.
- API 28 LDPlayer target `emulator-5556` (PJD110, x86_64 with ARM translation):
  **7 instrumentation tests passed** across `CapturePanelAndroidTest` and
  `AudioInputAndroidTest`.
- Real emulator AudioRecord delivered phone PCM with an actual phone route;
  unavailable-headset fallback delivered phone PCM without ending the recorder;
  Stop-before-start acquired no microphone.
- Panel tests verify the small actual-input caption with statistics disabled,
  persistent red warning across panel recreation, light/dark semantic colors,
  and past-tense fallback feedback during final processing. Optional 360dp panel
  renders were inspected in both themes. They show a **synthetic UI fallback state**,
  not evidence of physical headset disconnection.
- `tools/qa/check_audio_input_ui.py` passed the rendered Settings flow: enable auto
  preference with no headset, restart the process and verify persistence, open the
  real selector, select Phone, and verify auto preference turns off. UI actions use
  accessibility-tree bounds; the shared switch now exposes its setting title to
  assistive technology.

JVM coverage includes missing selection/reconnection with auto off/on, multiple
headsets, stable identity across changed Android port IDs, explicit wired/phone
overrides, startup initialization, stale picker rejection, and concurrent refreshes.
Routing-policy coverage distinguishes requested versus actual input, initial
negotiation, sticky fallback, route preemption, silencing, finite recovery, and
reopening after subsequent interruption.

The read-only concurrency review found two issues, both fixed and re-reviewed:

1. An identity check alone did not prevent an already-running old recorder callback
   from writing silencing state after recorder replacement. Configuration callbacks
   now set generation-local flags; the worker alone queries/publishes configuration.
   `CaptureSilencingTest` verifies callback-thread isolation and stale invalidation.
2. Waiting for a zero-length read could starve fallback recreation while stale PCM
   remained readable. `SleepRecoveryTest` first reproduced this starvation, then
   verified bounded recovery with every delivered block preserved in order and
   Stop checked immediately before native recreation.

## Reproduce

From an isolated worktree with the documented native dependencies prepared:

```powershell
.\gradlew.bat :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest "-PqaApplicationIdSuffix=.bluetoothqa" --console=plain -q
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -e captureEvidence true -e class io.github.lrq3000.utterlane.CapturePanelAndroidTest,io.github.lrq3000.utterlane.AudioInputAndroidTest io.github.lrq3000.utterlane.bluetoothqa.test/androidx.test.runner.AndroidJUnitRunner
python tools/qa/check_audio_input_ui.py --serial emulator-5556
```

Choose an explicit available target. The UI script requires an already configured
isolated QA installation and no external microphone; it force-stops only that
package and leaves Phone selected with automatic preference disabled. Optional panel
PNGs are written to that application's external files directory. Local UI trees and
screenshots are under ignored `qa-artifacts/`.

One Windows Kotlin daemon compilation encountered a temporary-directory cleanup
error; Gradle's compiler fallback completed successfully and the tests/APKs passed.
No source or global compiler configuration workaround was applied.

## Hardware validation still required

No physical Bluetooth microphone was connected. The emulator evidence **does not
verify SCO/LE radio activation, microphone quality, or real disconnect handover**.
An isolated API 34 emulator launch was attempted, but its userdata image required
more free disk space than was available. Thus API 31+ routing is compiled and
source-reviewed, not runtime-validated in this run. No host virtualization or
existing emulator configuration was changed.

Before claiming hardware verification, test classic HFP/SCO on older Android and
classic/LE Audio on Android 12+ where supported:

- Start without a headset; connect one while Settings is closed, auto off/on.
- Select a headset, restart the app with it present/absent, and record again.
- Verify the caption against actual acoustic input (speak/tap near each microphone).
- Connect another headset or edit settings during capture: active input stays fixed.
- Disconnect the active headset mid-speech, including screen-off meeting capture:
  verify continuing phone audio, a persistent red warning, and retained prefix PCM.
- Reconnect it during fallback: current capture stays on Phone, next recording
  follows auto preference. Repeat with several headsets and identical names.
- Exercise rapid disconnect/reconnect, Stop/Cancel during activation/fallback,
  Bluetooth disabled, wired/USB removal, call preemption and microphone revocation.
- Confirm audio mode and communication routing are released after capture ends,
  including while transcription is still draining.

Hardware/Android can introduce a handover gap. A finite unsuccessful fallback is
reported through the existing capture-failure/recovery flow; it is not described as
successful continuous recording. Locale translations follow the release-batch policy.
