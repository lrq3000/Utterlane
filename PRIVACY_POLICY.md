# Privacy Policy for Utterlane

**Last updated:** October 2026

## Overview

Utterlane is a voice-to-text application maintained by Stephen Karl Larroque that performs all speech recognition **entirely on your device**. We are committed to protecting your privacy.

## Data Collection

**We do not collect any personal data.**

- Audio and text are processed locally and are not automatically uploaded
- Microphone audio is saved locally only when you enable recording history
- Transcription uses temporary local text files for bounded-memory display, recovery, and export
- No usage analytics or telemetry
- No account required
- No advertisements

## Permissions

The app requires the following permissions:

| Permission | Purpose |
|------------|---------|
| Microphone | Record speech for on-device transcription |
| Accessibility Service | Inject transcribed text into other apps |
| Display Over Other Apps | Show floating microphone button |
| Internet | Requested downloads of speech recognition models (approximately 402–674 MB each) |
| Storage/Media | Access audio files for voice message transcription |
| Notifications | Show service status |

## Speech Recognition Model

- The selected speech recognition model (NVIDIA Parakeet v3 or optional Moondream Ultra/Redux) is downloaded from HuggingFace and stored locally
- After download, all processing happens offline on your device
- No audio or text is sent to a speech recognition server
- If you explicitly share audio or text, the receiving app controls what happens to that shared data

## Third-Party Services

- **HuggingFace**: Used only for user-requested model downloads (NVIDIA Parakeet v3 and Moondream Ultra/Redux GGUF conversions)
- Download hosts and their delivery providers necessarily receive connection information such as your IP address and the requested model URL. Their own privacy policies govern their server logs. Utterlane does not send audio, transcripts, account identifiers, or advertising identifiers with these downloads. You can import model files locally instead.
- Links you explicitly open, including this policy and model information, use your browser. The destination website and browser have their own privacy practices.

## Accessibility and text insertion

If you enable the optional Android accessibility service, Utterlane accesses the active window to find the focused editable field, reads that field's existing text and cursor position, and inserts the transcription you requested. This processing happens on your device. Utterlane does not send the field content to a server or retain it in recording history. The receiving app controls how it handles the text you insert. You can disable this permission in Android Settings; keyboard voice input, audio-file transcription, and clipboard use remain available without it.

## Data Storage

Utterlane processes and stores application data locally. Android may back up eligible settings and files according to your system configuration; microphone history is explicitly excluded:
- Speech recognition model stored in app's private storage
- User preferences stored locally
- Word correction rules stored locally
- Optional microphone history stored in private files and excluded from Android cloud backup and device transfer. Retention is configurable from No history to Forever; imported/shared audio is not duplicated
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
