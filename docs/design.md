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

## Approved evolution — Signal sprint (2026-10-05)

The maintainer approved motion study **A / Signal sprint** and explicitly
requested the complete continuation after section 01, followed by a conventional
commit and direct push to `website`. This extends the original design contract.

- Preserve the Blue harmony palette, original assets, split/stacked hero,
  navigation, real links, section order, and existing free/open-source mentions.
- Rotate the hero phone through approximately 52 degrees of yaw, with restrained
  pitch and roll. Progress follows the illustration's visible journey, rather
  than the height of the hero (whose copy occupies much more space on mobile).
- Make waveform panels roughly 100–108px tall, with percentage-height bars
  reaching about 86% of each panel. Keep the waveform visibly substantial even
  when the processing scene has finished. Increase the audio-card waveform too.
- Insert the navy `#0E1727` interlude directly after the hero. Use the approved
  headline: “Experience the fastest accurate offline transcription on Android.”
  “Fastest” is oversized `#A4C7FF` type, arriving with a short lateral sweep and
  settling into a readable layout. A bounded voice-signal animation connects a
  waveform to completed text. Supporting copy emphasizes accuracy, dependable
  offline operation, and automatic multilingual recognition.
- The superlative is the maintainer's requested positioning, not a new measured
  benchmark. Add no invented timings, comparative numbers, or accuracy scores.
  Retain the existing device/model/timing explanation and illustrative labeling.
- Continue through **all** sections: larger on-device processing scene; stronger
  use-case illustrations and staggered entrances; more prominent multilingual
  and vocabulary details; clearer privacy iconography; numbered quickstart
  steps with short line-draw entrances; native FAQ open/focus states; and a
  stronger navy closing composition. Preserve the recent Quickstart/model/FAQ
  copy update from `ec8534d`.
- Reuse one-shot viewport reveals. Animate transforms and opacity, never hijack
  scrolling, never force a delay before a link or disclosure can be used. Pause
  loops offscreen/in background tabs through the existing motion gate. Reduced
  motion, manual pause, missing observers, print, and no JavaScript show readable
  completed content. Keyboard focus must make a pending entrance readable.
- Keep normal flow on narrow/short viewports, with the existing responsive
  sticky story only on sufficiently large screens. Test at 320–1440px widths,
  portrait, landscape, and with motion enabled as well as reduced.

The approved study is the local companion's `motion-directions.html` and its
`motion-preview.html` A state. The implementation uses the existing static
HTML/CSS/TypeScript architecture, with no added runtime dependency or remote art.

### Implementation review

Compared the approved A study with rendered captures at 1440px, plus portrait
390×844 and landscape 844×390 section captures. Live Chrome review covered the
hero-to-footer scroll, the motion control, navigation, and an opened FAQ.

| Comparison point | Result / intentional adaptation |
| --- | --- |
| Interlude copy and hierarchy | Exact approved headline; oversized blue “fastest”, secondary two-line statement, signal, then value row. |
| Palette and illustration treatment | Navy `#0E1727`, headline `#A4C7FF`, original artwork and existing blue/violet voice gradient retained. |
| Spacing and containers | Full-width interlude, original gutters, open value row; mobile stacks the signal result without overflow. |
| Phone composition | Wider visible turn; mobile frame increased to 535px and message spacing tightened so the taller capture panel does not cover the input. |
| Waveform geometry | Proportional heights and flexible widths preserve a substantial, complete waveform at narrow sizes. |
| Downstream sections | All use cases, privacy, Quickstart, FAQ, closing and footer retained, with consistent section-specific polish. |
| Above-the-fold copy | No additions/removals in the hero or navigation; original rotating offline/private/free promise retained. |
| Motion lifecycle | Completed entrances settle permanently; pause/play cannot hide already-read text. Focus and reduced motion settle entrances immediately. |

Verification: TypeScript and production build passed; **31 Playwright tests**
passed, including seven viewport sizes with both running and reduced motion,
no-JS content, missing-observer fallback, keyboard/FAQ behavior, WCAG AA checks,
offscreen signal pausing and the pause/resume regression. The clean Chromium
runtime/asset check found no application errors or third-party requests. Live
browser-controller emitted its own injected `createLegacyRefRuntime` errors;
these were separate from the application runtime check.

The capture utility also supports section-level images. It hides fixed utility
controls only in those crops to avoid Chromium projecting the offscreen skip
link into a capture taller than the actual viewport. Live controls are checked
separately. No material visual mismatch remained in the inspected layouts.
