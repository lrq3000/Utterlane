# Quiet confidence implementation plan

**Goal:** Ship the approved Utterlane landing page as a standalone orphan
`website` branch, ready for GitHub Pages.

**Architecture:** Static HTML carries all content, CSS carries the approved
design system, TypeScript progressively enhances illustrations and motion.
Vite produces a compact, relative-path deployment; GitHub Actions validates
and deploys its artifact. Execute inline in this existing isolated worktree.

**Tech stack:** Vite, TypeScript, Playwright, axe-core; Node 22+.

## File responsibilities

- `index.html`: semantic marketing page, metadata, SVG icon symbols, FAQs.
- `src/styles.css`: tokens, typography, page layout, responsive/accessibility.
- `src/illustrations.css`: phone, local processing diagram, use-case visuals.
- `src/main.ts`: enhancement composition only.
- `src/motion.ts`: opt-in/out preference and visible animated surfaces.
- `src/scroll-story.ts`: normalized scroll progression and segment output.
- `src/demos.ts`: illustrative repeatable capture states, paused offscreen.
- `public/brand/`: original-art-derived PNGs and social card.
- `tests/landing.spec.ts`: behavioral, subpath, no-JS and accessibility checks.
- `.github/workflows/pages.yml`: branch-scoped validation and Pages deployment.
- `README.md`, `NOTICE`, `docs/design.md`: commands, publication, provenance.

## Tasks

1. Add package/config files and browser contract tests. Run the headline test
   against an empty initial page and confirm the missing-content failure.
2. Build the static document and shared design system, preserving A's first
   viewport, three use cases, and closing panel. Copy the existing logo/icon
   exports and license rather than regenerating or redrawing them.
3. Add motion classes. Motion starts only when allowed; all animations pause
   through a single root data attribute. Reduced motion displays a complete
   transcript and the full static promise. Motion control supports explicit
   opt-in, and handles later OS changes without overriding a user choice.
4. Add scroll progression (clamped 0–1), phone transform, segment reveals,
   and offscreen/hidden-document suspension. Use CSS transforms/opacity.
5. Add setup, privacy details, FAQs, download/source links and site metadata.
   Configure `base: './'` and branch `website` Pages artifact deployment.
6. Run `npm run check`, `npm run build`, and `npm test`; inspect the live page
   with browser-controller, desktop/mobile screenshots, and motion checks.
   Fix concrete failures and re-run the affected checks.
7. Document verification, build sizes, and publication instructions. Leave
   changes ready for review; commit/push only on explicit maintainer request.

## Acceptance checks

- Exact slogan; original brand assets; coherent responsive Blue harmony page.
- Correct release/source/privacy URLs; no unverified product claims.
- Motion reacts to scroll, can be paused, and respects OS preferences.
- Native navigation and FAQ remain usable without JavaScript.
- No horizontal overflow at 360, 390, 768, 1280, and 1440 pixels.
- No external requests on page load; local static fonts/artwork only.
- Production assets work beneath a repository path as well as site root.
- GitHub workflow is scoped to `website`, with read-only build permissions
  and deployment permissions confined to the deployment job.
