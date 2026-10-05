# Website Theme Toggle Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan
> inline, task-by-task. Implementation and subsequent publishing were authorized
> by the maintainer. Steps use checkbox syntax for tracking.

**Goal:** Give website visitors a persistent, explicit light/dark choice through
the approved top-right moon/sun button.

**Architecture:** Restore the local preference in the HTML head before paint.
An encapsulated TypeScript class updates the root theme, button and browser
chrome; existing CSS variables carry the complete page's palettes. Preserve the
website's semantic HTML, local assets and independent motion controller.

**Tech Stack:** Static HTML/CSS, TypeScript, Vite, Playwright and axe-core.

---

## File responsibilities

- `index.html`: initial light state, pre-paint restore, moon/sun symbols, header
  button and original light/dark header/footer artwork.
- `src/theme.ts`: one `ThemePreference` class; no system-theme subscription.
- `src/main.ts`: initialize the theme controller independently of motion.
- `src/styles.css`: palette tokens, button, responsive header, page surfaces.
- `src/illustrations.css`: themed illustration surfaces and text contrast.
- `tests/theme.spec.ts`: user-facing theme and persistence contracts.
- `README.md`: describe the new controller and local preference.

### Task 1: Establish the failing browser contract

- [x] Add a Playwright test that clicks “Switch to dark mode,” verifies the dark
  root background, reloads, then switches back to light while changing the
  emulated device color scheme. Assert the accessible action after each step.

```ts
const dark = page.getByRole('button', { name: 'Switch to dark mode' });
await expect(dark).toBeVisible();
await dark.click();
await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(14, 23, 39)');
await page.reload();
await expect(page.getByRole('button', { name: 'Switch to light mode' })).toBeVisible();
```

- [x] Run `npm test -- tests/theme.spec.ts -g "remembers" --reporter=dot
  --workers=1`. Expect failure because the production button is absent.

### Task 2: Add the explicit preference controller and initial restore

- [x] Add `data-theme="light"` to the root, and place this bootstrap before the
  stylesheets. It validates the saved value and updates theme-color before paint.

```js
try {
  const theme = localStorage.getItem('utterlane-theme');
  if (theme === 'light' || theme === 'dark') {
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]').content =
      theme === 'dark' ? '#0E1727' : '#F1F5FB';
  }
} catch (_) { /* Storage restrictions leave the initial light theme intact. */ }
```

- [x] Implement a `ThemePreference` class with cached root, button, SVG use and
  theme-color references. Derive the current choice from the root's data
  attribute. On a click set the opposite choice, attempt to save it, and update
  `aria-label`, `title`, SVG reference and theme-color. Show the initially hidden
  button only after its handler is registered.

```ts
const dark = this.root.dataset.theme === 'dark';
const label = dark ? 'Switch to light mode' : 'Switch to dark mode';
this.button?.setAttribute('aria-label', label);
this.button?.setAttribute('title', label);
this.icon?.setAttribute('href', dark ? '#sun' : '#moon');
this.chrome?.setAttribute('content', dark ? '#0E1727' : '#F1F5FB');
```

- [x] Import and construct `ThemePreference` in `src/main.ts` before the existing
  independent demonstration and motion controllers.

### Task 3: Apply the approved palette and responsive artwork

- [x] Resolve root color/background through the existing `--ink`/`--canvas`
  tokens. Add dark token overrides for `#0E1727` canvas, `#182438` surfaces,
  `#E9EFFC` text, `#ADBAD0` muted text, `#A4C7FF` actions and `#29374D` outlines.
- [x] Use `--on-blue` (`#FFFFFF` light, `#082957` dark) for pale-blue dark-mode
  buttons and message bubbles. Keep the intentionally navy interlude's text
  light in both themes.
- [x] Replace fixed white section/illustration surfaces with theme tokens.
  Theme hero/setup washes, translucent cards, illustration panels, borders,
  FAQ open state and motion-control background without changing their layouts.
- [x] Show `wordmark.png` in light mode and the supplied `wordmark-dark.png` in
  dark mode in both header and footer. Keep original aspect ratios and alt text.
- [x] Match the approved 44px button with a 19px icon. At mobile widths reduce
  navigation gaps; at 380px and narrower use the preview's 116px wordmark and
  hide the decorative CTA arrow to keep both actions accessible.

### Task 4: Validate the complete behavior and document it

- [x] Extend browser coverage with first-load light mode, stored invalid values,
  storage access denial, early restore while the main module is blocked,
  keyboard access, dark WCAG AA checks, and 320/390/768/1440px header layout.
- [x] Run `npm run check && npm run build`, then rerun the focused theme tests.
  Expect TypeScript/build success and all theme assertions passing.
- [x] Run `npm test -- --reporter=dot` once for regression coverage of the built
  `/Utterlane/` artifact, including existing no-JS and motion behavior.
- [x] Update README architecture and explain that only the theme preference is
  locally persisted; the motion preference remains limited to the visit.
- [x] Open the complete implemented website at the isolated local server,
  exercise the button in both directions, inspect downstream sections and
  compare with the approved study. Review `git diff --check` and the final diff.

## Plan self-review

All approved requirements map to the tasks above. The storage key and root
attribute agree across bootstrap, controller and tests. Scope is limited to the
website, and the existing motion and static content contracts remain covered.
