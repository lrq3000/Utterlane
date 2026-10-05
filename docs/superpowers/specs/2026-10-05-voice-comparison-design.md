# Utterlane — illustrated voice comparison

## Direction and placement

The maintainer selected **A / Illustrated stories**, with copy and illustration
revisions, and approved implementation after the final Whisper/Vosk wording
change. Add a self-contained `#compare` section immediately after the existing
navy speed interlude. Use the current Blue harmony palette, spacious white cards,
navy speed example, and blue-highlighted Utterlane column. The reviewed visual
reference is `.superpowers/brainstorm/voice-comparison/content/comparison-clean-copy.html`.

## Speed story

Headline: **Your voice moves faster than your thumbs.** A prominent **≈4×** is
explicitly a human speaking-versus-mobile-typing comparison, not an Utterlane
benchmark. A 100-word illustration shows about **2m 46s** typing versus **38s**
speaking, using 36.2 WPM and a rounded 160 WPM respectively. Recognition
finalization and editing are excluded. Link sources next to the figures.

Below “Picture a 100-word message.” add “Smaller is faster and better.” Both bars
start at zero and expand once the card enters the viewport, retaining final
lengths of 100% and 22.6%. This is a chart entrance, not a timed typing benchmark.
The speech bar has a subtle leaf-green barber-pole spiral with cylindrical
highlight/shading. Animate transforms rather than layout. Pause loops offscreen
and in background tabs. Manual pause shows the complete chart; Play and later
scrolls must not replay its completed entrance. Use the existing site motion
control in production (the standalone preview has its own bar control). Missing
observers, no JavaScript, and print show complete static bars.

