# Bluetooth microphone selection and fallback verification

## Lifecycle follow-up — 2026-10-09

Base: `9dc33a8`; branch: `fix/bluetooth-lifecycle`. This supersedes the initial
verification's statement that the API 31+ adapter had only been compiled/reviewed.

### What is now verified

| Evidence layer | Result | What it establishes |
| --- | --- | --- |
| Full JVM suite | **423 passed**, zero failed/skipped on the final run | Existing app regressions plus selection, capture and platform-adapter cases |
| Android API simulation | **76 cases passed** on API 28, 31 and 36 | Production catalogue/routing code executes against real SDK classes, Context, receivers and loopers, with AudioManager/AudioRecord hardware responses controlled by MockK |
| API 28 emulator | **7 instrumentation tests passed** | Real phone-route PCM, unavailable-input phone fallback, Stop behavior, and waveform caption/warning presentation |
| Rendered Settings flow | Passed on isolated `.bluetoothlifecycle` install | Auto preference persists across process restart without a headset; selecting Phone disables it |
| APKs | Debug and instrumentation builds passed | Standard APK package verified as `io.github.lrq3000.utterlane` |

The 76 platform cases comprise 55 routing cases and 21 catalogue cases. They cover
request acceptance versus actual route/unsilenced frames, delayed/rejected activation,
timeouts, legacy SCO exceptions, BLE source ports appearing after activation, Stop
during setup, existing call/communication mode ownership, late recorder callbacks,
partial platform failures, and bounded final cleanup retry. They also cover missing/
duplicate addresses and stable endpoint identity when unrelated duplicates appear
or disappear. The focused policy/platform/read-loop batch passed **113 tests**.

### Reproduced and corrected

- Modern raw Bluetooth sources could remain selectable without a communication ID.
  Available communication endpoints are now the canonical modern choices.
- An input could be assigned to multiple address-less endpoints, exact matches could
  prevent a remaining valid pair, and duplicate metadata could overwrite a choice.
  Matching now reserves unique exact pairs before unambiguous remaining pairs and
  keeps connected endpoint keys stable until removal. Indexes keep matching O(n).
- A rapid disconnect/reconnect could vanish inside a conflated refresh. Selected
  and pending choices now retain removal flags independently of the refresh queue.
  Tests cover delayed port-removal delivery, newer manual choices, removals during
  suspended/failed preference writes, and source-only SCO teardown.
- Active capture could miss the same rapid loss after next-session selection moved
  to another headset. A session-owned device callback now latches loss of its frozen
  connection; the capture worker applies sticky Phone fallback.
- Unrelated duplicate metadata could erase the active source's catalogue mapping.
  Active capture now retains its established source binding independently of settings
  persistence, queries fresh native inventory, and permanently invalidates removed
  or positively disproved associations. Device-event generations and a short binding
  lock prevent an event during route inspection from replacing a removed binding
  using stale evidence; native queries and route requests stay outside that lock.
- Partially failing mode/device requests could escape ownership cleanup. Ownership
  is recorded before the request. Legacy SCO routing exceptions use Phone fallback.
  Failed cleanup retains ownership for one final close attempt, without per-frame
  retry loops. Listener cleanup is independent so one failure cannot block the rest.

The catalogue, reconnect/write, acquisition/cleanup and review transition regressions
were observed failing before their fixes. Independent read-only review identified
two additional transition-order issues (seven SDK-specific cases) and two source-
binding publication/invalidation issues (four SDK-specific cases). Those tests failed
before correction; follow-up review confirmed all four findings resolved against the
passing reports.
No real-radio claim is inferred from injected devices or frame counts.

Reference inspected: [Dimowner AudioRecorder's routing helper](https://github.com/Dimowner/AudioRecorder/blob/28ef142524f38f66a3793f9a4d2f796c981a5067/app/src/main/java/com/dimowner/audiorecorder/util/AudioManagerHelper.kt)
and its [API-specific tests](https://github.com/Dimowner/AudioRecorder/blob/28ef142524f38f66a3793f9a4d2f796c981a5067/app/src/test/java/com/dimowner/audiorecorder/util/AudioManagerHelperTest.kt).
Utterlane retains per-session routing, next-recording preference changes and actual
input confirmation rather than a fixed settling delay or UI-owned routing lifecycle.

### Follow-up reproduction commands

Robolectric/MockK are test-only dependencies. The first online run downloads Gradle
dependencies and instrumented Android SDK jars; subsequent cached builds can use the
commands below. Test JVM flags narrowly enable the JDK access bridge needed by
Android 16's simulated shared-memory setup and MockK's test agent. The fixture grants
the manifest-declared install-time receiver permission in the simulated Application,
and explicitly selects the non-deprecated AudioRouting listener overload.

```powershell
.\gradlew.bat :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest "-PqaApplicationIdSuffix=.bluetoothlifecycle" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -e class io.github.lrq3000.utterlane.CapturePanelAndroidTest,io.github.lrq3000.utterlane.AudioInputAndroidTest io.github.lrq3000.utterlane.bluetoothlifecycle.test/androidx.test.runner.AndroidJUnitRunner
python tools/qa/check_audio_input_ui.py --serial emulator-5556 --package io.github.lrq3000.utterlane.bluetoothlifecycle
.\gradlew.bat assembleDebug "-PqaApplicationIdSuffix=" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
```

The final standard APK SHA-256 is
`1fe8ae315479513fc125df35fc71cadd16c75bc705f2d5917df4ea0f356b905c`.

One initial full run hit the existing `HistoryTest` immediate-deletion assertion on
Windows. Its focused eight-test rerun and a full rerun passed unchanged; the expanded
final 423-test suite also passed. The history implementation and assertion were not
modified; the transient failure's underlying OS cause was not established. Routine
test-agent class-sharing and existing Java deprecation warnings were also emitted.

## Initial implementation verification — 2026-10-08

Date: 2026-10-08. Base: `54a3dca`. Feature branch: `feat/bluetooth-input`.
The [approved design](../superpowers/specs/2026-10-08-bluetooth-input-design.md)
and [user guide](../user-guide.md#microphone-input-and-bluetooth) describe the contract.

### Initial automated evidence

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

### Initial reproduction commands

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
The initial isolated API 34 emulator launch could not allocate its userdata image
within available disk space. The follow-up above now executes the API 31/36 adapter
in JVM Android simulation, while physical SCO/LE transport, acoustic input identity,
handover gaps and OEM-specific audio behavior still require the hardware checks below.

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
