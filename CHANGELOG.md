# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

## [2.4.2] - 2026-11-28

### Changed
- Download or import the optional speaker model on the speaker-labeling setup page, even with labels off by default. Installing it ahead of time lets you enable labels with one tap during a recording.

### Fixed
- After a speech-model download completes, onboarding shows a clear completion message and a Continue button to move on to microphone setup without reselecting the model.

## [2.4.0] - 2026-11-27

### Added
- Drag the floating microphone onto the red × to hide it until you reopen Utterlane. The setting stays enabled, the current take goes to the transcription queue, and other jobs keep running.
- Choose **Stop all jobs and quit** from an active-work notification or the queue. Utterlane saves recoverable audio and progress before closing, without deleting completed results.

### Changed
- The floating mic keeps its shape and icon while recording, turns red, and plays a short rainbow animation after a successful tap-to-stop.

### Fixed
- The floating microphone stays above the recording panel so you can still tap or drag it in landscape and in short windows.

## [2.3.13] - 2026-11-26

### Added
- **Settings → About** shows your installed version, project credits, license, source repository, and how Utterlane processes speech on your device.

### Changed
- The keyboard mic, floating mic, accessibility shortcut, and onboarding practice mic now open the same dictation dialog. The Settings gear offers to keep recording, discard with confirmation, or let the current transcription finish in the background once recording has stopped.

### Fixed
- Interrupted model downloads show how much was saved while reconnecting. A valid partial download can resume even if the server changes its validator for a checksum-pinned model; the whole model is verified before installation.

## [2.3.10] - 2026-11-26

### Added
- A Settings gear in both keyboard dictation paths. Keep recording or explicitly discard an active take; after capture stops, continue the same transcription in the background and find the result under Transcripts.
- A removable `utterlane` → `Utterlane` correction for new dictionaries. Existing dictionaries, including empty ones, are unchanged.

### Fixed
- Visible speech and speaker model downloads keep the screen awake in onboarding and Settings without new permissions. Retry resumes safe partial downloads and reuses verified files.
- Temporary dictation audio is cleared after successful foreground delivery, including clipboard fallback, when audio history is off. Failed delivery remains recoverable.

## [2.3.4] - 2026-11-26

- Corrected the patch version to count all three functional changes since 2.3.1. App behavior is unchanged from 2.3.3.

## [2.3.3] - 2026-11-26

### Changed
- Import and Share now stack Speaker labels and Run in background, with scroll indicators when the options do not fit the window.
- Transcription progress uses icon controls separate from audio playback. Pause parks an attempt in the queue; Stop removes it from the active queue and keeps its partial result in Transcript History. Continue resumes a verified checkpoint; Restart starts a new attempt without replacing the partial.

### Fixed
- Pause and Stop no longer wait for an unfinished recognition window in plain or fixed-one-speaker mode. Continue reprocesses the uncommitted audio without replacing the text already saved. Multi-speaker mode still drains buffered words before it checkpoints.

## [2.3.1] - 2026-11-26

### Changed
- Pause, continue, stop, or restart an individual file from the transcription progress panel. Manually paused files stay paused until you continue them; restarting leaves the earlier partial transcript intact.
- The reader remembers your text size and moves backgrounding to a bottom icon. Import and Share can send files straight to the background, independently of speaker labels.
- Home shows the queue while work is active. Transcript History keeps paused jobs and unreviewed results accessible, and both surfaces open the exact attempt.

### Fixed
- Cancel and delete one job without removing another job's result or the original audio. Transcript-only imports no longer offer to delete a saved audio recording.

## [2.3.0] - 2026-11-26

### Added
- Select multiple audio files and queue them for offline transcription. Continue in the background and open each result from its own notification or from the Home queue.
- Keep dictating through the microphone while a file is processing. Live speech gets the next turn on the shared recognizer, and a new recording can start while the previous one finishes.
- With history turned off or set to Immediate, queued audio and text stay available until you review and explicitly close that result. Backgrounding the app or dismissing a notification does not discard it.

### Fixed
- Interrupted imports preserve accepted files and let you retry unreadable sources without resubmitting the batch. Interrupted transcriptions retain available audio and partial text for recovery.

## [2.2.6] - 2026-11-26

### Fixed
- On Android 13 and later, onboarding now explains how to open Utterlane’s App info and allow restricted settings if Android blocks accessibility or overlay access for an installation outside Google Play. Shortcut setup remains optional.

## [2.2.5] - 2026-11-25

### Fixed
- The voice keyboard now shows the two-person icon whether speaker labels are on or off. On affected phones the toggle worked, but its icon was invisible.

## [2.2.4] - 2026-11-25

