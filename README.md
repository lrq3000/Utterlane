<p align="center">
  <a href="https://lrq3000.github.io/Utterlane/"><img src="assets/utterlane-banner.png" alt="Utterlane — Fast. Offline. Transcription." width="900"></a>
</p>
<p align="center">
  <strong>Utterlane - Blazingly fast accurate offline transcription for Android.</strong>
</p>

<p align="center">
  <a href="https://lrq3000.github.io/Utterlane/"><img src="assets/visit-website.svg" alt="Visit the Utterlane website" width="360" height="56"></a>
</p>

<p align="center">
  <a href="https://github.com/lrq3000/Utterlane/actions/workflows/build_apk.yml"><img src="https://github.com/lrq3000/Utterlane/actions/workflows/build_apk.yml/badge.svg" alt="Build APK"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0 license"></a>
  <img src="https://img.shields.io/badge/Android-8%2B%20%C2%B7%20ARM64-3DDC84" alt="Android 8 or later, ARM64">
  <img src="https://img.shields.io/badge/speech%20recognition-on--device-0088ee" alt="On-device speech recognition">
</p>

<p align="center">
  <a href="https://github.com/lrq3000/Utterlane/releases/latest"><img src="https://img.shields.io/badge/Download_APK-GitHub_Releases-18283B?style=for-the-badge&amp;logo=github&amp;logoColor=white" alt="Download APK from GitHub Releases"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/lrq3000/Utterlane"><img src="https://img.shields.io/badge/Track_updates-Obtainium-067A9B?style=for-the-badge" alt="Add Utterlane to Obtainium"></a>
</p>

<p align="center">
  <a href="#get-started">Get started</a> ·
  <a href="#features">Features</a> ·
  <a href="#privacy">Privacy</a> ·
  <a href="#documentation">Documentation</a> ·
  <a href="#contributing">Contribute</a>
</p>
Utterlane is a free, open-source offline voice-typing and audio-transcription app for Android. Dictate into any app, use your keyboard's microphone button, or share a voice memo to the app to transcribe. No account or subscription is required.

## Features

- **Offline and private:** speech recognition runs on your device.
- **Streaming text as you speak:** receive completed speech segments while recording continues.
- **Record directly in the app:** the Blue Notebook home screen brings recording,
  local audio-file loading, audio history and transcripts together.
- **Blazingly fast:** When you stop speaking, the transcription is already done. That's because of clever optimizations, one of which being to start transcribing in the background, and careful memory handling between transcription and audio recording allows to concurrently do both with no performance hit.
- **SOTA accurate models:** Moondream Parakeet Ultra (Sept 2026, ~670MB) is the default model. For slow devices, Moondream Parakeet Redux with ternary quantization (~160 MB) is available. For more advanced phones, lots of custom SOTA models can be imported via CrispASR (eg, R2T2).
- **Reliable, robust:** Hardened recording and transcription that leaves no room for failure. Worst case scenario, voice recordings history with autopruning allows you to always recover from a failed transcription.
- **Works across apps:** use a keyboard mic, accessibility button, or floating mic.
- **25 languages:** language detection is automatic, even when you switch languages live in the middle of a recording.
- **Audio-file transcription:** share or open vocal memos and recordings, or monitor a folder for new audio.
- **Personalize your results:** add word corrections and optional speaker labels.
- **Real-time meetings transcripts:** Optional streaming speakers diarization with auto detection of the speakers count allows to transcribe meetings in real-time.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Utterlane Settings in English, with recognition model and microphone options" width="300">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Utterlane transcription dialog showing an audio file transcribed locally" width="300">
</p>

## Get started

### Install

You need **Android 8.0+ with ARM64 support** and space for a speech model
(approximately **159–674 MB**).

