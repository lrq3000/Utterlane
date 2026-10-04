# Utterlane website — Quiet confidence

Approved direction: A, by the maintainer on 2026-10-04. The HTML study at
`.superpowers/brainstorm/landing-directions/content/visual-directions.html` is
the visual reference; the app's Blue harmony kit is the identity authority.

## Design contract

- Cool `#F1F5FB` canvas, white surfaces, `#15263F` lettering, `#617088`
  secondary text, `#155ECC` actions, `#7750BC` supporting violet.
- Keep original raster wordmark and icon proportions, colors, and tagline.
  Reuse the source-derived exports; do not reconstruct the lettering.
- Spacious split hero, dimensional Android phone, two floating annotations,
  restrained background rings. Preserve the approved hero text and hierarchy.
- H1: “Near real-time transcription. On your Android phone.” followed by
  “100% offline.” Rotate offline/private/free vertically without layout shift.
- Continue into a scroll-driven, sticky speech-to-text explanation. Speech is
  processed inside the device boundary, then appears as completed segments.
  Also animate the hero phone as it scrolls out, rather than limiting scroll
  motion to the following section. Do not intercept or hijack native scrolling.
- Three illustrated use cases: dictation, reading shared voice messages, and
  offline travel. Follow with open-layout privacy/setup sections, an FAQ,
  the approved navy closing section, and source/maintainer/license credits.
- Mobile stacks content with bounded artwork. Short viewports and reduced
  motion receive readable, unpinned content rather than long empty scroll areas.
- Motion pause control, reduced-motion default, keyboard focus indicators,
  native FAQ disclosures, accessible static headline equivalent, and no-JS
  access to the full content and all real links.

## Product and publication accuracy

Content derives from the app README at `cad85a1`. Recognition is offline after
model download or local import. Segment-based output is not instantaneous
word-by-word streaming. Android 8+, ARM64; approximately 402–674 MB per model.
No speech uploads, analytics, account, or subscription. Optional microphone
history is off by default. Receiving apps control data after user exports.
Do not invent benchmarks, testimonials, store listings, or download counters.
Use the README's GitHub release/source/privacy links. The web demo is illustrative
and never requests microphone access or invokes recognition.

## Architecture and performance

Vite + TypeScript, static semantic HTML, CSS, no runtime framework, no external
fonts or third-party embeds. Separate styles for layout and illustrations.
Encapsulate motion preferences, scroll state, and demo cycling into classes.
Use one requestAnimationFrame per pending scroll, cached element references,
IntersectionObserver to pause offscreen loops, and visibility events to pause
background work. Work per frame is bounded by a fixed set of illustration nodes.

Build to `dist` with relative asset paths, so project Pages URLs and custom
domains both work. GitHub Actions builds and deploys only `website`; no app
build or app history is included. CI checks TypeScript, production build, and
browser behavior before publishing. Preserve license and artwork attribution.

## Verification

Check desktop and narrow/mobile first views against A, inspect every section,
exercise navigation/FAQ/motion controls and scroll transitions, confirm no
overflow, no missing assets, readable reduced-motion/no-JS output, and correct
project-subpath hosting. Automated browser tests cover user-visible contracts
and accessibility; visual judgment remains a direct screenshot comparison.
