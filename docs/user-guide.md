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

## First-launch setup guide

New installations open a step-by-step guide explaining dictation, shared audio,
and meeting transcripts. Use the top-right **Appearance** selector on any page
to choose **System**, **Light**, or **Dark**. Incomplete setup resumes after a
restart. Previously configured installations continue to Settings; choose
**Setup guide** there to revisit the introduction or try-it pages.

The model recommendation uses Android-reported total RAM, not free RAM:

| Total RAM | Suggested model |
| --- | --- |
| Up to and including 1 GB | Native ternary Parakeet Redux |
| Above 1 GB, up to and including 2 GB | Parakeet Ultra Q4 |
| Above 2 GB | Parakeet Ultra Q8 |

The thresholds use binary gigabytes. You can choose any featured model, including
the original Parakeet v3. A recommendation does not guarantee memory availability.
**Next/Download** installs the selected model; progress, retry and cancellation
are shown on a separate page. **Import from folder** supports offline setup,
including the optional speaker model.

Microphone access is optional for people who only transcribe files. Keyboard,
floating-mic and accessibility shortcuts can be configured later. Folder
monitoring requests audio access only when selected; choose a supported local
folder or **Use Downloads**. You can remove unavailable folders from the list.
Speaker labels are also optional. Automatic or multiple-speaker labeling needs
an additional model (about 107 MB). A saved one-speaker setting labels everything
as Speaker 1 without that model; replay retains the count and skips its download.
Replaying the guide retains existing settings unless you explicitly change them.

The optional **Try your voice** page records directly into an editable text field
without a keyboard setup. Practice recordings stop after two minutes and follow
the app's current history-retention setting. Enunciate clearly and keep the
microphone close for better results in noise.

The sharing trial includes a nine-second public-domain LibriVox reading from
*Alice's Adventures in Wonderland*, sourced through Wikimedia Commons. Its
recording and attribution are bundled with the app. Share it to Utterlane through
Android's share sheet to see a real transcription. Both trials have a **Skip**
action. The final page summarizes all choices in a vertically scrolling list.

## Microphone input and Bluetooth

Open **Settings → Microphone → Audio input → Change** to choose the phone microphone
or an available external microphone. Compatible Bluetooth, wired and USB inputs
appear when Android makes them available; a paired speaker without a recording
input is not a microphone option. Pair/connect headsets through Android settings.
Utterlane uses audio-routing APIs without scanning for or pairing Bluetooth devices.

**Always prefer a Bluetooth microphone** is off by default. When enabled, a connected
Bluetooth microphone is selected automatically for the next recording, including
when it connects after Utterlane starts. With several headsets, the current usable
Bluetooth selection is retained. You can select another one. Explicitly selecting
Phone or a wired/USB input also turns off automatic Bluetooth preference.

When a selected microphone disappears, the selector resets to Phone (or another
available Bluetooth microphone when automatic preference is on). With automatic
preference off, a disconnected selection is not restored just because it reconnects.
Devices without a stable identity supplied by Android may also reset after an app
process restart; enable automatic preference if any Bluetooth microphone is suitable.

Settings changes and newly connected microphones apply to the **next recording**.
The small **Input** caption in the waveform panel reports the actual recording input.
At startup, the phone can capture while an already-selected Bluetooth headset
activates; the caption identifies this connecting state rather than claiming the
headset is already recording.

If the active external microphone disconnects or becomes unusable, Utterlane attempts
to continue the **same recording using the phone microphone**. A persistent **red
message** explains the fallback. The current session stays on Phone even if the
headset reconnects; automatic Bluetooth selection still applies to the next session.
The warning is visible with stream statistics disabled and remains through processing.

This is particularly useful for unattended meeting or lecture recordings. Already
captured audio and transcription progress are preserved during handover. Android or
the hardware may introduce a short gap, and calls or microphone restrictions can
prevent even the phone from capturing. If fallback also fails, the app reports the
capture failure and uses its existing recording-recovery flow; it does not silently
claim that recording continues. Stop and Cancel always take precedence over recovery.

## Models and languages

