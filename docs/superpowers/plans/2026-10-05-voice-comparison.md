# Illustrated Voice Comparison Implementation Plan

> **For agentic workers:** Use the executing-plans skill to execute these tasks
> inline in this session's isolated worktree. Steps are tracked below.

**Goal:** Integrate the approved eight-card comparison and leaf-green animated
speaking-time bar into the current website without losing upstream theme/copy work.

**Architecture:** Static semantic HTML and shared SVG symbols, with isolated
comparison CSS. A small `PaceComparison` class enhances the timing chart through
the existing `MotionPreference` and visibility gates; no framework or remote data.

**Tech Stack:** Vite, TypeScript, CSS, Playwright, axe-core.

## Files and responsibilities

- `index.html`: approved copy, research disclosure, eight cards, icon symbols,
  timing chart, and hero scroll-cue destination `#compare`.
- `src/comparison.css`: scoped layout, status badges, responsive/theme styles,
  chart/illustration treatment, and then motion keyframes.
- `src/pace.ts`: one-shot chart entrance lifecycle, pause-to-complete, no replay.
- `src/main.ts`: instantiate the chart with the shared motion preference.
- `tests/comparison.spec.ts`: meaningful no-JS/navigation/layout/theme tests,
  then deterministic bar animation and fallback tests.
- `tests/landing.spec.ts`: include `compare` in existing section-order,
  responsive, and no-JS coverage.
- `docs/design.md`: document the approved addition and integration results.

## Task 1 — Synchronize and record the accepted design

- [x] Inspect status, diff and recent history; fetch `origin website` and
  fast-forward this branch to `origin/website` (`4d4e752`, no local commits yet).
- [x] Read the upstream theme tokens, main entry point, test configuration,
  latest HTML and theme tests. Preserve these contracts.
- [x] Run `npm run check && npm run build && npm test` with `CI=true` so tests
  start their own server rather than reusing another worktree's process.
  Baseline: 44 tests passed.
- [x] Commit this plan and the approved spec:
  `docs(website): record approved voice comparison design`.

## Task 2 — Ship the complete static illustrated section

- [x] Add `tests/comparison.spec.ts` with a no-JS contract first:

```ts
test('comparison is usable without JavaScript', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto(baseURL!);
  await expect(page.locator('#compare .comparison-card')).toHaveCount(8);
  await page.locator('#comparison-sources summary').click();
  await expect(page.locator('#comparison-sources')).toHaveAttribute('open', '');
  await expect(page.getByRole('link', { name: 'KASROZ' }))
    .toHaveAttribute('href', 'https://futo.tech/blog/swipe-keyboard');
  await context.close();
});
```

- [x] Run `npm test -- tests/comparison.spec.ts` against the built baseline;
  verify missing comparison content causes the failure.
- [x] Insert `#compare` after `#speed`, copying the approved final preview's
  copy and SVG scenes. Use a section h2, h3 card titles and per-cell `dl/dt/dd`
  labels. Put `aria-hidden` on decorative badges/art; status meaning gets
  screen-reader text. Do not render the section through client-side JavaScript.
- [x] Use shared phone/paper/cloud/person SVG symbols with `comparison-` IDs,
  retaining the green shield, language bubbles and matching speaker colors.
- [x] Use the approved 1200px section width, 22px card radii, four desktop
  columns, two intermediate columns and one narrow column. Add local CSS tokens
  for cell/border/highlight backgrounds, overriding only their dark counterparts.
  Use existing `--ink`, `--muted`, `--surface`, `--blue` and `--violet`.
- [x] Preserve exact final recovery copy, Whisper/Vosk examples, KASROZ link,
  green speaking bar and expanded multilingual/meeting copy. Keep the study
  method notes in a native disclosure initially closed. Static chart structure:

```html
<div class="comparison-pace" data-motion-surface>
  <!-- Heading, 4x statement and explanatory copy from the approved design. -->
  <div class="comparison-track" aria-hidden="true">
    <span class="comparison-bar" style="width:100%"></span>
  </div>
  <div class="comparison-track" aria-hidden="true">
    <span class="comparison-bar comparison-bar-speech" style="width:22.6%"></span>
  </div>
</div>
```

