# Onboarding study — revision 01 review

## Scope

Twelve browsable states cover Discover, Set up, and Try it. The speaker-model
download is conditional in the normal flow. Recording, permission prompts,
downloads, folder selection, and sharing are explicitly simulated. This is not
Android runtime validation.

The proposed native implementation uses Compose, existing theme resources, a
declarative step list, and a small adapter to the app's existing capabilities.
Bundled illustrations and audio will remain independent of website changes.

## Checks performed

- `node --check docs/design/onboarding-preview/content/onboarding-v1.js`.
- Live Chrome inspection of the welcome screen, model cards, and a dark-theme
  interrupted-download screen.
- Browser layout matrix: **96 combinations** (12 screens × light/dark ×
  320/390 px phone widths × 100%/200% text). No horizontal content/footer overflow;
  each content region retained at least 120 px of scrollable height. This checks
  the inner phone preview; it is not an Android accessibility certification.
- Recommendation boundaries: 768 MB selected Redux, 1 GB and 2 GB selected
  Ultra Q4, and 8 GB selected Ultra Q8.
- Interrupted download → retry → ready → next step.
- Simulated microphone approval and shortcut enable/return.
- Speaker-label opt-in → separate 107 MB download → try-it step.
- Microphone start/stop → editable simulated transcript.
- Sample share → simulated Android chooser → Utterlane result dialog; dialog
  focuses an action, keeps the phone background inert, and closes with Escape.

Review found and corrected two interaction/layout issues: a broad action prefix
intercepted shortcut confirmations, and side-by-side artwork crowded large text.
Confirmation is resolved first; use-case cards stack at enlarged font sizes.
The desktop studio also scales the preview to keep its bottom controls visible.

## Design decisions still requiring maintainer input

- Visual direction, wording, and overall screen sequence.
- Automatic onboarding on new installations versus an invitation for existing
  installations, with manual reopening from Settings.
- Final sample recording: verify both source and redistribution rights, choose
  the excerpt, and preserve attribution with the bundled asset.
- RAM-based recommendations follow the requested thresholds. Device runtime
  validation is still needed; download size alone does not prove memory fit.

No Android source, manifest, or resource changes have been made for this study.

## Revision 02 — requested refinements

- Replaced the slogan with **Less typing. More freedom.**, including the
  simulated dictation example.
- Corrected the recommendation: **RAM ≤ 1 GB → Redux; 1 GB < RAM ≤ 2 GB →
  Ultra Q4; RAM > 2 GB → Ultra Q8**.
- Added the in-app **System / Light / Dark** selector directly to Welcome.
  Both selectors share one preference, retained across the journey and shown in
  the final summary. System resolves the device preference rather than fixing a
  theme when selected.
- Redrew the first use-case illustration as a woman's open mouth in profile,
  facing right with sound waves. Moved the original page-and-microphone artwork
  to the voice-note card. The meeting illustration now has two complete abstract
  people with connected speech bubbles and sound motifs.
- Added the sourced, qualified **≈4.5×** speech/typing comparison and a details
  dialog linking the studies. See [the evidence record](speech-speed-evidence.md)
  for the measurements and limitations; no 20× or app speed/accuracy claim is made.
- Added **Agentic instructions.** after **A conversation.** and the requested
  **Your thoughts into words. Anywhere. Right from your pocket.** wording.
- Replaced the summary with eight full-width vertical label/value blocks for
  model, appearance, microphone, shortcuts, monitoring, audio access,
  notifications, and speaker labels. Each value has its own line; the containing
  screen scrolls vertically while its final action stays visible.

### Revision 02 verification

- `node --check docs/design/onboarding-preview/content/onboarding-v2.js` passed.
- Live Chrome inspection: dark Welcome with the new selector, the three revised
  illustrations, source dialog, and vertically scrolled completion summary.
- All five RAM presets matched the expected recommendation: 768 MB and 1 GB
  Redux; 1.5 GB and 2 GB Ultra Q4; 8 GB Ultra Q8.
- All three **Welcome** appearance options changed the resolved theme correctly
  and appeared correctly in the completion summary. System matched the current
  device's dark preference.
- **144 layout combinations** checked: 12 screens × Light/Dark/System ×
  320/390 px phone widths × 100%/200% text. No content, header, or footer
  horizontal overflow; each body kept at least 120 px of scrollable height.
- **12 completion-summary geometry checks** confirmed that all eight rows used
  the full container width and were stacked without overlap. The first
  frame-waiting audit lost its tool response while the tab was inactive; the
  reported results come from the completed synchronous geometry audit after
  focusing the preview tab.
- Source details showed both expected original-source links; Escape closed the
  dialog. These are browser mockup checks, not Android runtime validation.

### Subsequent refinements

- The speech test includes: **Tip: Enunciate clearly and speak close to the
  microphone for better accuracy, especially in noisy places.**
- The **System / Light / Dark** selector now stays in the top-right header on
  **every onboarding page**, with one shared preference. It remains available
  while the content scrolls; the completion summary updates immediately and
  preserves keyboard focus when its selector is used.
- Checked all 12 screens at 320/390 px and 100%/200% text: **48 layouts and 144
  appearance changes passed**, with right-aligned selectors, no horizontal
  overflow, and usable scrollable content. JavaScript syntax also passed.

## Revision 03 — illustration refinement

- Replaced the separate filled lip shapes with a single flowing profile contour:
  a smaller nose, subtler parted lips, and a soft chin/neck curve. The three
  outgoing sound-wave lines remain a separate supporting motif.
- Increased both speakers' head radii from 7 to 10 and broadened the torsos from
  20 to 30 units. The figures now end at chest level, with no legs or waist line;
  the connected speech bubbles are retained.
- Reviewed the rendered Everyday Uses cards in both light and dark themes.
  JavaScript syntax passed. Final assessment of the illustration proportions
  remains part of the maintainer's visual review.