### Fixed
- The live recording panel now uses the same round speaker toggle as Home, inside the waveform's top corner. The two-person icon is drawn directly by the live button, so it stays visible without relying on the dialog theme's foreground layer. The panel stays the same height, and tapping the toggle does not stop recording.
- Waveform bars no longer cover the finish label when it wraps at larger text sizes.

## [2.2.3] - 2026-11-25

### Changed
- Settings now groups existing controls into six pages. The dedicated **Speaker labels (diarization)** page puts model download and local import at the top.

### Fixed
- The live speaker toggle is now a compact conversation icon beside the listening title, so it no longer makes the normal recording panel taller. Long-press it for an explanation.
- Missing or unverified speaker-model warnings link directly to the speaker settings. Returning from Settings preserves the selected audio file and its speaker-label choice. Recordings can continue without labels, and fixed one-speaker mode does not need the auxiliary model.

## [2.2.1] - 2026-11-25

### Fixed
- The onboarding microphone trial now shows the keyboard's recording panel, with a live waveform, streaming text preview, speaker controls, and processing feedback. No keyboard setup is required.
- Stopping the trial preserves captured speech while transcription finishes, and cancelling no longer lets a new take start before the microphone is released.

## [2.2.0] - 2026-11-24

### Added
- Choose speaker labels before transcribing shared, opened, or picked audio. The choice is specific to the file, persists for retries, and does not change the microphone default.
- Turn speaker labels on or off beside the waveform during a live recording. Changes apply to upcoming transcription segments without stopping the microphone or rewriting earlier text.
- Blue Notebook native Home: direct recording, local audio loading, complete
  transcript reading/copy/share, a live speaker-label default, and fixed Record /
  Audio history / Transcripts navigation. Settings is accessible from the gear.
- Independent history duration/recording-time/actual-speaker metadata and passive
  pin indicators, with existing detail-level pinning and confirmed deletion.
- Native, resumable onboarding with device-aware model recommendations, optional
  permissions and speaker-label setup, appearance selection on every page, and
  real dictation/file-sharing trials. The guide can be reopened from Settings.
- Bundled vector illustrations and an attributed public-domain audiobook sample.

### Fixed
- Reopened unfinished results show **Partial transcript**, without adding the warning to copied or exported text.
- Recoverable speaker-model failures preserve recognized words and allow transcription to continue without labels.
- Preserve prior useful work when a new microphone attempt has no input or a local
  file cannot be copied; keep Home capture alive across navigation/recreation.
- Preserve interrupted labeled text metadata, canonical Android cache leases, and
  cross-owner Keep/delete identity consistency during recovery and explicit saving.
- Model-download cancellation no longer waits for a stalled DNS resolver.
- Folder monitoring uses a FileObserver constructor compatible with Android 8/9.

## [2.0.0] - 2026-10-04

### Added
- Incremental microphone and file transcription with bounded audio queues,
  recoverable completed text, and processing feedback.
- Optional local recording history, replay, export, and configurable retention.
- Moondream Parakeet Ultra/Redux recognition through the pinned CrispASR backend.
- Compact native ternary Redux, custom CrispASR model imports, streaming speaker
  labels, persistent app-language selection, and configurable idle model unloading.
- F-Droid packaging preparation and free Google Play release-bundle workflow.
- GitHub Releases, Obtainium, and Komi Store installation instructions.

### Changed
- Rebrand the application, first-party source packages, JNI library, localized
  labels, release artifacts, and documentation as Utterlane.
- Use `io.github.lrq3000.utterlane` as the new Android application identity.
  This installs separately; upstream app data and permissions are not migrated.
- Apply the supplied Utterlane wordmark and icon to launcher, Settings,
  notifications, README and store artwork; archive the original design.
- Document maintenance by Stephen Karl Larroque, upstream lineage, and the
  policy welcoming AI contributions sanity checked by humans.
- Target Android 16 / API 36 while retaining Android 8 / ARM64 support.
- Build sherpa JNI and bindings with a portable Python tool; use the verified
  official ONNX Runtime 1.23.2 Maven artifact and check 16 KB native alignment.
- Publish release-signed APKs and Play bundles instead of debug APK releases.
- Refresh English Settings/transcription screenshots and clarify the optional
  accessibility service disclosure, with an in-app privacy-policy link.

### Fixed
- Preserve active transcription sessions across device sleep and allow completed
  audio to drain after capture stops.

Store publication is pending maintainer submission and store review. This entry
describes the prepared release, not an already available store listing.

Earlier entries and release links below belong to the upstream TranSlander
history and are retained as historical records.

## [1.2.4] - 2026-02-07