- [x] Add tests for accessible column labels, sources navigation, theme colors
  and content bounds at 320/390/768/1440px. Include the new section in existing
  whole-page tests. Check the open source disclosure for axe contrast too.
- [x] Run `npm run check && npm run build && npm test`. Inspect the rendered
  site using browser-controller at `http://127.0.0.1:4352/Utterlane/#compare`.
  Capture exact desktop/mobile sizes using the existing Playwright tooling when
  browser-controller has no viewport-emulation operation.
- [x] Commit the complete static slice after its tests pass:
  `feat(website): add illustrated voice typing comparisons`.

## Task 3 — Add the approved chart motion

- [x] Apply the user's implementation-stage revision: a native, initially
  closed `.comparison-disclosure` wraps the comparison heading, legend and eight
  cards. Summary: “See how Utterlane compares to other solutions.” Add a failing
  keyboard expand/collapse test before inserting the wrapper; open it explicitly
  in no-JS, layout and contrast tests. Keep the speed chart and sources outside.

- [x] Test zero scale before entering the viewport, intermediate animation
  samples, final 22.6% ratio, manual pause before/during entrance, later Play
  without collapse, offscreen stripe pause, and missing-observer fallback.
  Run the focused animation tests and observe failure on the static chart.
- [x] Implement `PaceComparison` in `src/pace.ts`, constructed with the existing
  `MotionPreference`. Cache the chart and its two bars. Observe the chart with
  threshold 0.2; set `data-pace-entered` once visible. On `comparison-grow`
  animation end, mark each fill `data-expanded` so its entrance cannot restart.
  On manual pause, mark both expanded and the chart entered before resuming can
  recollapse it. No observer means complete static chart. One observer and event
  handlers only: bounded O(1) work, no animation-frame loop.
- [x] Add the transform-only rules:

```css
[data-motion="running"] [data-pace-ready]:not([data-pace-entered]) .comparison-bar {
  transform: scaleX(0);
}
[data-motion="running"] [data-pace-ready][data-pace-entered] .comparison-bar:not([data-expanded]) {
  animation: comparison-grow 1.2s cubic-bezier(.2,.7,.2,1) both;
}
[data-motion="running"] [data-pace-ready] .comparison-bar-speech::before {
  animation: comparison-spiral 2.4s linear infinite;
}
@keyframes comparison-grow { from { transform: scaleX(0); } to { transform: scaleX(1); } }
@keyframes comparison-spiral { from { transform: translateX(-32px); } to { transform: translateX(0); } }
```

  Existing `[data-in-view]` / `[data-page-hidden]` rules pause the loop. Print
  rules disable motion and expose complete fills. Site autoplay policy is kept;
  the existing global Play/Pause button controls the chart too.
- [x] Add `import { PaceComparison } from './pace';` and
  `new PaceComparison(motion);` to `src/main.ts` after motion initialization.
- [x] Run focused motion tests, then the full check/build/browser suite. Verify
  the real Chrome scroll → grow → pause → play path, source disclosure and theme
  control. Compare reference and implementation screenshots using Read.
- [x] Record five fidelity points in `docs/design.md`: copy/order, card geometry,
  artwork/green shield, typography, green-bar timing/ratio, and theme adaptation.
- [x] Commit behavior and tests with the requested collapsible comparison:
  `feat(website): add animated and expandable comparisons`.

## Completion

- [x] Fetch `origin website` again. If upstream advanced, merge its new commits
  without rewriting history, preserving all upstream hunks; validate affected
  behavior before adding a conventional integration commit.
- [x] Inspect status, commits and diff from `origin/website`; run `git diff --check`.
- [x] Report local commits, actual check results, preview URL and worktree path.
  Do not push or deploy without a request.

Self-review: all eight approved cards and wording revisions are assigned to
Task 2; motion and edge cases to Task 3. Theme support is the required adaptation
to the freshly fetched website. Existing autoplay and section content remain
under their current contracts. Commits must include motivation and the footer
`Harness: OpenCode; Model: OpenAI gpt-6-astra`.

Implementation-stage copy changes: remove the fifth comparison footnote and
rename the closing action to “Get Utterlane”, retaining its releases URL.