Research: [Palin et al., MobileHCI 2019](https://doi.org/10.1145/3338286.3340120)
reports 36.2 WPM for 37,370 volunteers copying English sentences. Typing words
are standardized to five characters. [Yuan, Liberman & Cieri, Interspeech 2006](https://www.isca-archive.org/interspeech_2006/yuan06_interspeech.html)
reports 164 WPM turn-wise and 196 WPM over elapsed conversation time in English
Switchboard. These are natural-conversation measurements, not dictated text.
Different participants, tasks, and word definitions make the ratio approximate.

## Comparison cards

Implementation revision: wrap “Same thought. A different experience.”, its
introduction, legend and all eight cards in a native `<details>` element,
collapsed by default. Its summary is “See how Utterlane compares to other
solutions.” The timing illustration, cross-app closing benefit, and four-source
disclosure remain outside this fold. Keyboard and no-JS operation are required.

Each card has a numbered topic, descriptive heading, short benefit explanation,
an original local SVG illustration, and four labelled columns: Phone keyboard,
Cloud transcription, Other offline transcription, Utterlane. Define the other
offline column visibly as the legacy, older-model batch workflows illustrated
here, not all competing offline apps.
Cloud features vary by service and plan. Status badges use white symbols in
rounded green, amber, or red squares, plus meaningful text (never color alone).
The legend is Strength / Depends or limited / Limitation, since a limitation is
not equivalent to total feature absence. Plain text covers non-applicable cells.

1. **Writing speed:** retain the research average; link KASROZ to
   <https://futo.tech/blog/swipe-keyboard>. Add the maintainer's swipe-training
   point, with “retraining can be long and tedious” rather than treating a
   universally long learning time as a measured finding. FUTO supplies an
   optimization rationale, not a longitudinal human training study. Utterlane's
   subtitle becomes “Write as fast as you speak”, with the requested SOTA
   streaming/near-real-time meeting-labels copy. Keep its adjacent device/model
   timing explanation; it is not a universal performance benchmark.
2. **Privacy:** illustrate server-bound audio versus on-device recognition.
3. **Offline reliability:** replace the train scene with a phone fully enclosed
   by a brilliant green shield, with a soft green glow and restrained sparkles.
4. **Recovery:** illustrate saved audio plus transcript, replay and retranscribe.
   Explain default one-hour retention and configurable history. Claims concern
   saved material, not unconditional recovery from every failure. Phone keyboard
   gets a red limitation mark and “Draft recovery entirely depends on the
   receiving app.” Other offline transcription gets a red mark, the subtitle
   “Recovery is not assured”, and “Without saved audio and partial transcripts,
   a failed session can mean starting over.” The comparison concerns Android
   implementations; omit the desktop-based recovery counterexample.
5. **Accuracy:** heading “Speech recognition in real environments.” Use the
   maintainer's paragraph beginning “SOTA speech models with clever optimizations
   battletested for everyday recordings. Speak, whisper, sing…”. Cloud receives
   a green check for modern frontier models. Other offline transcription receives a red
   cross for legacy-model limitations, with the exact text “Older models (eg,
   Whisper, Vosk) can struggle with challenging speech.”
   Utterlane remains green. A short note explains variable accuracy and
   corrections for whispered/sung audio. Replace the singing-benchmark sentence
   with a practical clear-articulation/closer-microphone tip, using “can improve”
   rather than guaranteeing a large gain. Close microphone placement in noise
   is supported by [Google's STT guidance](https://cloud.google.com/speech-to-text/docs/best-practices).
   The
   [Ultra model card](https://huggingface.co/moondream/parakeet-ultra) reports
   improvements on noisy/multilingual speech in its own GPU runtime, not an
   Utterlane phone measurement. Other current offline apps also use Parakeet
   (for example [Dictus](https://github.com/getdictus/dictus-android)).
6. **Multilingual:** cloud gets a green check and “Often multilingual”; legacy
   offline gets a red limitation mark and “Mixed-language limitations”. Say
   legacy workflows can miss language switches, and behavior depends on models
   and settings. Do not invent market-share data (“most apps”) or claim Vosk
   and Whisper uniformly translate unprompted. Retain 25 languages, with the
   requested expanded description: “Automatic language switching detection in
   the same recording: start in one language, switch to another one, all
   faithfully recorded in the language spoke in.” Retain the existing
   intra-segment reliability note and general accuracy context.
7. **Meetings:** use “with optional streaming speaker labelling to help follow
   who said what.” Explain the separate model and extra processing work. Legacy
   offline gets a red mark and “Never live”, explicitly scoped in its cell to
   **these legacy batch workflows** rather than every offline implementation.
   [Speech Android](https://github.com/soniqo/speech-android#meeting-transcription-building-blocks)
   documents streaming on-device diarization, so an industry-wide “always
   post-processing” claim is unsupported. Utterlane gets “Live stream offline
   speakers labels” and the requested up-to-eight-speakers / phone-as-scribe copy.
8. **Ownership and rate limits:** cloud gets a red limitation mark and “Limited
   free tiers, subscriptions with rate limits, or usage billing.” Keyboard gets
   “Unlimited” with the requested hands-speed explanation. Other offline transcription
   gets “Technically unlimited, practically not”; supporting text says speed,
   accuracy, or reliability **can limit practical use**, rather than declaring
   every legacy tool unusable. Utterlane gets “Truly unlimited and yours” and
   “Free & open source & accurate & fast enough to hold the promise of unlimited
   transcripts.” Keep “Unlimited means no service-imposed transcription quotas
   or per-minute charges.” Remove the following device-capacity sentence from
   the visible card, as requested.

End with the cross-app input-method benefit and a real release link. Include a
native Sources & comparison notes disclosure, readable without JavaScript.
Keep its four source references; the maintainer removed the fifth comparative
footnote during implementation.

## Implementation boundaries

Use semantic static HTML for indexable, no-JS content, with comparison-specific
CSS in `src/comparison.css`. Reuse SVG symbols, existing section containers, and
the site's motion visibility/pause mechanism. No runtime framework, fetched data,
tracking, remote illustrations, or microphone demo. SVG assembly and repeated
card markup do not require a runtime templating layer.

Four columns on desktop, two at intermediate widths, one on narrow phones;
repeat labels per card and preserve logical DOM order. The speed chart's bars
represent **time needed**, so the shorter speech bar denotes the faster method.
All figures and text remain visible when motion is paused or JavaScript is off.
Add a visible comparison link using the existing hero scroll cue.

## Verification and scope

Baseline TypeScript/build and all 33 Playwright tests passed on `3617ebf`.
Check the new source links, updated wording, card/column relationships, badge
meanings, native disclosure, no-JS reading, narrow layouts, zoom, and WCAG AA.
Inspect real rendered screenshots at desktop and phone sizes, including the
green shield and long KASROZ cell. Preserve existing motion/pause behavior and
GitHub Pages subpath hosting. Keep work in this session's website-derived
worktree; committing, publishing and deployment require an explicit request.

Implementation authorization: the maintainer requested replaying on the latest
`origin/website` and frequent, semantically focused conventional commits. The
worktree fast-forwarded to `4d4e752`, preserving the new persistent light/dark
theme and updated action/freedom copy. Baseline check/build and 44 browser tests
passed there. Use its theme tokens for comparison surfaces/text; retain the
approved navy timing card and green speaking bar in both themes. Publishing is
not part of this authorization.

Self-review: source methods and units stated; unsupported universal comparisons
narrowed; all eight topics, requested badges, layout, and verification specified.

### Animated design-preview verification

A focused Chromium/Playwright check of the standalone preview passed:

- Both fills are zero before intersection; both grow after scrolling into view.
- Sampling the entrance at 0 and 600 ms confirms zero then intermediate lengths.
- The completed speech/typing length ratio is 0.225985 (target 0.226).
- The spiral pauses offscreen and through the manual button. Completed bar
  entrances do not replay after scrolling away/back or pausing/resuming.
- Requested copy, KASROZ URL, and updated badge positions match the revised design.
- No horizontal overflow at 320, 390, and 768 pixels; desktop and phone captures
  were visually inspected. No JavaScript runtime errors were recorded.
- The chart retains complete static bars with JavaScript or observers disabled.

Real Chrome inspection also confirmed the stripe transform advances while its
tab and chart are visible, and stays paused while the tab is in the background.
The standalone captures are `pace-desktop.png` and `pace-mobile.png` beside the
HTML preview. These checks cover the design preview; production integration is
approved and has its own implementation checks in the accompanying plan.

The leaf-green revision was subsequently checked in Chromium: all eight column
labels use “Other offline transcription”; the recovery badge is red; the expanded
multilingual copy and shortened quota note are present. The computed speaking-bar
gradient is `#5c9938` → `#8bc34a` and retains `comparison-spiral`. No horizontal
overflow at 320, 390, 768, or 1440 pixels and no runtime errors were observed.
Inspected captures: `leaf-pace-desktop.png`, `leaf-pace-mobile.png`,
`leaf-recovery-desktop.png`, and `leaf-multilingual-mobile.png`.
