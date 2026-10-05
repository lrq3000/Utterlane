# Utterlane website

The **Quiet confidence** landing page for [Utterlane](https://github.com/lrq3000/Utterlane),
the local-first Android voice-typing and audio-transcription app.

This is the independent, **orphan `website` branch**. It intentionally contains
only the website and has no shared ancestry with the Android application's
`main` branch. Work on the app and website separately; do not merge app history
into this branch. Website changes should target `website`, not `main`.

## Development

Requires Node.js **22.12+** (CI uses Node 24) and npm.

```text
npm ci
npm run dev -- --host 127.0.0.1
```

Open the local URL printed by Vite. All artwork is bundled locally. There are no
remote fonts, analytics, cookies, microphone requests, or third-party embeds.

```text
npm run check
npm run build
npx playwright install chromium
npm test
npm run preview -- --host 127.0.0.1
```

Browser tests run against the **built artifact** at `/Utterlane/` to check Pages
subpath behavior, not just the development server. Build before running them.
On Linux CI, install Chromium with `npx playwright install --with-deps chromium`.

For reproducible desktop/mobile visual captures with the preview server running:

```text
node tools/capture.mjs http://127.0.0.1:4174 /absolute/path/to/screenshots
node tools/capture.mjs http://127.0.0.1:4174 /absolute/path/to/screenshots "#speed" "#everyday" ".closing"
```

The capture utility explicitly pauses animations so a full-page image shows the completed
story, in desktop, portrait and landscape layouts. Optional trailing selectors
also capture individual sections at native resolution. Check the live scroll
sequence and play/pause control in a browser too.

## GitHub Pages

Expected public URL: **https://lrq3000.github.io/Utterlane/**

Repository setup verified on 2026-10-04: Pages uses GitHub Actions, and the
`github-pages` environment allows deployments from `website`. The first actual
deployment still requires committing and pushing this branch.

`.github/workflows/pages.yml` checks types, builds the static site, runs Chromium
interaction/accessibility tests, and uploads/deploys `dist`. Only pushes to
`website` deploy. PRs targeting `website` validate without deployment.

Repository settings:

1. **Settings → Pages → Build and deployment → Source: GitHub Actions**.
2. **Settings → Environments → github-pages → Deployment branches and tags**:
   allow the `website` branch if the environment has a branch restriction.
3. Commit and push the `website` branch when ready to publish. The push trigger
   works even though `main` remains the default branch. The manual dispatch
   button may not appear because this workflow is intentionally absent from
   `main`; use the `website` push trigger for normal deployment.
4. Inspect the **Website — validate and deploy** workflow and its deployment URL.

GitHub Pages configuration does not publish uncommitted local files. No custom
token or secret is needed; deployment uses the workflow's scoped `GITHUB_TOKEN`
and OIDC token. Build permissions are read-only; Pages permissions belong only
to the deployment job.

The Vite base is `./`, so assets work beneath repository paths and custom domains.
If the public domain or repository name changes, also update the canonical/Open
Graph URLs in `index.html`, `public/robots.txt`, and `public/sitemap.xml`.

## Page and motion architecture

- `index.html`: complete semantic content, native FAQ disclosures, metadata.
- `src/styles.css`: Blue harmony tokens, typography, layout and breakpoints.
- `src/illustrations.css`: original CSS phone and on-device processing scenes.
- `src/motion.ts`: autoplay and manual Play/Pause, offscreen/background pausing.
- `ViewportReveals` in `src/motion.ts`: one-shot section entrances that settle on
  completion, pause, or keyboard focus; resuming does not replay
  already-read text.
- `src/scroll-story.ts`: requestAnimationFrame-coalesced scroll progression.
- `src/demos.ts`: bounded decorative waveform construction (no audio capture).
- `public/brand/`: original artwork and its provenance.
- `tests/landing.spec.ts`: user-facing contracts and accessibility checks.

Animations start automatically on every visit, even when `prefers-reduced-motion`
is enabled. The fixed control lets a visitor explicitly play or pause them for
that visit; OS preference changes do not override this control. No preference is stored. Without
JavaScript, all content, download links, and FAQs remain available. On small or
short screens, the story uses normal page flow rather than a pinned viewport.

The illustrations are labeled demonstrations. Text arrives in **segments** to
reflect the app's behavior; no unsupported latency benchmark is implied.

## Content and design maintenance

The initial content follows the Android README at `cad85a1`. Keep requirements,
model sizes, feature claims, privacy details, and download URLs in sync with
[the current README](https://github.com/lrq3000/Utterlane#readme).

The primary download links point to GitHub Releases, not a specific APK or an
unverified store listing. Setup copy states that an Utterlane APK must be available
there; building from source remains linked as an alternative.

The design follows [the approved specification](docs/design.md) and the app's
[Blue harmony kit](https://github.com/lrq3000/Utterlane/tree/main/docs/design).
The source-derived logo is not redrawn, recolored, or substituted with text.

## License

Apache-2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE), and
[brand provenance](public/brand/README.md).