Select a recognition model in Utterlane, then download it or use **Import from
folder** if you already have the required files. Once installed, recognition
works without a network connection.

| Model | Approximate download |
| --- | --- |
| NVIDIA Parakeet v3 | 670 MB |
| Moondream Parakeet Ultra | 674 MB or 402 MB |
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
- **Diarization:** streaming preset (default **Low latency**), buffered-step batching (default 16, strict
  schedule 1), probability/margin thresholds, ordinary and strong confirmation,
  same-speaker gap bridging (default 1 s), label lookahead, timestamp tolerance,
  and fallback word intervals when native ends are unavailable. These trade
  certainty/context against latency; changing them does not resize ASR windows.
- **Audio/capture:** ASR window and context, silence cuts, queued-audio budget,
  microphone buffer/read sizing, and wake-recovery delays. Window plus context
  must fit the fixed 12-second transport limit.
- **Downloads:** connection and read timeouts, including disabling a timeout;
  cancellation still interrupts blocked network I/O.
- **Experimental:** bounded speaker cache, FIFO and update cadence (defaults:
  **192 / 192 / 160 frames**), plus local diagnostics. Cache reductions can hurt
  returning-speaker accuracy. Saved choices are preserved; reset this group to
  adopt the current defaults.

Under **Appearance**, **Show transcription stream statistics** is off by default.
Enable it to show the latest processing stage, awake elapsed time, time since
completed progress, and audio backlog in recording/file views. The waveform,
normal progress and controls remain available when statistics are hidden. This
display preference is independent of diagnostic logging. A long-running
operation is not terminated merely because 90 seconds passed. **Force unload /
reset recognition** remains available to recover from genuinely stuck native work.
Model **idle unloading** is a separate setting and does not interrupt active
sessions.

**Appearance → Maximum visual refresh rate** offers 1, 2, 5, 10, 20, 30,
**60 (default)**, 90, or 200 updates per second. Lower values reduce waveform and
routine progress-display work on older devices. The waveform keeps the latest 64
microphone blocks and advances whenever new audio arrives, independently of this
setting. Lower refresh rates show larger steps between frames rather than slowing
the waveform's progression. Recording, recognition, speaker labeling, and final text are unaffected;
important control, signal-warning, and completion changes appear immediately.
Each block contributes its own amplitude point; it is not averaged with adjacent
blocks into a slower history. The displayed time span depends on microphone block
sizes. These are maximum update rates; actual drawing also depends on incoming
audio and the device display. Previously saved frequency choices are preserved.

