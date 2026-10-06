# Comparison invitation — approved B / Illustrated preview

The maintainer selected B in the interactive design study and requested its
implementation on 2026-10-06. The reference is the B template and associated
styles in `docs/mockups/comparison-invitation/studio.html`.

## Visible design

Replace the plain comparison summary with the approved rounded invitation:

- Kicker: **A closer look**.
- Title: **What makes Utterlane different?**
- Description: **Compare phone keyboards, cloud transcription, offline tools,
  and Utterlane.**
- Three illustrated tiles: **Speed**, **Privacy**, **Meetings**, using the
  corresponding existing comparison artwork.
- Footer: **8 illustrated comparisons** and **These three topics, plus five more.**
- Closed action: **Expand the comparison** with a downward arrow.
- Open action: **Hide comparison** with the arrow pointing upward.

Retain the white theme-aware surface, subtle violet radial wash, blue/violet
top accent, 20px corners, 30px/34px/23px desktop padding, and two-column layout.
Use the current Blue harmony colors and typography. At 800px, stack the copy
and topic row. At 480px, use the approved 21px horizontal padding, 17px corners,
28px title, smaller illustrations, and stacked footer with a full-width action.

## Behavior and implementation

Keep the native `details`/`summary` disclosure, collapsed initially. The entire
invitation is one click/keyboard target; the button-shaped action is a span,
not a nested button. Native disclosure state and focus work without JavaScript.
Use CSS to swap the action label and arrow according to `[open]`.

Keep the eight comparison cards, neighboring speed chart, source notes, links,
and section order. Define reusable SVG scene symbols for the three illustrations
so the invitation and original cards share their artwork without a runtime
cloning controller. This adds fixed markup and CSS, with no new per-frame work.

Respect the existing theme tokens and focus outline. The arrow transition is
disabled for reduced motion and the site's explicit paused-motion setting.
The study's device/theme toolbar and preview-only return button are not part of
the selected website component.

## Acceptance

- Visible copy, spacing, type, colors, artwork, and responsive layout match B.
- Click, Enter, and Space open/close all eight cards; focus stays on the summary.
- The action label changes with state, including without JavaScript.
- Closed and expanded states remain usable at 320, 390, 768, and 1440px in both
  themes, without horizontal overflow; accessibility checks pass.
- Existing website checks and browser tests remain green.

Self-review: the accepted design, exact copy, responsive rules, shared-artwork
boundary, native interaction, and verification criteria are fully specified.
The user's approval covers implementing this selected design.

## Implementation verification

The implementation was compared directly with the approved B browser study at
matching content viewport widths: 1370px desktop and 378px phone (the study's
390px device frame has a 6px border on either side). Additional responsive
browser checks covered 320, 390, 768, and 1440px and both themes.

| Comparison point | Reference and implemented result |
| --- | --- |
| Visible invitation copy | Exact normalized text match in both layouts. |
| Card dimensions | Desktop 1200 × 307.5625px; phone 346 × 442.734375px; equal in reference and implementation. |
| Padding / corner radius | Desktop 30px 34px 23px / 20px; phone 25px 21px / 17px; equal. |
| Typography | Title 33px desktop / 28px phone; matching wrap and hierarchy. |
| Palette / accent | Same white surface, violet radial wash, blue/violet top accent and theme-aware controls. |
| Artwork | Same speed, privacy and meeting scenes; 96 × 60px desktop, 49px-high phone art. Shared symbols preserve the images. |
| Responsive behavior | Same stacked phone layout, topic row and full-width action. |

Reference and implementation screenshots were visually inspected together,
including the implementation's dark phone state. No material visual mismatch
remained. Above-the-fold hero/navigation copy and order are unchanged. The only
motion adaptation is honoring the site's paused state for the disclosure arrow.

The updated keyboard test first failed against the old summary as expected.
After implementation, TypeScript, production build, all nine focused comparison
tests, and all **53 website tests** passed. The tests cover native keyboard and
no-JS operation, changing action text, content containment and Axe accessibility.
Live Chrome interaction confirmed eight visible cards after opening and the
“Hide comparison” action. A clean browser context had no application errors;
browser-controller separately emitted its known injected `createLegacyRefRuntime`
error during live inspection.

The maintainer subsequently authorized integrating into `website` and pushing.
