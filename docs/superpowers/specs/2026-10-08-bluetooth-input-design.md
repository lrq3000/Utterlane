# Bluetooth microphone selection and capture continuity

Approved in conversation on 2026-10-08, including implementation of the whole plan.

## User contract

Settings > Microphone offers Phone microphone and usable connected external inputs
(Bluetooth, wired, USB). Always prefer a Bluetooth microphone defaults off. Explicit
non-Bluetooth selection atomically disables that preference. Auto mode preserves a
connected Bluetooth selection, otherwise chooses a stable candidate. Missing saved
selections reset to Phone after initial inventory; auto mode remains enabled and
selects a Bluetooth microphone when one subsequently becomes available. Do not use
transient Android port IDs as persisted device identities or choose by name alone.

The next-recording selection and current recording route are independent. New
connections and settings edits affect the next recording. Losing the current
external microphone falls back to Phone for the rest of the current session,
including after wake/reopen; reconnection never switches that session back.

Capture starts on Phone while a headset selected at session start is being activated.
That startup negotiation alone may promote the session to its initially chosen
headset. It has a finite deadline; rejected or unconfirmed activation falls back to
Phone. No model initialization gates capture. No persistent communication route is
held while idle or while only transcription is draining.

The common waveform panel always shows a small actual-input caption, independent
of statistics. Unknown routing is presented honestly. External-input loss produces
a red warning which persists through panel recreation and final processing for that
session. While recovering, say switching to Phone; only report continued capture
after the phone route and incoming, unsilenced PCM are confirmed. Reconnection and
ordinary signal updates do not erase the warning. No modal acknowledgement is needed.

## Implementation boundaries

- `audio/AudioInputPolicy.kt`: pure next-selection policy, stable device descriptors.
- `audio/AudioInputController.kt`: application-lifetime Android inventory, serialized
  preference updates and initial-read barrier; events refresh only on changes/start.
- `audio/CaptureRoutePolicy.kt`: pure per-session startup/fallback/timeout state.
- `audio/AndroidCaptureRoute.kt`: API-appropriate communication-route ownership and
  AudioRecord preference application. Actual route confirmation, never preference
  acceptance, determines the caption. Modern communication APIs use sink devices
  with corresponding input chosen by Android; legacy SCO is asynchronous.
- `AudioRecorder`: the existing capture worker alone opens, reads, reroutes and
  releases native resources. Callbacks only mark inventory/configuration dirty.
- `CaptureObserver`, `CaptureMetrics`, `RecordingPanel`: carry and display route
  state without ending capture or replacing recognition errors.
- `settings/AudioInputSettings.kt`: isolated Compose selector and switch.

## Concurrency and failures

Serialize device inventory and manual/automatic settings transactions. Revalidate
on recording start and ignore callbacks from released recorder generations. A
device-change callback refreshes the whole inventory rather than replaying stale
add/remove deltas. Selection changes cannot overwrite a newer manual choice.
Keep the existing global MicrophoneSession admission guard. Stop/Cancel wins over
startup, recovery and delayed callbacks; cleanup releases only this session's
communication request. No native release occurs on a callback thread.

Phone fallback is sticky for the session, preserves the writer queue and transcript,
and has bounded recovery rather than an endless silent capture. Sleep recovery must
not restore the initial headset. Permission denial/revocation, Bluetooth disabled,
route rejection, multiple headsets, changed port IDs, identical names, calls/audio
preemption, start/disconnect and stop/reconnect races are included in validation.
Only available input-capable devices are shown; paired output-only speakers are not
microphones. Any required Bluetooth permission is optional for phone recording.

Phone fallback is best effort: Android/hardware can introduce a handover gap or
deny all microphone access. Preserve delivered PCM and report real failure if no
working route remains; never promise gapless hardware capture.

## Values, complexity and verification

CORE_VALUES section 3 adds continuity-through-fallback and the unattended meeting/
lecture example. Reuse the shared capture path for every microphone entry point.
Inventory work is O(number of devices) on changes, with indexed O(1) session lookups;
no per-PCM-block device enumeration, unbounded event queue or additional PCM copy.

Tests: pure selection and route state transitions; atomic settings; PCM preservation
and cancellation during fallback; actual-input/warning panel behavior; existing
capture and sleep regression tests. Build with `gradlew.bat testDebugUnitTest
assembleDebug assembleDebugAndroidTest`. Emulator tests validate UI and phone
routing; physical classic/LE Bluetooth handover is a separate hardware check and
must be reported as unverified if no suitable device is available.

Source contracts inspected: AOSP AudioRouting.java (`getPreferredDevice` does not
guarantee actual routing); Android 16 AudioManager.java (`setCommunicationDevice`
selects a sink, auto-selects its source, must be cleared; SCO activation is async).
