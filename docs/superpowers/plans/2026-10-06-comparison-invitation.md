# Illustrated comparison invitation implementation plan

> **For agentic workers:** Use the executing-plans skill to implement this focused
> change inline. User approval: B / Illustrated preview, followed by “please
> implement it.”

**Goal:** Make the collapsed comparison visibly valuable and clearly expandable,
faithfully matching the approved B mockup.

**Architecture:** Native static HTML disclosure and theme-aware CSS. Three SVG
scene symbols share artwork between the invitation and the existing cards.
No additional runtime JavaScript or dependencies.

**Tech stack:** HTML, CSS, existing Vite/TypeScript website and Playwright/Axe tests.

## File responsibilities

- `index.html`: shared scenes and the approved semantic summary markup.
- `src/comparison.css`: invitation layout, typography, themes, responsive and
  open/closed appearance.
- `tests/comparison.spec.ts`: update the existing disclosure and responsive
  coverage to verify the new invitation's state-dependent controls.

## Task 1 — Update the existing behavioral coverage

- [x] Replace the old exact summary-text assertion with checks for the approved
  title and visible state label:

  ```ts
  await expect(summary).toContainText('What makes Utterlane different?');
  await expect(summary.getByText('Expand the comparison', { exact: true })).toBeVisible();
  // After Enter opens it:
  await expect(summary.getByText('Hide comparison', { exact: true })).toBeVisible();
  // Space closes the native disclosure and keeps focus on its summary.
  ```

- [x] Extend the existing no-JS test to verify both action labels and all eight
  cards, clicking the action-shaped span to exercise event bubbling.
- [x] Extend the existing viewport/theme test to cover invitation content
  containment and Axe scans in the closed state before opening the cards.
- [x] Run the focused keyboard test against the current build and observe the
  expected missing-title failure before changing production markup.

## Task 2 — Implement approved B

- [x] Extract the current speed/privacy/meeting SVG scene bodies into symbols
  with IDs `comparison-speed-scene`, `comparison-privacy-scene`, and
  `comparison-meeting-scene`; reference them from both locations:

  ```html
  <svg class="comparison-art" viewBox="0 0 240 150" aria-hidden="true">
    <use href="#comparison-speed-scene"/>
  </svg>
  ```

- [x] Replace the disclosure summary with the B template's copy, topic tiles,
  count, and action. Rename the study's `invite-*` classes into the existing
  `comparison-invitation-*` namespace. Use one summary target and span labels:

  ```html
  <span class="comparison-invitation-action">
    <span class="comparison-invitation-closed">Expand the comparison</span>
    <span class="comparison-invitation-open">Hide comparison</span>
    <svg class="icon comparison-invitation-arrow" aria-hidden="true"><use href="#arrow-down"/></svg>
  </span>
  ```

- [x] Port only B's relevant CSS from the approved study, retaining its exact
  values and responsive overrides. Use a local border token for light/dark.
  Scope open-state rules to `.comparison-disclosure[open]`:

  ```css
  .comparison-invitation-open { display: none; }
  .comparison-disclosure[open] .comparison-invitation-open { display: inline; }
  .comparison-disclosure[open] .comparison-invitation-closed { display: none; }
  .comparison-disclosure[open] .comparison-invitation-arrow { transform: rotate(180deg); }
  ```

## Task 3 — Verify and hand off

- [x] Run `npm run check` and `npm run build`.
- [x] Run `npm test -- tests/comparison.spec.ts`, then the normal `npm test` suite
  after the focused checks pass.
- [x] Open an isolated local production preview in Chrome. Verify the invitation
  starts closed, expands on click, changes its label, and closes again.
- [x] Compare rendered desktop/mobile screenshots with B at matching widths;
  inspect copy, geometry, typography, colors, illustrations, and responsive
  wrapping. Record any adaptations in the design document.
- [x] Run `git diff --check` and review the final changes. The local production
  preview is `http://127.0.0.1:4369/Utterlane/#compare`; verification is recorded
  in the design specification. The maintainer authorized committing/integrating
  into `website` and pushing after verification.

Self-review: all acceptance criteria map to the implementation or verification
tasks; no runtime feature, additional visual direction, or unrelated refactor
is introduced.
