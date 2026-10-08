# Recording-first home — three interactive proposals

These are **design-review artifacts**, awaiting selection. They explore replacing
the post-onboarding Settings landing screen with a useful recorder and transcript
workspace. The user can record in the app without configuring a keyboard,
accessibility shortcut, or floating microphone first.

## Open the study

From this worktree's root, serve the repository using an explicit directory:

```text
python -m http.server 8774 --bind 127.0.0.1 --directory .
```

Use an absolute `--directory` if launching a detached server. Open:

- [A / Focus](http://127.0.0.1:8774/docs/design/home-preview/?concept=focus)
- [B / Notebook](http://127.0.0.1:8774/docs/design/home-preview/?concept=notebook)
- [C / Studio](http://127.0.0.1:8774/docs/design/home-preview/?concept=studio)

The ES modules require HTTP serving. There is no package installation, build step,
remote font, third-party script, or external asset request. The source-derived
Utterlane wordmark and the Blue harmony light/dark palette are reused directly.

## Design alternatives

| Concept | Composition | Strength | Trade-off |
| --- | --- | --- | --- |
| **Focus** | Transcript card, app-wide speaker switch, waveform, two history shortcuts | Balanced first-use experience; each feature has an explicit label | Smaller reading area |
| **Notebook** | Open document canvas, lower recording dock, primary bottom navigation | More text visible; recorder stays near the thumb | History destinations have less explanatory copy |
| **Studio** | Library strip above the transcript, compact recording console below | Efficient, clearly organized workspace for frequent use | Denser and more utilitarian |

Focus is the initial recommendation for the broadest audience. Each concept has
both light and dark modes, and all three default to light. The waveform retains the
existing violet–indigo–blue recording identity. There are no glows, ambient motion,
new branding assets, or external image-generation dependencies.

## Try the interactions

1. Tap the initially flat waveform. The button becomes a stop control, its sample
   levels animate, and sample text arrives in chunks without focusing the text.
2. Tap again. A finite simulated processing stage shows progress and an approximate
   remaining time in the same control area. It then returns to a flat waveform
   labelled **New recording**, with Copy, Share and additional text actions.
3. Start another recording. Finished sample sessions remain in both demo histories.
4. Open either history and inspect an entry. Open Settings using the gear and
   return home. Navigating during a session preserves the session.
5. Toggle **Speaker labels** on Home or in Settings. The preference is synchronized
   across all three concept tabs via local storage and applies to new recordings.
   Existing results keep the speaker-label choice with which they were created.
6. Try importing a sample audio file, the share preview, appearance switching,
   360/390/430-pixel phone widths, 125% text, and the direct state selector.

The sample transcript, audio levels, recognition, ETA, library, import, and
Android settings/share destinations are **simulated**. There is no microphone
capture, speech engine, audio playback, model setup, or connection to Android app
data. Copy uses the browser clipboard when available and provides selectable text
when denied. Text export downloads the actual sample `.txt`. Browser sharing, if
available, is an explicitly selected action. No sample text or audio is uploaded.

Appearance, phone width, text size, and the current jump-to-state phase are saved
locally and restored when opening another design or reloading. An explicit dark
choice takes precedence over the light default, including in Studio. Active
recording/finishing previews restart their sample timeline on a new page; this
preserves the comparison phase, not the previous session's audio or elapsed time.
The demo speaker preference also persists. Sample histories exist in memory and
reset on reload. The native implementation must instead
respect the real, independent history toggles and retention policies; automatic
demo history is not a proposal to override those settings.

## Reuse and native implementation direction

The prototype deliberately shares behavior, not just matching visuals:

- `components.js`: `TranscriptPanel`, `WaveformControl`, `SpeakerToggle`,
  `HistoryNavigation`, the header and icon primitives.
- `state.js`: one `DemoSession` state machine, one observable speaker `Preferences`
  source, and validated shared `PreviewPreferences` for the four review controls.
  The 64-bar waveform and finite sample text have bounded per-tick work.
- `app.js`: route ownership and layout composition. Opening a destination does
  not own or terminate the capture lifecycle.
- `styles.css`: shared tokens and component styles, with explicit layout variants.

After a concept is approved, **extract the Android components before composing
the new Home screen**:

1. Extract the currently private `WaveformButton` from `ui/RecordingPanel.kt`.
   Expose an explicit idle/recording/processing/completed presentation and actions.
   Keep the existing IME/overlay behavior and real PCM-driven visualization. Idle
   on Home means ready to start, not a disabled or already-active microphone.
2. Extract transcript presentation and the relevant transfer/history actions from
   `transcribe/TranscriptionDialog.kt`. Both the existing dialog and Home should
   use the extracted pieces. Preserve bounded/paged text access and existing large
   transcript transfer/export behavior; do not substitute the displayed preview
   for the complete transcript when copying or sharing.
3. Bind Home to the existing capture/coordinator and retained transcript state.
   Recording, model loading, transcription, and speaker labeling remain separate
   concerns. Start capture before model readiness, surface actual processing
   progress, and preserve audio when transcription fails. Browser timings are not
   estimates for the Android engine.
4. Use `SettingsRepository.diarizationEnabled` and `setDiarizationEnabled()` as
   the single app-wide preference. Reflect missing speaker-model prerequisites
   without making optional diarization a prerequisite for ordinary recording.
5. Make Home the post-onboarding launcher destination. The gear opens the existing
   Settings activity; histories are first-class destinations. Preserve onboarding,
   explicit model-selection/recovery intents, service restart behavior, system
   back navigation, and current audio share/open entry points.
6. Verify incremental result preservation, recording while models load, failures,
   retention-disabled behavior, background/navigation survival, accessible focus,
   large text, and real-device resource use before shipping the native screen.

The selected concept and native lifecycle details still require design approval.
These mockups do not select a final Android layout or change the launcher.

## Review checks

The browser review covered the start → progressive text → stop → progressing ETA
→ completed result → second recording path, navigation during capture, retained
demo entries, history entry details, share preview, clipboard-denial fallback,
and preference synchronization between Home, Settings and separate concept tabs.

Responsive checks used true iframe browsing-context widths of 360, 390 and 430 px
for all three pages: no page/app horizontal overflow and all brand assets loaded.
Compact phone previews with 125% text were also checked. Large text may scroll
vertically to retain controls rather than clipping them. Screenshots were reviewed
for hierarchy, typography, the original logo, palette, spacing, transcript
scrolling, and navigation visibility. The full-phone preview was fitted to the
available desktop height; completed-state previews start at the top of the text.

JavaScript syntax is checked with `node --check` on all three modules; whitespace
with `git diff --check`. Android build/emulator verification is outside this
browser-only design study. The browser-controller extension logged its own
`createLegacyRefRuntime` error (also on plain directory listings); no prototype
module runtime error was observed. Actual clipboard success, OS share targets and
real speech recognition are not established by these checks.
