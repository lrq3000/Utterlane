# Blue harmony — approved application design

Approved in conversation on 2026-10-04: revision 3, option A.

## Visual contract

- Blue is the primary action color; violet-to-blue gradients identify voice input.
- Light screens use a cool near-white background, white rounded cards, navy text,
  and a subtle violet/blue/cyan header. Dark screens use navy surfaces and pale
  blue actions with equivalent contrast and hierarchy.
- The Settings header displays the supplied icon **and original wordmark**, not
  a font approximation. Transparent derivatives preserve the supplied lettering;
  a white derivative provides contrast in dark mode.
- Settings and dialog icons are clean glyphs without colored background tiles.
- Settings sections become inset rounded cards with consistent spacing. Existing
  actions, status information, permission disclosure, translations and navigation
  remain available. Mockup sample data is not application content.
- Transcription, history, dictionary, model selection, confirmation dialogs,
  recording overlays, voice IME and floating microphone share the theme.
- Preserve semantic warning/error/recording indicators and accessible native
  button semantics. Android-owned permission screens and share sheets remain
  system controlled.
- Follow the existing system/light/dark preference. Wallpaper-derived dynamic
  colors must not replace the approved brand palette.

## Implementation boundaries

One shared palette supplies Compose and native recording views. Reusable Compose
header/card primitives keep branding out of business logic. Generate wordmark
assets offline using the existing branding generator, with no runtime bitmap work
or additional library. Keep recording updates O(number of waveform bars), as today.

## Verification

Build `assembleDebug` incrementally; run the existing unit tests and focused
recording-panel instrumentation. Inspect Settings, dialogs and recording views in
light/dark on the available Android emulator, including narrow screens and larger
font sizes where feasible. Record actual limitations and screenshots in QA notes.
