# Submission and reviewer drafts

Review these against the final signed build before pasting into store forms.
Supply actual release/build/video links; none has been submitted automatically.

## F-Droid

**Title:** New App: Utterlane (`io.github.lrq3000.utterlane`)

Utterlane is an Apache-2.0 offline voice-typing and audio-transcription app for
Android 8+ ARM64. Source: https://github.com/lrq3000/Utterlane

This is an independently maintained TranSlander fork with its own application ID,
name and artwork. Improvements include incremental transcription with bounded
queues, optional local recording history and retention, transcript recovery,
additional Parakeet Ultra/Redux models and a refreshed interface. The maintainer,
Stephen Karl Larroque, requests inclusion and intends to maintain the app.

Fastlane listings and fresh English Settings/transcription screenshots are in the
source repository. Recognition is on-device. Models are CC-BY-4.0 data, downloaded
only on request or imported locally; no executable self-updater, advertising or
analytics is included. Native compilation uses pinned sherpa/CrispASR/ggml sources
and official MIT-licensed ONNX Runtime from Maven Central, with SHA-256 checking.
The recipe downloads source dependencies before scanning and builds the AAR after
scanning. It uses ordinary F-Droid signing; reproducible builds are not claimed.

Attach the generated recipe and Linux F-Droid CI/build log. Disclose the project's
human-reviewed AI-assisted contribution policy if asked; do not conceal provenance.

## Google Play app access / reviewer setup

No account, login, subscription or payment is required. Use an ARM64 device with
Android 8 or later and enough space for a catalog model (159–674 MB). Open Utterlane, choose
a model and download it, or import the model files locally. Wait until it is
loaded before testing dictation. Speech recognition then works offline.

For the simplest test, share an audio file with Utterlane using Android's Share
menu. For microphone dictation, grant Microphone permission. Keyboard voice input
can be enabled in Android's input-method settings. The optional accessibility
service inserts dictation into a focused field after the in-app disclosure and
Android permission approval. The optional floating microphone additionally needs
Display over other apps. None of these optional permissions is required just to
transcribe a shared file.

## Accessibility API declaration

Utterlane uses AccessibilityService for user-requested voice typing into a focused
editable field. It accesses the active window to identify that field, reads its
current text and cursor position, and inserts the locally recognized text. It
does not automate decisions, perform gestures, change settings, bypass system
controls, or send field contents to a server. Users explicitly enable it after a
prominent in-app disclosure and can disable it in Android Settings. Alternative
keyboard voice input, audio-file transcription and clipboard output remain usable
without accessibility access.

The manifest does not claim `isAccessibilityTool=true`. Declare this general
voice-typing use accurately rather than claiming the app's primary purpose is
support for a specific disability.

**Video:** show Settings → Accessibility disclosure → decline (app remains usable)
→ agree and Android enablement → focus a harmless text field → start/stop voice
typing → inserted text → disable permission. Use a current release build and no
private messages/passwords.

## Foreground services

### Microphone

User-initiated voice typing records microphone audio for local speech recognition.
Recording must remain active while the user speaks into a target application.
Utterlane shows its capture controls and service status; users can stop or cancel
recording. Audio is processed on-device, and recording history is optional and off
by default.

**Video:** grant microphone access; enable floating microphone; show the service
notification; start dictation over an editor; show the capture indicator; stop or
cancel; demonstrate that capture stops.

### Special use — optional audio-folder monitor

The user explicitly enables monitoring of selected audio folders in Settings.
The service watches for new voice-message/audio files and offers local
transcription with a notification. A persistent service notification communicates
that monitoring is active. Monitoring is user-controlled and can be disabled in
Settings. The service does not upload files or transcribe through a cloud service.

**Video:** enable Audio Files Access and Monitor Folders, show the selected folder
and service notification, save a small test audio file there, open its transcription
notification, then disable monitoring. Verify this flow on the Android versions
you claim to support, especially current scoped-storage behavior.

## Other Play declarations

- **Pricing:** Free; no in-app purchases or subscriptions.
- **Ads:** No.
- **App access:** All functionality available without login; model setup required.
- **Permissions:** microphone for dictation; optional accessibility for insertion;
  overlay for floating control; media/audio access for local audio workflows;
  notifications for active services; wake lock for active transcription.
- **Target audience / content rating:** answer from the real intended audience and
  final content. Do not copy upstream answers or invent a rating.
- **Data safety:** use the analysis in the publication guide, including model-host
  network metadata and user-initiated sharing. A privacy-policy URL is mandatory.