### Fixed
- Boot notification UX: tap to start services silently via trampoline activity
- Thread safety: ReadWriteLock on native recognizer, ConcurrentHashMap for debounce jobs
- Memory: replace boxed MutableList<Short> with ShortArray chunks in AudioRecorder and AudioDecoder
- Fix text injection into wrong field (remove aggressive editable+focusable fallback)
- Fix AudioMonitorService state tracking with static isRunning flag

### Changed
- Service notification: swipeable (IMPORTANCE_MIN), no stop button
- Boot alert notification: auto-cancel on tap, IMPORTANCE_LOW
- Recording overlay adapts to system dark/light mode
- Lazy-init OkHttpClient to avoid idle thread pools
- Extract hardcoded English strings to resources
- Remove dead code in RecognizerManager.initialize()

## [1.2.3] - 2026-02-04

### Fixed
- Thread safety: use AtomicBoolean for recording state, @Volatile for singleton
- Replace deprecated onBackPressed() with OnBackPressedDispatcher
- Remove deprecated AccessibilityNodeInfo.recycle()/obtain() calls
- Fix mutex scoping and properly cancel debounce jobs
- Fix race condition in recognizer initialization

### Changed
- Use CopyOnWriteArrayList for file observers
- Replace Handler.postDelayed with coroutine delay
- Add explicit ServiceInfo type for Android 14+ foreground services
- Remove non-functional language setting
- Dismiss audio notification when closing transcription popup

## [1.2.2] - 2026-02-03

### Fixed
- Fix folder monitor 6-hour timeout on Android 15+ (switch to specialUse FGS type)
- Clean up dead code for minSdk 26 compatibility

### Changed
- Extract ServiceAlertNotification for better code organization
- Add documentation for Android 14/15/16 FGS restrictions

## [1.2.1] - 2026-02-03

### Fixed
- Harden service startup for Android 14+ boot restrictions
- Show notification when service fails to start at boot (tap to open app and restart)
- Request notification permission when enabling floating mic or folder monitor (Android 13+)

## [1.2.0] - 2026-02-02

### Added
- Import model from local folder instead of downloading over the internet
- Separate "Download model" and "Import from folder" options in UI
- Copy progress indicator ("Copying to app storage")
- User-friendly localized error messages for checksum, network, and storage errors
- Model file validation on resume and before loading
- NVIDIA Parakeet TDT CC-BY-4.0 attribution

### Fixed
- Wrap browser intent in try-catch for devices without a browser app
- Prevent UI from getting stuck if model files are deleted outside the app

## [1.1.1] - 2025-05-01

### Fixed
- Catch `UnsatisfiedLinkError` in speech recognizer initialization
- Guard foreground service startup against `IllegalStateException` on Android 12+
- Prevent `Application.onCreate()` crash if background services fail to start at boot

## [1.1.0] - 2025-04-15

### Added
- UI localization for 23 languages (Bulgarian, Croatian, Czech, Danish, Dutch, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Slovak, Slovenian, Spanish, Swedish, Ukrainian)
- Store listings in German, French, Spanish, Italian, and Portuguese

### Fixed
- Language count in descriptions (25, not 26+)

## [1.0.2] - 2025-02-10

### Fixed
- Fix floating button crash when overlay permission is missing
- Overlay permission setting now always visible with tap-to-manage shortcut
- Folder monitor automatically restarts when adding or removing watched folders

## [1.0.1] - 2025-02-05

### Added
- Accessibility service disclosure dialog for Google Play compliance

### Fixed
- Fix model download source in privacy policy (HuggingFace, not GitHub)

## [1.0.0] - 2025-02-01

### Added
- Offline speech recognition using Parakeet TDT v3 model (~600MB)
- Floating microphone button (draggable overlay)
- Accessibility button support (system navigation bar)
- Voice message transcription — share audio files from WhatsApp, Signal, etc.
- Word corrections feature — fix recurring recognition errors with custom rules
- Audio monitor — watches Downloads folder for new voice messages
- Support for OPUS, AAC, OGG, M4A, MP3 audio formats
- Multi-language support (25 languages with auto-detection)
- Material 3 UI with dark/light theme support
- Keyboard integration for HeliBoard and others
- Model integrity verification with SHA256 checksums

[1.2.2]: https://github.com/hatsch/TranSlander/compare/v1.2.1...v1.2.2
[2.0.0]: https://github.com/lrq3000/Utterlane/releases/tag/v2.0.0
[1.2.1]: https://github.com/hatsch/TranSlander/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/hatsch/TranSlander/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/hatsch/TranSlander/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/hatsch/TranSlander/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/hatsch/TranSlander/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/hatsch/TranSlander/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/hatsch/TranSlander/releases/tag/v1.0.0