Download the `Utterlane-<version>-arm64-v8a.apk` from
[GitHub Releases](https://github.com/lrq3000/Utterlane/releases/latest) and open it
to install. If prompted, allow installation from your browser or file manager.
The release APK will be available after the first signed release is published;
until then, see [building from source](docs/development.md#build-from-source).

You can also track releases with [Obtainium](https://github.com/ImranR98/Obtainium)
using the button above, or find `lrq3000/Utterlane` in
[Komi Store](https://github.com/komi-store/komi-store).

### Store availability

**F-Droid and a free Google Play listing are planned.** See the
[installation guide](docs/user-guide.md#installation-and-updates) for update
options and advice on switching between distribution channels.

### Your first dictation

1. Open **Utterlane** and follow the **setup guide**. Choose a recommended speech
   model and download it, or import existing model files. After setup, recognition
   works offline. The guide includes optional in-app dictation and audio-sharing
   tests and can be reopened from Settings.
2. Grant **Microphone** access.
3. On **Record**, tap the waveform and speak; tap it again to stop. Read, copy or
   share the transcript directly. Use the gear for Settings and the bottom tabs
   for **Audio history** and **Transcripts**. No keyboard or overlay setup is needed.

For dictation **into another app**, just configure one of the available shortcuts in our settings:
- **Keyboard mic:** open **Keyboard Integration → Voice Input Method**, enable
  Utterlane, and enable the voice-input key in a compatible keyboard such as
  [FUTO Keyboard](https://keyboard.futo.tech/) or [HeliBoard](https://github.com/Helium314/HeliBoard).
- **Accessibility button:** enable Utterlane's text-input accessibility service
  in Android Settings.
- **Floating mic:** allow **Display over other apps** and enable the floating
  button in Utterlane.

Any of these shortcuts will allow to write in any text field in any app. They can also be enabled all at once, they are useful in different ways, and you can try to see which suits you best.

Focus a text field, tap the microphone on the keyboard's mic, floating mic or using the accessibility shortcut, and speak. Tap the recording panel to finish. If text cannot be inserted, use the clipboard or transcript export.

**Have an audio file?** Use the icon beside **New transcript** on Record, share it with Utterlane, or choose **Open with → Utterlane** in your file manager.

## Privacy

- Audio and transcripts are not uploaded to a speech service. No analytics or
  account is required.
- Network access is used for requested model downloads; local model import is
  also available.
- Recording history defaults to **one-hour retention**. You can disable it or
  change retention in Settings; existing choices are preserved. Saved audio stays
  private to the app and is excluded from Android cloud backup and device transfer.
- Exporting or copying a transcript shares it with the receiving app.

Read the [privacy policy](PRIVACY_POLICY.md) for permissions and retention details.

## Documentation

- [Core values](CORE_VALUES.md): the product principles guiding responsiveness,
  speed, reliability, privacy, and user ownership.
- [User guide](docs/user-guide.md): installation options, models, languages,
  speaker labels, recording history, and settings.
- [Developer guide](docs/development.md): building, testing, architecture,
  streaming, and model internals.
- [Release and store submission guide](docs/distribution/README.md)
- [QA reports](docs/qa/README.md) · [Brand artwork](docs/design/utterlane-branding.md)

## Contributing

Bug reports, translations, accessibility feedback, and code are welcome.
[Open an issue](https://github.com/lrq3000/Utterlane/issues) with your app version,
device, selected model, and steps to reproduce the problem. Remove private
speech and transcript content from shared logs.

See [CONTRIBUTING.md](CONTRIBUTING.md). **AI-assisted contributions are welcome
when humans sanity check the outputs before submission.**

## License and acknowledgements

Licensed under [Apache-2.0](LICENSE). Utterlane development is led by [Stephen Karl Larroque](https://github.com/lrq3000).

Forked from [TranSlander](https://github.com/hatsch/TranSlander), originally developed by
[hatsch](https://github.com/hatsch) and its contributors with credited assistance
from [Claude Code](https://claude.ai/claude-code) as a straightforward Android implementation of Parakeet TDT v3.

Models and third-party libraries retain their own licenses;
see [dependency acknowledgments](docs/development.md#dependency-acknowledgments).

This project would not be possible without the incredible work of the folks at [CrispASR](https://github.com/CrispStrobe/CrispASR), [Moondream](https://moondream.ai/), NVIDIA and the general AI research community.
