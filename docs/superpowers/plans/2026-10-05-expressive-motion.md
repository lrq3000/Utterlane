# Signal Sprint Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan
> inline in the session's isolated `website-expressive-motion` worktree.

**Goal:** Implement the approved A motion direction across the entire responsive
Utterlane landing page, then commit and push directly to `origin/website`.

**Architecture:** Preserve semantic static HTML and progressive enhancement.
Extend the existing motion/visibility classes with one-shot entrances, and keep
scroll work bounded by cached geometry and requestAnimationFrame coalescing.

**Tech Stack:** Vite, TypeScript, CSS, Playwright and axe-core. No new dependencies.

## Design authority and file boundaries

The approved design and full-page scope are recorded in `docs/design.md` under
“Approved evolution — Signal sprint”. The user has authorized implementation.

- `index.html`: interlude, full section content, declarative entrance targets.
- `src/styles.css`: interlude typography/layout, shared entrances, section polish.
- `src/illustrations.css`: phone, waveforms, engine and use-case demonstrations.
- `src/demos.ts`: bounded, deterministic bar proportions.
- `src/motion.ts`: shared viewport-entry observer and existing motion gates.
- `src/scroll-story.ts`: geometry-based phone progression and existing story.
- `src/main.ts`: instantiate the entrance enhancement.
- `tests/landing.spec.ts`: user-facing motion, accessibility and layout contracts.

## Task 1 — Capture the changed behavior

- [x] Extend the existing Playwright tests to locate the new `#speed` heading,
  then verify it and every later section remain present with JavaScript disabled.
- [x] Add a desktop/phone test measuring the actual phone transformation over
  its visible scroll journey. Compare the yaw extracted from `DOMMatrix` before
  and after a half-illustration scroll: the difference must exceed 25 degrees.
- [x] Check that the phone waveform's container is at least 96px tall and its
  tallest untransformed bar at least 75px, in both viewport shapes.
- [x] Add scroll-entry/pause/focus coverage and include `speed` plus 320px and
  short landscape viewports in the existing overflow checks.
- [x] Run the focused additions against the current built baseline:
  `npm test -- --grep "speed interlude|phone turn|waveform" --reporter=line`.
  Expect missing interlude / insufficient rotation / insufficient height failures.

## Task 2 — Hero, signal interlude and processing illustration

- [x] Add a semantic section between hero and story with the approved exact
  headline, signal graphic, supporting copy and three value statements. Use the
  existing check icon and a shared local waveform symbol for no-JS fallbacks.
- [x] In `DemoIllustrations.populate`, use proportional bar heights:
  `24 + Math.abs(Math.sin(index * 1.83)) * 62` percent. CSS sizes the panels and
  flexes bars to avoid clipping at small widths; do not add animation timers.
- [x] Measure the untransformed `.hero-art` box. Cache these scroll endpoints:
  `start = Math.max(0, top - innerHeight * .46)`;
  `end = top + height * .45`. Normalize/clamp the current scroll offset between
  them. Observe main-content resizing to invalidate downstream story positions.
- [x] Use `rotateY(-28deg + progress * 52deg)` with modest pitch/roll, plus
  supporting badge parallax. Increase the phone's height to accommodate its
  enlarged capture panel without covering its message/input content.
- [x] Match A's navy, blue headline, font proportions, diagonals and spacing.
  Use a 700ms decelerating headline arrival and a short traveling signal. All
  motion selectors must be conditional on the existing running state.
- [x] Enlarge the engine boundary and waveform. Leave at least 80% vertical
  waveform scale at the completed story state. Keep mobile content in flow.

## Task 3 — Shared entrances and full-page continuation

- [x] Add `ViewportReveals` to `src/motion.ts`, initialized from `src/main.ts`.
  If IntersectionObserver is unavailable, return before opting into entrance
  styles. Observe `[data-reveal]`, mark each entered target `data-revealed`, then
  unobserve it. A shared `focusin` handler reveals any pending target containing
  the focused control. Complexity is O(n) setup, O(entered targets) callbacks;
  no new per-scroll traversal or permanent animation loop.
- [x] Apply declarative targets to interlude type, section introductions,
  use-case cards, feature-strip items, privacy points, quickstart items, FAQ and
  closing. Set small bounded delays for peer cards; never delay native actions.
- [x] Increase illustrated waveform/mic/travel motion. Improve use-card depth,
  feature-strip typography, privacy icon scale, quickstart numbering/drawn rules,
  FAQ disclosure states and closing type/rings within the existing palette.
- [x] Define readable static defaults. Scope hidden/pre-entry states to running
  motion plus successfully initialized reveals. Paused/no-JS/print content must
  be complete; `:focus-within` overrides pending decorative concealment.

## Task 4 — Verification and publication

- [x] Run `npm run check`, `npm run build`, `npm test -- --reporter=line`.
- [x] Use browser-controller on the local built page to scroll from hero through
  the footer, replay/pause animations, navigate anchors and open a FAQ. Compare
  A and the implementation for copy, type hierarchy, palette, spacing, phone
  framing, waveform scale and portrait/landscape behavior. Use the repository's
  Playwright capture utility for additional fixed-size images when needed.
- [x] Record only meaningful deviations/fixes in the design documentation;
  review the final diff for unrelated changes and sensitive data.
- [x] Fetch `origin/website` and check whether it moved. Integrate only newer
  website work if necessary; this orphan branch must never merge app `main`.
- [ ] Inspect status, diff and the last ten commits; stage intended source,
  documentation and tests. Create a conventional commit explaining the subtle
  original scroll range and fixed-pixel waveforms, the full-page improvement,
  and the OpenCode / OpenAI GPT-6 Astra (`openai/gpt-6-astra`) attribution.
- [ ] Push using `git push origin HEAD:website` (regular fast-forward only).
  Confirm the remote hash and inspect the Pages workflow through `gh`.
