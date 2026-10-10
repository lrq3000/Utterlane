# Bluetooth microphone selection and fallback verification

Latest routing/source-choice follow-up:
[Explicit capture choices](explicit-capture-selection.md), including the four newer
donor commits reviewed before squash/publication. The preceding
[main-first rebase](audiorecorder-ports-main-first.md) and port results below describe
earlier trees and retain their historical APK checksums.

## AudioRecorder ports and pinch-only resizing — 2026-10-10

Branch: `feat/audiorecorder-ports`; implementation through `08a3d9e`, including
permission hardening `1691c8a` and endpoint-identity fixes `82e3e56`.
The [28-commit applicability record](audiorecorder-port-review.md) identifies every
reviewed donor change. The [user guide](../user-guide.md#microphone-processing-presets)
documents the new presets, permission flow, diagnostics and original-audio handling.

### Final evidence

| Evidence layer | Result | Scope |
| --- | --- | --- |
| Full JVM suite | **694 passed**, zero failures/skips | Final production code, including legacy-size migration and service recreation after pinch |
| HFP Android API simulation | **124 transport + 9 integration cases passed** | SDK 28/31/36 paths with controlled profile/device responses; actual production state/ownership code |
| API 34 emulator | **17 instrumentation tests passed** | One permission denial/settings-return test, then a combined 16-test capture/input/playback/floating/settings run |
| Earlier API 28 emulator | **16 passed** on 2026-10-09 | Combined ports before the later pinch-only clarification; the final outside-Settings pinch test was verified on API 34, not rerun natively on API 28 |
| Build | Debug and Android-test APKs passed | Isolated `.audiorecorderports` QA identity; standard identity rebuilt for delivery |
| Final review | No findings in the pinch-only change | Read-only review of production changes, persistence, gesture/session integration and real-window test corrections |

The final API 34 run used `emulator-5584` (Android SDK emulator, x86_64/ARM64
translation). Its 17 cases comprise four panel tests, three real AudioRecord input
tests, seven native playback/UI tests, one floating capture test and two microphone
settings tests. The floating test first enables the overlay, **leaves Settings**, and
records without a recognition model. It enlarges the button **56 → 84 dp**, shrinks it
**84 → 63 dp**, drags out and back, requests rotation, and explicitly stops. It verifies
one capture creation, no intermediate stop, continued delivered samples, matching
saved sample counts, and the resulting recovery dialog.

The size menu was removed at the user's request. Pinching directly on the floating
microphone is the resizing workflow. Existing persisted presets remain readable.
No new Settings navigation restriction or recording-panel visibility behavior was
introduced. The prior settings-size-menu/recording-panel overlap scenario is no longer
part of this resizing workflow.

Other device assertions cover every Custom option and fixed preset, retained choices
after reopening Settings, diagnostic address redaction and deliberate clipboard copy,
permission denial and refresh on return from Android settings, native playback speed,
paused position, preparation fencing and end-of-audio completion. The diagnostic UI
test deliberately uses a **synthetic diagnostic session**, not a physical HFP headset.

### Failures investigated during verification

- HFP review found an addressless inferred peer could override contradictory catalogue
  evidence and dual-mode input preference could remain on BLE rather than verified
  classic SCO. Four SDK-specific regressions failed before correction; follow-up
  review confirmed both fixes.
- The settings permission helper now fences duplicate launches and delayed writes
  that finish after the activity leaves RESUMED. Unit regressions and the API 34
  denial/return workflow pass.
- UI tests originally targeted non-clickable text siblings of switches or attempted
  to activate already-selected radio options. They now target accessible switch names,
  scope popup windows and exercise real transitions through every radio option.
- The original four-second playback fixture ended before slow UI navigation reached
  Pause. A 30-second fixture retains the explicit final seek/completion assertions.
- Overlay creation must be awaited without scrolling the app underneath it. Recovery
  UI is awaited before cleanup, and cleanup closes current Activity instances rather
  than an obsolete reference left by recreation.
- Windows exhausted host disk space during an earlier emulator run, causing guest
  DataStore `SyncFailedException` even though `/data` reported free space. After space
  was reclaimed, the temporary emulator was restarted with file-backed quickboot RAM
  disabled. A later cold boot also produced system-wide ANRs, including System UI and
  the app's background cleanup service, under high system load. After startup settled,
  the unchanged focused and combined instrumentation runs passed. These incidents
  were not treated as passing tests or patched by changing application behavior.

### Reproduction

Use an isolated QA installation with no recognition model and the floating service
initially disabled. Nearby Devices must be revoked **outside** instrumentation before
running the denial test, because revocation can terminate the app process.

```powershell
.\gradlew.bat :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest "-PqaApplicationIdSuffix=.audiorecorderports" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
adb -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5584 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$pkg = 'io.github.lrq3000.utterlane.audiorecorderports'
$runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb -s emulator-5584 shell pm revoke $pkg android.permission.BLUETOOTH_CONNECT
adb -s emulator-5584 shell pm clear-permission-flags $pkg android.permission.BLUETOOTH_CONNECT user-set user-fixed
adb -s emulator-5584 shell am instrument -w -e class 'io.github.lrq3000.utterlane.MicrophoneSettingsAndroidTest#deniedDefaultDoesNotPromptAndExplicitActionCanRecover' $runner
$classes = 'io.github.lrq3000.utterlane.CapturePanelAndroidTest,io.github.lrq3000.utterlane.AudioInputAndroidTest,io.github.lrq3000.utterlane.AudioPlaybackAndroidTest,io.github.lrq3000.utterlane.FloatingControlsAndroidTest,io.github.lrq3000.utterlane.MicrophoneSettingsAndroidTest#presetsCustomControlsAndDiagnosticsStayIndependent'
adb -s emulator-5584 shell am instrument -w -e class $classes $runner
.\gradlew.bat assembleDebug "-PqaApplicationIdSuffix=" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
```

Read the instrumentation summary: the shell exit code alone does not establish that
tests passed. These final runs reported `OK (1 test)` and `OK (16 tests)`.

Standard APK: `app/build/outputs/apk/debug/app-debug.apk`, package verified with
`aapt2 dump packagename` as `io.github.lrq3000.utterlane`. SHA-256:
`483431c9adb70ee64a88e1e7a2fb84211a9549bf919fd1eb4266dc800df3c765`.
Local ignored screenshots include `pinch-outside-settings34.png`,
`pinch-only-settings34.png`, `microphone-diagnostics34.png` and `playback-speed34.png`
under `qa-artifacts/`; the inspected Settings screen shows the pinch hint and no size
selector. Translations for new controls follow the release-batch workflow.

**Hardware limitation:** no physical Bluetooth headset/radio was exercised. Emulated
PCM delivery and simulated HFP callbacks do not establish acoustic microphone identity,
headset gain/quality, OEM DSP behavior, negotiation success or handover gap duration.
The physical-device matrix at the end of this document still applies.

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
within available disk space. The later AudioRecorder port verification above did run
API 34 instrumentation successfully, alongside SDK 28/31/36 JVM simulation. Physical
SCO/LE transport, acoustic input identity, handover gaps and OEM-specific audio behavior
still require the hardware checks below.

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
