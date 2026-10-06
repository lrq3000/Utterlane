# Native onboarding — approved design

Approved for implementation after the revision-03 browser study and subsequent
speech-tip and persistent-appearance refinements. The visual reference is
`docs/design/onboarding-preview/content/onboarding-v3.html`.

## Flow and presentation

Use native Compose and the existing Blue harmony theme. An ordered registry owns
stable screen IDs, phase, localized headings/copy and page kind. The host owns
navigation and a persistent top-right System/Light/Dark selector. Page bodies
scroll, bottom navigation remains reachable, and larger fonts may wrap controls.
Bundled vector layers reproduce the approved single-line speaking profile,
sound waves, voice-note illustration, and connected head-and-shoulder speakers.

1. Welcome: “Less typing. More freedom.” and local-first introduction.
2. Everyday uses: speech input, shared recordings, meetings; sourced ≈4.5×
   English speech/typing comparison with an in-app source-details dialog.
3. Model selection: four borderless filled cards, selection distinct from the
   recommendation, names/sizes sourced from the app model catalog.
4. Speech-model download/import: real progress, error/retry, cancel/change,
   verified readiness before continuing.
5. Microphone: contextual request, optional for file-only use.
6. Input shortcuts: keyboard, floating mic, accessibility; optional.
7. Folder monitoring: opt-in, local folder choice, contextual audio permission
   and notifications. Reject unsupported cloud/document-provider folders instead
   of pretending a filesystem observer can watch them.
8. Speaker labels: opt-in, automatic count initially, extra model size displayed.
9. Speaker-model download: conditional, same download presentation, enable only
   after successful verification. Skipping preserves normal transcription.
10. Voice trial: editable field, in-app microphone start/stop, real transcription,
    permission on demand, retry/cancel and partial-text preservation. Include the
    approved enunciation/microphone-distance tip. No keyboard setup required.
11. File trial: bundled public-domain reading excerpt, attribution, native
    share sheet, and the existing transcription activity. Optional.
12. Completion: “A message. An idea. A conversation. Agentic instructions.” and
    “Your thoughts into words. Anywhere. Right from your pocket.” Full-width
    vertical label/value summary, then open Settings.

## Recommendation and state

- Total physical RAM ≤ 1 GiB: native ternary Redux.
- Above 1 GiB and ≤ 2 GiB: Ultra Q4.
- Above 2 GiB: Ultra Q8.
- Unknown RAM: no asserted recommendation. Existing explicit selections survive
  replay, including a current custom model outside the four featured choices.
- Recommendations are guidance, not a guarantee of available runtime memory.
- Separate onboarding DataStore retains stable step ID and draft choices across
  process death. Never persist a live recording or unverified download as ready.
- Fresh launcher starts enter onboarding. Previously configured installations
  continue to Settings; Settings provides an explicit replay entry. Incomplete
  onboarding resumes even after some choices have already updated app settings.
- Replaying the guide does not reset app settings or clear completion. Skip does
  not silently turn off a feature that was already enabled before replay.
- Shared-file and other dedicated entry points retain their own intent handling.

## Boundaries and lifecycle

The onboarding package depends on a small app-facing services interface. Only
its adapter knows model managers, the recording session factory, service starts,
and settings storage. The Activity handles Android result launchers, external
settings screens, and permission refresh on resume. UI reads a state snapshot.

Downloads reuse existing verification/storage logic. Rotation retains the
controller and transfer; cancellation is explicit, and process death offers a
retry rather than displaying phantom progress. Platform permission results are
re-queried; denial provides a route to app Settings without a request loop.

Voice capture uses the existing bounded MicrophoneSession pipeline and current
history policy. Stop recording when the Activity ceases to be visible; cancel
and release on final teardown. Keep the trial preview bounded and cap trial
duration; preserve completed text on failure. Do not automatically start capture
or network transfer on an informational page.

The sample is copied from bundled assets to private cache, shared as a
FileProvider content URI with read permission and ClipData, and handled by the
existing TranscribeActivity. Source/license/attribution are bundled as well.

## Validation

Unit tests cover recommendation boundaries, launch policy, conditional flow,
stable-ID restoration, permission prerequisites and local-folder validation.
Android tests cover the real Activity, completion/replay, persistent appearance,
permission refresh and sample URI access. Build with `assembleDebug`; inspect
native screenshots in light/dark and narrow/large-text layouts. Recognition tests
use actual installed models and the bundled audio when the target supports them.
