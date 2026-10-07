# Privacy Policy for Utterlane

**Last updated:** October 7, 2026

## Overview

Utterlane is a voice-to-text application maintained by Stephen Karl Larroque that performs all speech recognition **entirely on your device**. We are committed to protecting your privacy.

## Data Collection

**We do not collect any personal data.**

- Audio and text are processed locally and are not automatically uploaded
- Microphone audio is buffered in private local files during processing, including when history is disabled. Successful recordings follow your history setting (one hour by default); unfinished recordings remain available for recovery until successfully retried or explicitly deleted
- Transcription uses temporary local text files for bounded-memory display, recovery, and export
- No usage analytics or remote metrics reporting
- No account required
- No advertisements

## Local Capture Metrics

The recording interface computes microphone levels, a bounded waveform, signal
status, and transcription progress and time estimates on your device. These
capture metrics normally exist only in memory to provide recording feedback and
are not sent to a server. The metrics component does not retain raw recordings.
Private audio buffering and optional completed-recording history are separate
from these metrics and described below. Visual refresh frequency is configurable;
it does not change which audio is recorded or which transcription features run.

### Optional local diagnostics

Advanced recognition settings include a diagnostic log that is **off by default**.
When you enable it, new operations can record content-free processing stages,
elapsed/progress times, audio sample counts and backlog, runtime configuration,
and app/Android/device-model and native-build information in the app cache.
These production logs exclude audio, transcripts, word probabilities, model paths,
and exception messages. There is no automatic network reporting.

The log queue is bounded and files rotate within two 1 MiB files. You can clear
them in Settings. Inactive log files expire after seven days when cleanup next runs;
otherwise rotation limits their size.
**Share diagnostics** creates a local ZIP snapshot and opens Android's chooser;
only choosing a receiving app shares it. At most two export snapshots are kept,
and they expire after one day; creating another export may remove the oldest.
The receiving app controls any copy you share. An operation keeps its starting
diagnostics preference, so a changed preference applies to subsequent operations.

## Permissions

The app declares the following permissions for its features. Runtime permissions
and special access are requested when needed; local capture metrics do not require
any additional permission beyond the microphone access used for recording.

| Permission | Purpose |
|------------|---------|
| Microphone (`RECORD_AUDIO`) | Record speech for on-device transcription |
| Display Over Other Apps (`SYSTEM_ALERT_WINDOW`) | Show the optional floating microphone button |
| Internet (`INTERNET`) | User-requested downloads of speech recognition models (approximately 159–674 MB each) and the optional speaker-label model (approximately 107 MB); local model import is also available |
| Storage/Media (`READ_EXTERNAL_STORAGE` through Android 12L; `READ_MEDIA_AUDIO` on Android 13+) | Access audio files for optional folder monitoring and transcription; files explicitly shared or selected can instead use the access granted by Android |
| Notifications (`POST_NOTIFICATIONS`) | Show recording/monitoring status, transcription results, and service alerts |
| Wake lock (`WAKE_LOCK`) | Keep active recording and transcription running and support the screen-awake behavior during these operations |
| Foreground services (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_SPECIAL_USE`) | Run the optional floating microphone and audio-folder monitoring services with visible notifications |
| Boot completed (`RECEIVE_BOOT_COMPLETED`) | Restore enabled services after restart, or notify you to open the app when Android restricts automatic restart |

The accessibility service and voice keyboard are additionally protected by
`BIND_ACCESSIBILITY_SERVICE` and `BIND_INPUT_METHOD`, respectively. These declarations
restrict which system components can bind to those services; you enable the
optional integrations in Android Settings. Accessibility inserts requested text
into the focused field, and the voice keyboard delivers text through Android's
input-method interface. No permission is used for usage analytics or remote
metrics reporting.

## Speech Recognition Model

- The selected speech recognition model (NVIDIA Parakeet v3 or optional Moondream Ultra/Redux) is downloaded from HuggingFace and stored locally
- After download, all processing happens offline on your device
- No audio or text is sent to a speech recognition server
- If you explicitly share audio or text, the receiving app controls what happens to that shared data

## Third-Party Services

- **HuggingFace**: Used only for user-requested model downloads (NVIDIA Parakeet v3, Moondream Ultra/Redux GGUF conversions, and the optional NVIDIA Nemotron speaker-label model)
- Download hosts and their delivery providers necessarily receive connection information such as your IP address and the requested model URL. Their own privacy policies govern their server logs. Utterlane does not send audio, transcripts, account identifiers, or advertising identifiers with these downloads. You can import model files locally instead.
- Links you explicitly open, including this policy and model information, use your browser. The destination website and browser have their own privacy practices.

## Accessibility and text insertion

If you enable the optional Android accessibility service, Utterlane accesses the active window to find the focused editable field, reads that field's existing text and cursor position, and inserts the transcription you requested. This processing happens on your device. Utterlane does not send the field content to a server or retain it in recording history. The receiving app controls how it handles the text you insert. You can disable this permission in Android Settings; keyboard voice input, audio-file transcription, and clipboard use remain available without it.

## Data Storage

Utterlane processes and stores application data locally. Android may back up eligible settings and files according to your system configuration; microphone history is explicitly excluded:
- Speech recognition model stored in app's private storage
- User preferences stored locally
- Word correction rules stored locally
- Microphone audio is buffered in private files excluded from Android cloud backup and device transfer. Processing reads this same audio incrementally, so a slow or unavailable model does not require keeping the whole recording in RAM. No shared-storage permission is needed for this buffering
- Completed-recording retention defaults to one hour and is configurable from No history to Forever. With No history, successful recordings are deleted when active readers finish. Existing choices are preserved and imported/shared audio is not duplicated
- Failed, cancelled, and interrupted microphone recordings are shown as unfinished recordings in Settings, including with No history selected. Automatic history cleanup does not delete them. You can retry, export, or explicitly delete them; a successful retry resolves recovery and applies your current history setting
- Successful delivered/dismissed transcripts are removed once active readers finish. Recoverable temporary transcripts expire after seven days, with best-effort scheduled and startup cleanup
- User-requested audio and text export snapshots remain temporarily available for the receiving app and expire after one day. These copies are separate from recording-history retention
- Android may defer background deletion while asleep or force-stopped. Active capture, playback, transcription, and export readers are protected from cleanup

## Children's Privacy

This app does not knowingly collect any information from children under 13.

## Changes to This Policy

We may update this privacy policy from time to time. Changes will be posted in this document with an updated date.

## Contact

For questions about this privacy policy, contact Stephen Karl Larroque at
[LRQ3000@GMAIL.COM](mailto:LRQ3000@GMAIL.COM), or open an issue at:
https://github.com/lrq3000/Utterlane/issues

## Open Source

Utterlane is open source software licensed under Apache 2.0. You can review the complete source code to verify these privacy claims.
