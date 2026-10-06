# Utterlane user guide

[Back to README](../README.md) · [First dictation](../README.md#your-first-dictation) ·
[Developer guide](development.md)

## Installation and updates

Use an **Android 8.0+ device with ARM64 support** and leave room for a speech model
(approximately 159–674 MB for the catalog models; custom models vary).

### GitHub Releases

Open [GitHub Releases](https://github.com/lrq3000/Utterlane/releases/latest),
download `Utterlane-<version>-arm64-v8a.apk` from **Assets**, and open it to install.
If Android asks, allow installation from your browser/file manager. Use the APK;
the `.aab` is a Google Play upload artifact and cannot be installed directly.
The release APK becomes available after the maintainer publishes the first signed
release; until then, you can [build from source](development.md#build-from-source).

### Obtainium

For automatic update tracking with [Obtainium](https://github.com/ImranR98/Obtainium),
use the button in the README, or open **Add app** and enter
`https://github.com/lrq3000/Utterlane`. Select the ARM64 release APK if prompted,
then add/install the app. Obtainium tracks new releases from this repository.

### Komi Store

In [Komi Store](https://github.com/komi-store/komi-store), search for
`lrq3000/Utterlane`, open the matching GitHub project, and select its Android APK
release. The maintainer is **lrq3000** and the application ID is
`io.github.lrq3000.utterlane`. A published release with an APK asset is needed
before installation is possible.

### Stores and switching channels

Publication on **F-Droid** and **Google Play (free)** is planned. Once published,
search for **Utterlane** in the official F-Droid repository or Google Play and
verify the application ID `io.github.lrq3000.utterlane`.

GitHub/Obtainium/Komi Store use the same developer-signed APK. An F-Droid build
normally has an F-Droid signature, and Google Play may use a separate Play App
Signing key. Switching between differently signed builds requires uninstalling
the old app first, which removes its private data. Export anything you need first.

Utterlane installs separately from its predecessor, TranSlander. Settings, model
files, recording history, and permissions do not migrate automatically.

## Models and languages

Select a recognition model in Utterlane, then download it or use **Import from
folder** if you already have the required files. Once installed, recognition
works without a network connection.

| Model | Approximate download |
| --- | --- |
| NVIDIA Parakeet v3 | 670 MB |
| Moondream Parakeet Ultra (first-launch default) | 674 MB or 402 MB |
| Moondream Parakeet Redux | 674 MB or 402 MB |
| Moondream Parakeet Redux — compact native ternary | 159.1 MB |

The app needs working memory in addition to storage for the download. Speed and
accuracy depend on your device and model. For formats, backends, and measurements,
see the [technical model guide](development.md#recognition-backends-and-models).

### Recognition languages

Speak naturally in any of the 25 supported languages—Utterlane detects the
language automatically. You can switch languages within a single recording:
start in English, continue in French, then switch to Spanish. Each part is
transcribed in its spoken language without selecting a language or restarting.

Supported languages: Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian,
Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese,
Polish, Portuguese, Romanian, Russian, Slovak, Slovenian, Spanish, Swedish, and
Ukrainian.

### App language

Under **Appearance**, select **System (device language)**, **English**, or any of
the 23 packaged translations. The choice persists across restarts and is
independent of speech recognition language. Android 13+ also exposes supported
languages in its system per-app language settings.

Existing interface translations were machine-generated; corrections and
completion of newer strings are welcome. New settings use English fallback
until the project's translation batch.

### Custom models

1. Choose **Recognition model → Custom model…**.
2. Select the CrispASR-compatible speech model and its companion files together,
   then choose the main file.
3. For models requiring an explicit audio tokenizer/codec (such as MiMo-ASR),
   assign that companion in the next step; otherwise retain automatic sibling
   discovery.
4. Use **Load** to check compatibility and warm up the model.

You need compatible converted weights, all required companion files, and enough
device memory. Missing companions are not downloaded automatically. TTS/music
models cannot be used for speech input. See the
[developer guide](development.md#custom-models) for supported formats and behavior.

## Word corrections

Use your own whole-word replacement rules to fix recurring names and recognition
mistakes. Corrections are applied after speech recognition.

## Speaker labels

Enable **Speaker diarization → Add speaker labels** and download the separate
NVIDIA Nemotron-3-Diarization model (107 MB), or import its GGUF from a folder.
This is **off by default** and does not require replacing your speech model.

Choose **Auto (up to 8)** or **1–8** speakers. Labels appear during microphone and
file transcription and are included in copied/shared transcripts and text
inserted into other apps. Labels apply to one recording; uncertain speech can
appear as **Unknown speaker**. Selecting a count does not create speakers who
are not present. Settings changes take effect on the next recording.

Speaker labeling adds processing work, so performance depends on your device.
One-speaker mode labels the recognized text directly without loading the separate
speaker model. Auto is still available for detecting an unknown number of voices.
The recognizer runs once per audio window; speaker decisions annotate its words
instead of cutting speech into tiny pieces for additional recognition passes.

## Advanced recognition options

Expand **Advanced recognition → Runtime options** to edit grouped settings.
The section starts collapsed. Each group has contextual help, saved/default
values, a validated draft, and reset controls. Related values are applied
together; active operations retain their starting snapshot.

- **Recovery:** worker connection timeout (30 s), model preparation and inference
  without-progress limits (300 s each), and an optional absolute operation limit
  (disabled). Enter **0** to disable a limit. These are awake/interactive-time
  budgets, paused during sleep/screen-off; they are not recording-length limits.
  Actual completed native work renews the stall budget. A backend with no internal
  progress remains subject to the configured opaque-call fallback, so unusually
  slow models can require a larger limit or disabling it.
- **CPU:** separate ASR and speaker-model thread counts. Defaults remain four;
  Auto uses up to four available processors. More threads are not always faster.
- **Diarization:** streaming preset, buffered-step batching (default 8, strict
  schedule 1), probability/margin thresholds, ordinary and strong confirmation,
  same-speaker gap bridging (default 1 s), label lookahead, timestamp tolerance,
  and fallback word intervals when native ends are unavailable. These trade
  certainty/context against latency; changing them does not resize ASR windows.
- **Audio/capture:** ASR window and context, silence cuts, queued-audio budget,
  microphone buffer/read sizing, and wake-recovery delays. Window plus context
  must fit the fixed 12-second transport limit.
- **Downloads:** connection and read timeouts, including disabling a timeout;
  cancellation still interrupts blocked network I/O.
- **Experimental:** bounded speaker cache, FIFO and update cadence, plus local
  diagnostics. Cache reductions can hurt returning-speaker accuracy.

The recording/file UI reports the latest processing stage, awake elapsed time,
time since completed progress, and audio backlog where available. A long-running
operation is not terminated merely because 90 seconds passed. **Force unload /
reset recognition** remains available to recover from genuinely stuck native work.
Model **idle unloading** is a separate setting and does not interrupt active
sessions.

Optional diagnostics are local and off by default. Enable them for a new run,
then use **Share diagnostics** to export a content-free snapshot, or clear the
stored logs. See the [privacy policy](../PRIVACY_POLICY.md#optional-local-diagnostics).

## Recording and history

Text arrives in completed speech segments, rather than instantly word by word.
Pauses, the selected model, and device speed affect how soon results appear.
The recording panel provides a waveform, low/no-signal feedback, processing
progress, and an estimated remaining time after stopping.

Microphone history defaults to **one-hour retention**, so saved audio is available
for recovery, replay, sharing, or retranscription. You can delete recordings,
disable history, or change retention from one hour to forever. Existing retention
choices, including disabled history, are preserved. Recordings use approximately **115 MB per hour**.
Android can delay background cleanup while asleep or force-stopped.

If your device cannot keep up with recognition while history is disabled,
recording stops visibly and accepted audio finishes processing. With history
enabled, saved audio also lets processing catch up with the recording.

Saved history is private to the app and excluded from Android cloud backup and
device transfer. Exports and clipboard transfers give data to their receiving
apps, which have their own privacy behavior. See the
[privacy policy](../PRIVACY_POLICY.md) for retention details.

## Model memory

In **Speech Model → Idle time before unload**, choose **Immediate**, **5 min**,
**20 min** (default), **1 h**, **3 h**, **24 h**, or **Never**.

Unloading frees model memory without deleting downloaded files. The next
transcription reloads the model automatically. Recording and processing keep
the model loaded; the idle countdown begins when work finishes or is cancelled.
Loading a model without transcribing also starts the countdown. Changing the
timeout applies to time already spent idle, including device sleep.

**Immediate** also skips startup auto-loading. **Never** disables automatic
unloading, although Android can still close the app process. If a model fails or
becomes stuck, you can unload/reset recognition without force-closing the app.

## Audio files and folder monitoring

Share an audio file with Utterlane or choose **Open with → Utterlane** in your file
manager. Supported formats include OPUS, AAC, OGG, M4A, MP3, and WAV, subject to
your device's codec support. Results appear before the whole file finishes.

To monitor saved Signal messages, open **Voice Message Transcription**, add the
folder where Signal saves audio (for example, `Music/Signal`), and enable folder
monitoring. Save a voice message from Signal to that folder; Utterlane detects it
and offers transcription.

## Privacy settings

Speech recognition stays on your device. Network access is used for requested
model downloads from Hugging Face; local model import is also available. Devices
with per-app network controls can disable Utterlane's network access after model
setup.

## Compatibility

- AOSP Keyboard does not offer this voice-input integration; try a compatible
  keyboard such as [HeliBoard](https://github.com/Helium314/HeliBoard).
- Upstream reported voice-result integration problems with Vanadium;
  compatibility depends on the receiving app.
- Recognition accuracy depends on speech, background noise, language, and model.
  The app is provided under the warranty terms of its [license](../LICENSE).

For help, [open an issue](https://github.com/lrq3000/Utterlane/issues) with your
app version, Android/device details, selected model, and reproducible steps.
Remove private speech or transcript content from shared logs.
