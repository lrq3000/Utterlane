# Everyday landing-page implementation plan

**Goal:** Make Utterlane's landing page explain concrete everyday uses and recovery, retaining its existing visual language.

**Architecture:** Keep semantic content and original inline SVG scenes in `index.html`, illustration styling in `src/illustrations.css`, and section layout in `src/styles.css`. Reuse the shared reduced-motion and viewport pause controls; no external imagery or runtime dependencies.

**Tech stack:** Static HTML, CSS, TypeScript, Vite, Playwright.

## Approved design

- Remove landing-page fork attribution, shorten the demo caption, and use “Less typing. More freedom.”
- Keep the offline dependability statement and explain incremental transcript saving and recoverable recording history. Avoid an absolute no-loss guarantee.
- Rework `#everyday` as “For your everyday needs”, with three cards: private idea capture (including “scooped”), shared audio/lectures, and streaming meetings with speaker labels. No speaker-setup detail.
- Illustrate human situations in the existing blue/violet palette: an idea capture scene, a student/lecture scene, and three meeting participants whose bubbles become labeled transcript segments.
- Animate the meeting bubbles traveling into the phone, with matching colors/numbers and accumulating transcript rows. Pause and reduced motion show the complete static scene.
- Add sharing recordings to Quickstart and update privacy/FAQ copy for one-hour history retention.
- Remove the Quickstart tagline “An Android phone. A local speech model. And something to say.”

## Execution

- [x] Independently update the Android default in a main-based worktree: absent retention becomes HOUR, explicit choices and unknown values retain their behavior; match settings initial UI and current privacy documentation. Run the history regression tests, then commit and fast-forward push to `main` as requested.
- [x] Edit the caption, scroll prompt, footer, Quickstart, and history descriptions in `index.html`.
- [x] Add the robustness callout and three illustrated use cases; keep existing anchors and navigation working.
- [x] Add local SVG scenes and narrowly scoped CSS, using the existing animation pause mechanism and static no-JavaScript fallback.
- [x] Run `npm run check`, `npm run build`, and `npm test`; inspect desktop and narrow mobile screenshots, including all three scenes and recovery copy.

## Verification criteria

No page-visible TranSlander reference or “no microphone access” caption. Three concrete use cases with distinct original illustrations. Sharing and one-hour history described consistently. No unconditional durability/security claim. No speaker-diarization setup footnote. No horizontal overflow, missing assets, external requests, or accessibility regressions.

## Results

- Android commit `1b1c12e` pushed to `main`. Eight history tests passed with the Kotlin 2.0.21 compiler and JUnit 4.13.2, after the new default test first failed against the old implementation. The normal Android Gradle unit-test task was blocked by the missing sherpa-onnx AAR in the fresh worktree; no APK build is claimed.
- TypeScript check and Vite production build passed. All 32 Playwright tests passed, including per-speaker bubble movement, ordered transcript accumulation, static reduced-motion content, and pause behavior.
- Existing tests covered seven viewport sizes from 320px to 1440px, motion enabled/disabled, no-JavaScript content, keyboard access, asset loading, runtime errors, external requests, and automated WCAG AA checks.
- Desktop (1440px) and mobile (390px) section screenshots were visually reviewed. Captures also include landscape (844px). Chrome extension screenshot calls returned `Extension disconnected`; the repository's Playwright capture utility provided the visual evidence.
- Publication approved: create a conventional commit, integrate into `website`, and push to `origin/website` to trigger the Pages workflow.
