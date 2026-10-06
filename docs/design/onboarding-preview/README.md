# Onboarding — interactive design study

This browser study previews a proposed native Android onboarding flow. It is a
design artifact, not an implementation of recording, downloads, permissions, or
Android sharing. All device actions are explicitly simulated. It uses no remote
fonts, images, services, or dependencies.

Open `content/onboarding-v3.html` directly in a browser, or serve the directory
with the brainstorming visual companion. Relative styles and scripts work in
both cases. Review controls change theme, preview width, font size, available
RAM, and download state. The sidebar also opens individual screens.

Revision 02 adds a top-right Appearance selector on every onboarding screen, corrected
inclusive 1 GB recommendation, revised line illustrations, the
[sourced speech/typing comparison](speech-speed-evidence.md), and a full-width
vertical completion summary. Appearance is shared by the onboarding and studio
controls; System follows the browser's device preference.

Revision 03 refines the speaking illustration into a single curved profile with
a smaller nose and subtler lips. The meeting illustration uses larger heads and
torsos cropped at the shoulders-and-chest level, with the speech connection
retained. It reuses revision 02's styles and behavior.

## Design sources

- The app's [Blue harmony specification](../blue-harmony-spec.md) supplies the
  light/dark colors, voice gradient, typography direction, spacing, and shapes.
- The website branch at `5c71960` supplies the phone/speech-to-text composition
  and illustrated everyday-use concepts. The study adapts those compositions
  locally; it does not embed or depend on the website.
- The app's `ModelCatalog` supplies model identities and approximate decimal-MB
  download sizes. Download size is not a runtime memory estimate.

The simple product label is ordinary UI text, not a reconstruction of the
Utterlane wordmark. Native screens will use the existing source-derived artwork.
The browser voice-note example is illustrative. The native implementation bundles
a verified nine-second public-domain LibriVox reading from Wikimedia Commons;
its provenance is in `app/src/main/assets/onboarding/ATTRIBUTION.txt`.

## Proposed implementation direction

Keep native Compose screens in an isolated `onboarding` package. Define the step
order and informational content separately from renderers and platform actions.
Use a small app-facing adapter for model operations, permissions, recording, and
sharing. Model metadata and actual download state come from existing managers.
The mockup's JavaScript is solely for this design review, not an app dependency.

Open decisions for the design review include whether existing installations
should be invited into onboarding, and whether the introductory use cases should
remain on one screen or be separate slides. Optional setup and both demos always
have a clear skip action. A RAM-based suggestion is guidance, not a guarantee
that a model will fit alongside the OS and other applications.