Optional diagnostics are local and off by default. Enable them for a new run,
then use **Share diagnostics** to export a content-free snapshot, or clear the
stored logs. See the [privacy policy](../PRIVACY_POLICY.md#optional-local-diagnostics).

## Recording and history

Text arrives in completed speech segments, rather than instantly word by word.
Pauses, the selected model, and device speed affect how soon results appear.
The recording panel provides a waveform, low/no-signal feedback, processing
progress, and an estimated remaining time after stopping.

Under **Audio and transcript histories**, configure the two histories independently:

- **Automatically save microphone audio:** on by default, with one-hour retention.
  Existing automatic-history opt-outs are preserved.
- **Automatically save completed transcripts:** on by default, with **24-hour**
  retention selected. Every completed re-transcription creates a new text entry,
  even when the audio and model are unchanged. Earlier results are not overwritten.
  An explicitly saved opt-out remains off after an update.
- **Delete unpinned entries after:** a separate duration for each history, ranging
  from Immediate to Forever. Turning automatic saving off does not prevent manual saves.

Audio uses approximately **115 MB per hour** for microphone PCM; saved text is
independent and remains usable after its source audio expires. Deleting a text
entry does not delete audio, and deleting audio does not delete saved text.

**Audio history** and **Transcript history** open full-screen pages with the app
logo and a Back arrow. Scroll to browse: older entries load automatically as you
approach the bottom, and earlier pages load again when you scroll back up. There
are no page buttons. Opening a record and returning, or recreating the screen,
retains the browsing position. The list keeps a small window of previews in memory
instead of reading every transcript while you explore a large history.

### Pins and manual saves

Manual saving to history **pins the item forever**, without duplicating an already
saved source/attempt. Both histories show an outline pin for normal entries and a
filled accent-colored pin for permanent entries. Tap the pin to toggle it.
History lists group compact entries by date. Tap anywhere on an entry to open it;
the pin acts independently. Deletion is available only inside the opened item,
so the lists have no separate Open or Delete buttons. Retention captions are omitted
from rows; the icon and accessibility state indicate pinning without using another line.
Unpinning starts a fresh retention countdown **from the time of unpinning**, not
the original date. Under Immediate retention, the item waits until the next genuine
user-facing app launch and is skipped by background cleanup; repinning cancels that
pending expiration. Pinned entries can still be explicitly deleted.

Pruning uses metadata at startup and scheduled background intervals matching each
finite retention setting. Opening/closing ordinary dialogs or refreshing lists
does not scan and prune history. Forever has no periodic expiration job. Android
can delay jobs while asleep or force-stopped, so a deadline is eligibility for the
next cleanup opportunity rather than an exact deletion time.

Capture starts while the selected model loads in the background. The panel shows
recording and model loading separately. Microphone audio is buffered in private
files even with history disabled, keeping RAM bounded. Speech recognition and
enabled speaker labeling continue incrementally; a slower device can catch up
after you tap Stop. Speaker labeling is never automatically disabled for speed.

A model-loading or recognition failure leaves capture running. After stopping,
recovery opens the same transcription dialog used for shared audio. Settings and
recording-specific notifications also provide access. **Choose transcription model**
opens the actual picker, with the same audio available when you return.

Explicit Cancel/Discard deletes temporary work; unexpected interruption preserves
it for recovery. Closing a temporary dialog discards its working audio, including
after a failed or successful retry, so use **Save audio** first if you want to keep
it. Closing a saved-history entry leaves it in history. Saved recovery audio expires
normally unless pinned. Text already inserted, exported or saved in transcript
history is independent of the temporary audio's lifetime.

### One transcription dialog

While text arrives, a compact progress dock below the reader shows the current
stage, percentage of audio transcribed, and approximate time remaining. You can
keep reading and scrolling without moving the progress into the text. Completion
reduces the dock to a confirmation row, giving space back below the reader rather
than shifting its top or resetting your reading position.

Imported duration estimates are marked **≈**. If duration is unknown or contradicted
by decoded audio, the app shows an indeterminate bar until the actual total is
known. **Estimating time…** means no reliable timing measurement is available yet.
With speaker labeling, **About … + finishing** estimates the remaining audio
processing while making additional finishing time explicit; the final drain is
shown as **Finishing speaker labels…**, not premature 100% completion. Progress
and ETA remain visible with stream statistics disabled.

Shared audio, history and recovery use the same actions:

- **Re-transcribe:** use the same model, or choose another model and retry.
- **Save audio:** save to history (pinned forever), share with another app, or save
  to a device/document-provider destination. Multipart recordings use a chosen folder.
- **Copy / Share transcript / Keep transcript forever:** operate on text separately
  from audio, with manual text saving available even when automatic text history is off.
- **Close**, and **Delete recording/transcript** or **Discard** according to ownership.

Temporary private copies of shared audio support retries and playback; discarding
them never deletes the original file supplied by the sending app. A cancelled
destination picker or failed save does not falsely report that a copy was saved.

Press **Play** to reveal Pause/Resume, Stop playback, a seek bar, and elapsed/total
time. Seeking while paused keeps playback paused; Stop or reaching the end resets
and collapses the player. Hourly audio parts appear as one timeline. Playback is
local and independent of recognition, pauses on focus/headphone/background changes,
and uses the visual-refresh preference for position updates. A transcript whose
source expired remains readable; audio-dependent actions explain its absence.

If the microphone or storage itself fails, recording stops with an error and the
successfully saved portion remains available. Storage-writer overload drains its
bounded buffer, including the block that detected overload, before finalization.

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
