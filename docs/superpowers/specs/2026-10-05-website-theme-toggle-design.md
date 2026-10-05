# Website moon/sun toggle — approved design

Approved by the maintainer on 2026-10-05 after reviewing the website header and
hero at `.superpowers/brainstorm/theme-toggle-v2.html`.

## User-facing contract

- Add a small, keyboard-accessible moon/sun button at the right end of the
  website navigation, beside “Get Utterlane.” Keep it usable on narrow screens.
- The moon switches the complete website to dark mode. The sun switches it back
  to light mode. Accessible names and tooltips describe the next action.
- Retain light as the initial theme. A manual selection overrides device
  appearance and survives reloads using the local `utterlane-theme` preference.
- Accept only `light` and `dark` as stored values. Invalid or inaccessible
  storage falls back to light. Storage write failures still permit switching
  for the current visit.
- Restore a saved preference before the first paint, rather than briefly showing
  the wrong theme while the main JavaScript module loads.
- Use the approved Blue harmony dark canvas, surfaces, text and accents. Adapt
  the complete page, including illustrations, disclosures, controls and footer.
  Keep the original source-derived light/dark wordmarks and voice gradients.
- Preserve the page's content, links and independent motion controls. Without
  JavaScript, keep the light page readable and hide the nonfunctional toggle.

## Implementation

An early inline head bootstrap restores the preference. A small encapsulated
`ThemePreference` class owns the click handler, cached DOM references and saved
choice. CSS variables provide the palettes and switch artwork without rebuilding
page content. Theme changes perform constant-sized work and add no dependency,
network request, animation loop or per-frame theme computation.

This work is based on the orphan `website` branch. Website and Android history
must remain separate.

## Verification

Add browser coverage for explicit overrides, reload persistence, restoring before
the main module executes, unavailable storage, invalid preferences, keyboard
operation, dark-mode accessibility and narrow header layout. Run the existing
TypeScript check, production build and Playwright suite against the built Pages
subpath. Review the complete light/dark website in the browser against the
approved preview.

## Implementation review

Implemented in the isolated `feat/website-theme-toggle` worktree, based on
`website` at `5c71960`. Reviewed desktop 1440×1000 light/dark captures and a
390×844 dark capture against the approved header/hero study. The button, palette,
original wordmarks, spacing and responsive placement match that direction.

TypeScript and the production build passed. All **42 Playwright tests** passed,
including 11 new theme tests and the existing motion/no-JS/accessibility checks.
The asset regression now checks visible lazy artwork in both themes rather than
expecting an intentionally hidden alternate footer wordmark to download early.
Live Chrome DOM inspection confirmed the complete dark page's surfaces and saved
choice. The browser-controller screenshot tool returned `Extension disconnected`;
visual captures were obtained with the already-installed Playwright tooling.

## Website integration — 2026-10-05

The maintainer requested a conventional commit, merge into `website`, and push.
Feature commit `21f92c6` was integrated onto website tip `3617ebf`, retaining the
new everyday SVG scenes, recovery copy, animation autoplay and footer credit.
Conflicted files started from that website tip; every original feature hunk was
then reapplied with this checklist:

| File / original hunk | Result | Adaptation |
| --- | --- | --- |
| README / architecture entry | Applied | — |
| README / preference documentation | Applied with adaptation | Retained the newer autoplay description and clarified that only the motion preference is not persisted. |
| index.html / initial theme | Applied | — |
| index.html / early restore | Applied | — |
| index.html / moon and sun vectors | Applied | — |
| index.html / header artwork and control | Applied | — |
| index.html / footer artwork | Applied with adaptation | Retained the current “Made with ❤️” credit while adding theme-specific wordmarks. |
| illustrations.css / phone surface | Applied | — |
| illustrations.css / reply and capture colors | Applied | — |
| illustrations.css / translucent cards | Applied | — |
| illustrations.css / processing scene | Applied | — |
| illustrations.css / transcript surface | Applied | — |
| illustrations.css / everyday surfaces | Applied with adaptation | Preserved the intervening new SVG scene styles and applied the palette to their shared illustration panel. |
| illustrations.css / microphone border | Applied | — |
| illustrations.css / dark outlines | Applied | — |

Merged verification: `npm run check`, `npm run build`, and all **44 Playwright
tests** passed. No unmerged paths or whitespace errors remained.
