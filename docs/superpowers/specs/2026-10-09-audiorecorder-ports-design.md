# AudioRecorder ports: approved Utterlane design

Approved in conversation on 2026-10-09 after review of all 28 commits in
lrq3000/AudioRecorder's `feat/bluetooth-mic-experiments` through
`e1c802060bcf29b8c66a6b3f27339e857289a37d`. Base this work on Utterlane's completed
Bluetooth lifecycle work, `bddcde6`, rather than losing those unmerged corrections.

## Microphone configuration

Offer Disabled, HFP preset and Custom. HFP is the default when no new configuration
is stored, including upgraded installations; retain microphone selection and the
independent automatic-Bluetooth preference. HFP applies this tuple atomically:

- Android source: VOICE_RECOGNITION.
- Route: HFP_VOICE_RECOGNITION using BluetoothHeadset.startVoiceRecognition.
- Requested mode: NORMAL.
- Android effects: AGC_ONLY (NS off, AEC off, AGC on).
- Transcription gain: AUTO_LEVEL.

Disabled restores DEFAULT/STANDARD_SCO/IN_COMMUNICATION/SYSTEM_DEFAULT/OFF. Custom
retains its current values. An individual edit selects Custom; selecting a fixed
preset updates all five fields in one DataStore transaction. Reset returns the
Utterlane default HFP preset. Never infer success from a stored preset label.

Expose sources DEFAULT, MIC, VOICE_COMMUNICATION, VOICE_RECOGNITION, UNPROCESSED;
routes STANDARD_SCO/HFP_VOICE_RECOGNITION; modes IN_COMMUNICATION/NORMAL; effects
SYSTEM_DEFAULT, DISABLE_NS, DISABLE_NS_AEC, DISABLE_NS_AEC_AGC, AGC_ONLY; and gains
OFF, DB_PLUS_6, DB_PLUS_12, DB_PLUS_18, AUTO_LEVEL. Custom controls expand on entry
and may be collapsed. Current/last-session diagnostics remain available in all modes.

Changes affect subsequent recordings. Each capture and any native reopen retain
one immutable configuration. Unsupported Java effects remain nonfatal and report
availability, control, requested and observed state. Diagnostics stay process-local,
exclude speech and raw device addresses, and support deliberate copying.

## HFP integration and continuity

Retain the hardened per-session routing owner and source-binding generation checks.
Standard routing retains modern communication-device APIs on Android 12+ and legacy
SCO below that. NORMAL is an experiment for classic HFP/legacy routes; modern standard
routing uses communication mode and reports the distinction.

HFP requires BLUETOOTH_CONNECT on Android 12+, requested from an explicit Bluetooth
settings action; Phone and file transcription remain usable without it. No scan or
location permission is added. Match the selected headset by address or a genuinely
unambiguous classic profile/device association; never silently choose another peer.
Observe actual HFP audio connection and the AudioRecord route, not request acceptance.

Start Phone capture while HFP setup proceeds, with an eight-second HFP activation
deadline. Rejection, missing permission, unsupported profile, disconnect and timeout
use the existing sticky Phone fallback/red warning. Do not inherit the donor's
experimental stop-on-failure behavior or silently substitute standard SCO for HFP.
Stop/Cancel wins over delayed profile callbacks. Release owned voice-recognition
requests, profile proxies, receivers and mode/routing claims, including late-arriving
proxies; never clear another session's ownership.

## Non-destructive software gain

This is an approved adaptation from the donor: save the original captured PCM,
including whatever Android/headset processing supplied, and apply software gain only
to the transcription stream. Persist the gain choice with a microphone history entry
so retranscription/recovery uses that same choice. Older entries and imported files
default to gain OFF. Audio playback/export continues to expose the preserved source.

Use saturating PCM16 gain and a bounded, audio-time leveler: approximately -18 dBFS
RMS target, at most +18 dB gain, 50 ms reduction/500 ms increase, and return toward
unity below -60 dBFS. Do not noise-gate samples. Preserve every sample and the final
partial block. Fixed analysis windows make results independent of writer/inference
backlog and arbitrary input chunk boundaries. No file-long buffer or second audio
spool is introduced. Apply before recognition, including optional diarization, without
making gain failure a reason to cancel otherwise healthy capture.

## Other selected ports

- Floating control: resize in place, add persisted bounded pinch sizing, clamp
  position to usable display bounds, handle rotation and prevent drag/multi-touch/
  cancellation from becoming accidental recording taps. Permit recording with the
  required permissions even when no model is installed; use normal recovery later.
- Playback: pause Utterlane-owned playback at an accepted microphone start, including
  pending preparation, retain position and do not automatically resume it. Add
  0.5x-2x speed independently of visual refresh rate; changing paused speed must not
  start playback, and WAV-part transitions preserve the choice.
- Storage/decoding: recognize storage-full errors with actionable messages, retain
  the existing saved-prefix contract, and tolerate malformed optional metadata while
  rejecting invalid mandatory formats with context. Do not invent audio metadata.

The review also examined naming/notes, external speech-provider delegation,
drag-to-close, system playback, AAC recording/recovery, search, rendering and build
updates. The approved immediate scope uses Utterlane's existing offline recognition,
bounded PCM spool and recovery; those separate product workflows are not added by
this port. No upstream build-stack upgrade or analytics is needed.

## Verification

Use failing regressions before corrections, small coherent commits and normal
incremental builds. Cover preset defaults/migration/atomic writes, PCM math and
chunk-independent replay with byte-identical raw history, effects failures/release,
HFP readiness/permissions/timeout/late callbacks on API 28/31/36, floating gestures
and uninterrupted capture during resizing, playback state transitions and storage/
metadata faults. Run the integrated unit suite and debug/test APK builds, targeted
API 28 instrumentation and UI checks. Report simulated API evidence separately from
physical Bluetooth/headset validation.
