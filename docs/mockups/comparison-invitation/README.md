# Comparison invitation — interactive design study

Three proposed entry points for the website's collapsed comparison:

- **A / Highlighted invitation:** compact tinted card with an explicit expand action.
- **B / Illustrated preview:** three existing illustrations introduce the eight topics.
- **C / Featured comparison:** navy panel with the four approaches shown visually.

The study uses the current worktree's `index.html`, styles, and original artwork.
It replaces only the disclosure summary inside a preview frame. All eight real
comparison cards remain available through the native disclosure. This is a
brainstorming artifact awaiting selection, not a production integration.

## Open with the Superpowers visual companion

From the website worktree, with Node.js available:

```text
node docs/mockups/comparison-invitation/preview.mjs <path-to-brainstorming-skill>/scripts/server.cjs
```

The command prints a local URL and creates an isolated, ignored session under
`.superpowers/brainstorm/comparison-invitation-<timestamp>/`. The existing
Superpowers server runs on loopback and stops after 30 minutes of inactivity.
Re-run the command to start a fresh preview. The editable source is `studio.html`;
each launch copies it and the current website assets into the new session.

Use the A/B/C selectors, Desktop/Phone and Light/Dark controls. Click anywhere on
an invitation to open it. Enter and Space also toggle the focused summary.
“Reset view” returns to the closed invitation; the preview-only “Back to
comparison” control also closes it after reading the longer content.

The illustrations are copied from the relevant comparison cards, not newly
invented performance scores. Site motion stays paused to make the designs easy
to compare. Theme changes are limited to the preview and do not update the
website's saved preference.

## Verification of the initial study

Base: `origin/website` at `ebaa678c0b68b7caf919dce42ed378b6117592d8`.

- `npm run check` and `npm run build` passed on the baseline.
- Browser checks passed for all 12 combinations of A/B/C, desktop/phone, and
  light/dark: closed initial state, eight expanded cards, click and keyboard
  toggles, preview return control, theme changes, and horizontal overflow.
- Axe WCAG A/AA scans of each invitation passed in those 12 combinations.
- Nine additional layout checks passed across A/B/C at 320, 390, and 768px.
- No page errors or failing asset responses occurred in the 12-case run.
- Desktop and phone screenshots were inspected; generated captures live in the
  ignored companion session's `state/` directory.

Verification used Playwright with the installed Chrome channel. The default
Playwright browser binary was unavailable. The accessibility scan requires an
explicit browser context; phone-preview return-button checks first scroll the
outer page until the frame is fully visible.
