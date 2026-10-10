# AudioRecorder: 28-commit applicability record

Source: [lrq3000/AudioRecorder, feat/bluetooth-mic-experiments](https://github.com/lrq3000/AudioRecorder/tree/e1c802060bcf29b8c66a6b3f27339e857289a37d).
All 28 commits in `fab9cb1..e1c8020` were reviewed before the approved design.
Utterlane implementation starts from Bluetooth lifecycle hardening `bddcde6`.
The [design](../superpowers/specs/2026-10-09-audiorecorder-ports-design.md)
records the agreed adaptations and subsequent pinch-only clarification.

“Adapted” means the relevant behavior was implemented in Utterlane's existing owners,
not that a donor commit was cherry-picked wholesale. “Deferred” identifies a separate
workflow outside the approved port. This record does not claim unimplemented features.

| # | Donor commit | Subject | Utterlane disposition |
| --- | --- | --- | --- |
| 1 | `c31f7a6` | Floating recorder overlay | Existing floating microphone retained; adapted in-place resizing and gesture/lifecycle safeguards rather than introducing a second recorder service. |
| 2 | `c27c75a` | Theme floating rename text | Deferred with rename overlay; existing Compose theme remains. |
| 3 | `b17329a` | Floating overlay pinch resize | Adapted proportional two-finger resizing, persistence, display clamping and tap suppression. User subsequently selected pinch-only resizing and removal of the Settings size menu. |
| 4 | `bc1b5e4` | Rename overlay speech input | Deferred: rename workflow is outside scope. |
| 5 | `c95af17` | Recognizer intent for rename speech | Not adopted: external speech delegation is unnecessary for this offline transcription workflow. |
| 6 | `62ed191` | Polish rename actions | Deferred with rename workflow. |
| 7 | `31eada7` | Keep rename keyboard hidden | Deferred with rename workflow. |
| 8 | `8222e62` | Gate rename by stop source | Deferred rename UI; preserve Utterlane's existing Stop/Cancel and recovery ownership. |
| 9 | `0b04a87` | Upstream v2.1 | Reviewed recorder release/thread-safety and description work. Retain Utterlane's worker-owned capture, bounded waveform and raw spool; descriptions/tag editing are deferred. |
| 10 | `f66f82e` | Gradle/Kotlin/dependency upgrades | Not ported: unrelated build-stack migration. |
| 11 | `baccf9b` | Upstream v2.2 improvements | Adapted decoder handling for absent/malformed optional metadata, valid track selection, and storage-full reporting. Format-selection and migration workflows are outside scope. |
| 12 | `21c9d03` | Version 2.2.0 / 945 | Donor version only; no Utterlane version change. |
| 13 | `fbc61a4` | Version 2.2.1 / 946 | Donor version only; no Utterlane version change. |
| 14 | `03a042b` | Upstream v2.3 | Bluetooth routing informed the existing lifecycle hardening; retain generation-fenced, session-owned routing. Filename templates and compressed-recording restoration are deferred. |
| 15 | `e348cce` | Upstream v2.4 | Adapted playback-speed selection using the existing MediaPlayer controller. No ExoPlayer migration; record search and compressed-recording restoration are deferred. |
| 16 | `802bec8` | Upstream v2.5 | Adapted pause-before-capture behavior and reviewed source/cleanup safeguards. Preserve bounded PCM capture; AAC/M4A capture, system-playback recording and analytics are not adopted. |
| 17 | `45ee88c` | Append rename speech to notes | Deferred naming/notes workflow. |
| 18 | `464a04b` | Preserve description drafts | Deferred with notes UI; existing transcription/recovery data preservation remains mandatory. |
| 19 | `ea6e6cd` | Compact description field | Deferred with notes UI. |
| 20 | `21c8cb2` | Sanitize filename speech | Deferred speech-driven naming. |
| 21 | `b7c4dbe` | Clean filenames on save | Reviewed; donor naming behavior is outside this port. Retain current export naming and source ownership. |
| 22 | `115204a` | Linear-replay merge adaptations | Donor system-audio consent and rename/recovery integration; not a standalone Utterlane feature. |
| 23 | `db1926f` | Drag floating recorder to close | Deferred: dragging must not unexpectedly terminate or discard a recording. |
| 24 | `6dd99b8` | Overlay binding across reconnects | Adapted the ownership intent through existing service/session ownership, without introducing the donor binding architecture. |
| 25 | `5ca80cf` | Microphone experiments and PCM gain | Adapted source/effect/gain choices. Approved difference: software gain runs on derived transcription buffers, leaving original captured PCM in history. |
| 26 | `ee3e5c6` | Bluetooth experiments and diagnostics | Adapted explicit HFP voice recognition, route/mode options and local diagnostics; retain Phone capture during negotiation and sticky fallback instead of stopping on HFP failure. |
| 27 | `d838368` | Enhanced HFP preset persistence | Exact default tuple: `VOICE_RECOGNITION / HFP_VOICE_RECOGNITION / NORMAL / AGC_ONLY / AUTO_LEVEL`, atomically persisted; input selection stays independent. |
| 28 | `e1c8020` | Preset selector and collapsible controls | Disabled/HFP/Custom, collapsible Custom controls, reset and independent diagnostics. Explicit permission actions and actual/requested state remain distinct. |

## Utterlane-specific correctness decisions

- Preserve original PCM and per-recording gain metadata; retries process the same
  derived signal, while old/imported audio defaults to no gain.
- Capture configuration and route ownership are per session. Late callbacks, Stop,
  removed endpoints and contradictory device identity cannot revive an old route.
- HFP activation waits for actual link/recording evidence, has an eight-second limit,
  and never silently substitutes another headset or Standard SCO.
- Gain, effects and diagnostic data stay local; optional DSP failure does not make
  capture depend on a working enhancement. Device addresses are redacted.
- Floating pinch changes geometry in place. Sizes are bounded and saved in dp;
  legacy presets are read for upgrades. No new Settings access restriction or
  recording-panel visibility rule was requested or implemented.
- Playback speed does not resume a paused player. Accepted microphone admission
  pauses current or pending app-owned playback without discarding its source lease.
- Storage error classification preserves other exceptions and saved-prefix recovery.

See [Bluetooth/input QA](bluetooth-input.md) for verification and hardware limits.
