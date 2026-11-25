# Settings navigation preservation checklist

Baseline: the Settings screen at `5c0d18d6`. The new directory changes navigation, not preference keys, defaults, or ownership. Conditional controls remain conditional.

| Destination | Controls from the previous screen |
| --- | --- |
| Speech recognition | Built-in and custom speech model selection, download, local import, load/unload, force unload, cancel transfer, delete confirmation, model attribution, auto-load and idle timeout; dictionary enable and add/remove word corrections; all advanced runtime option groups, presets, validation, resets and local diagnostics. |
| Speaker labels (diarization) | Default speaker-label switch, Auto or fixed speaker count, model information and readiness, download/local import, transfer progress and cancellation, delete, and transfer error details. |
| Voice input | Microphone permission and app-permission settings, all input device and microphone processing controls (including HFP and diagnostics), Android keyboard voice input, accessibility disclosure/system settings, floating microphone enable, overlay permission, and size hint. |
| Audio files & folders | Audio-file permission, folder monitoring, default Downloads location, custom folder picker/add/remove, and Share/Open instructions. |
| History & storage | Audio and transcript history actions, automatic retention, reset import-retention choices, transcript recovery, managed audio/Markdown destinations and status, and audio quality/compression. |
| Appearance & language | App language, theme, visual refresh rate, and transcription stream-statistics display. |
| Settings directory | Run onboarding again, open the privacy policy, and navigate to all six destinations; Home navigation remains available. |

Legacy speech-model recovery still opens the speech model selector. A speaker-model link opens the speaker page directly without starting a transfer; returning from file setup preserves that file and its speaker choice. During external keyboard dictation, its explicitly labeled Finish and open action delivers the result before navigating.

LDPlayer QA: If the verified guest passes its boot and package-manager probes but the default `adb install` stalls, use `adb -s <verified-serial> install -r --no-streaming <apk>` for both the isolated app and instrumentation APKs. This upload-then-install path succeeded on the same guest where two default installs timed out before instrumentation.
