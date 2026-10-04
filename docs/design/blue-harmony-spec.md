# Utterlane — Blue harmony design specification

Version 1 · 2026-10-04 · Implemented UI baseline: `592a659`.

This is the reusable specification for the approved **revision-3, option-A**
redesign. The repository card uses the subsequently selected **app showcase**
composition. The code references below describe the implementation; illustrative
component text and waveform levels in the design sheets are sample content.

## 1. Design intent

**Fast, calm, local.** Lead with the blue Utterlane identity. Use violet as a
supporting voice/recording accent. Leave reading and configuration surfaces quiet
so model names, permission states and transcription remain easy to scan.

- A cool near-white canvas with white section cards in light mode.
- Navy canvas and surfaces, pale-blue actions and white lettering in dark mode.
- A restrained three-stop gradient in the Settings header.
- A richer violet → indigo → blue gradient on the recording control.
- Clean line/glyph icons without colored tiles behind them.
- Soft corners, modest borders, clear type hierarchy and familiar Android controls.

The app follows its **System / Light / Dark** setting. Android wallpaper colors
do not replace this palette. Platform-owned permission screens and share sheets
remain under Android's styling control.

## 2. Logo, icon and provenance

The original design is [utterlane-logo-and-icon.png](utterlane-logo-and-icon.png).
The lettering is **Utterlane**, and the original tagline is
**FAST. OFFLINE. TRANSCRIPTION.**

| Asset | Use |
| --- | --- |
| Original design board | Immutable source and authorship reference |
| Cyan/blue U-and-waveform icon | Launcher, app identity, Settings header, repository-card illustration |
| Transparent navy/blue wordmark | Light header and light marketing backgrounds |
| Transparent white wordmark | Dark application headers |
| Monochrome notification mark | Android-controlled notification/themed-icon surfaces |

Preserve proportions and letterforms. Use the original wordmark image rather than
typing the name in an approximate italic font. Keep the original tagline with
the wordmark when space permits; accessible app-name semantics remain text.
Do not recolor the launcher icon to violet to match a control gradient.

### Source-derived exports

`tools/generate_brand_assets.py` validates the original image hash. It generates
`utterlane_wordmark.png` and `utterlane_wordmark_dark.png` in the Android
`drawable-nodpi` directory by removing the white matte offline. No bitmap tracing,
runtime image processing, additional network font or logo reconstruction is used.

For new compositions, leave at least one quarter of the icon's width around an
isolated icon, and about one quarter of the wordmark's letter height around its
lettering. The supplied source is raster: resizing does not add source detail.
The SVG documents embed those pixels and keep surrounding artwork editable.

## 3. Color system

Authoritative source:
[`ui/theme/Color.kt`](../../app/src/main/java/io/github/lrq3000/utterlane/ui/theme/Color.kt).
The [JSON token export](blue-harmony-tokens.json) and visual reference are generated
from that file. Hex colors below are opaque sRGB.

| Role | Light | Dark | Purpose |
| --- | --- | --- | --- |
| `background` | `#F1F5FB` | `#0E1727` | Screen canvas |
| `surface` | `#FFFFFF` | `#182438` | Cards, native panel |
| `text` | `#15263F` | `#E9EFFC` | Main content |
| `muted` | `#617088` | `#ADBAD0` | Supporting text |
| `primary` | `#155ECC` | `#A4C7FF` | Main actions, selected controls |
| `onPrimary` | `#FFFFFF` | `#082957` | Content on primary fill |
| `container` | `#EAF1FF` | `#233954` | Tonal controls, fields, results |
| `onContainer` | `#233E61` | `#D6E5FF` | Content on tonal fill |
| `outline` | `#748198` | `#8393AA` | Stronger control outline |
| `outlineVariant` | `#E5EAF3` | `#29374D` | Subtle section border |
| `violet` | `#7750BC` | `#D0B7FF` | Secondary brand accent |
| `violetContainer` | `#EDE9FC` | `#33284C` | Supporting violet surface |
| `onVioletContainer` | `#39265D` | `#EBDDFF` | Content on violet surface |
| `warning` | `#8A4B0B` | `#FFBF7A` | Native capture warnings |

Red is semantic, not decorative. Recording/destructive state colors retain
`#B3261E` and the light-on-dark counterpart `#F2B8B5`; Compose error roles retain
Material's light/dark error pairs. The floating microphone is red while recording.

### Gradients

All app gradients run **left to right**, with evenly spaced stops at 0%, 50%, 100%.

| Surface | Light stops | Dark stops |
| --- | --- | --- |
| Settings header | `#EDE9FC` → `#E4EFFF` → `#E3F6FF` | `#292043` → `#1C3051` → `#153349` |
| Waveform / idle floating mic | `#7750BC` → `#365ECD` → `#067BC9` | `#6542AA` → `#3459B4` → `#086FAC` |

These are the implemented A gradients. The reversed blue-to-violet gradient from
the B comparison was not selected. The logo/icon retain their own source-derived
cyan-to-blue identity rather than being recolored to the waveform stops.

Use solid fills under long-form text. When composing new surfaces, check actual
foreground/background contrast, including the lowest-contrast gradient stop;
do not infer accessibility from the color name or a single sample point.

## 4. Typography

The app uses the default Material 3 typography and Android system font. No custom
font is downloaded or bundled by this redesign. Preserve system font scaling.

| UI role | Compose style / native size | Use |
| --- | --- | --- |
| Dialog title | `titleLarge` (22 sp / 28 sp line height) | Transcription and prominent titles |
| Section heading | `titleSmall` (14 sp / 20 sp, medium) | Settings group labels |
| Setting title / transcript | `bodyLarge` (16 sp / 24 sp) | Reading content |
| Supporting description | `bodyMedium` (14 sp / 20 sp) | Status and secondary details |
| Compact note | `bodySmall` (12 sp / 16 sp) | Attribution and explanatory notes |
| Header Settings label / actions | `labelLarge` (14 sp / 20 sp, medium) | Small navigation/action text |
| Native recording title | 20 sp | Listening / processing state |
| Native details / warning | 13 sp / 14 sp | Model, time, signal warning |
| Native live transcript | 15 sp, up to three lines | Bounded live preview |
| Native progress / finish control | 19 sp / 16 sp | Progress and stop target |

The logo is artwork and does not substitute for UI typography. Reference SVGs/PNGs
use Arial or Liberation Sans for reproducible desktop composition; their sample
type is illustrative, while the app's Material styles remain authoritative.

## 5. Spacing, shapes and layout

Use a 4 dp spacing rhythm, with 8/12/16/20/24 dp as common increments.

| Element | Implemented geometry |
| --- | --- |
| Material shape scale | 8, 12, 16, 20, 24 dp from extra-small to extra-large |
| Settings section | 16 dp outer horizontal inset, 8 dp vertical inset |
| Section heading | 8 dp extra start inset, 10 dp gap before card |
| Section card | 20 dp radius, 1 dp outline-variant border, 8 dp inner vertical padding |
| Settings brand header | 24 dp horizontal / 20 dp vertical padding plus system insets |
| Header identity row | At most 360 dp wide, 42 dp icon, 12 dp icon/wordmark gap |
| Header waveform motif | 56 × 24 dp, 20 dp end / 8 dp bottom inset, 14% text-color opacity |
| Filled action buttons | 12 dp radius |
| Transcription result surface | 16 dp radius |
| Transcription dialog | 24 dp radius, 20 dp content padding, 24 dp vertical outside padding |
| Transcription width | 560 dp outer cap, then 94% fill inside available width |
| Native recording panel | 26 dp top corners, 20 dp horizontal / 16 dp top / 12 dp bottom padding |
| Native waveform control | 136 dp high, 24 dp radius, 12 dp top gap |
| Native cancel control | 48 dp high, 12 dp radius and top gap |

Model actions use a wrapping row **below** the details. This gives long model
names and translated text the available width, including on narrow screens with
larger text. Settings and long dialogs scroll. Header artwork is width-bounded in
landscape so it does not expand into a full-screen logo.

## 6. Component language

### Settings

Pair the original icon and wordmark in the subtle gradient header, with the
localized Settings label beneath. Group related controls in inset surface cards.
Use plain blue/pale-blue icons, semantic switches and unambiguous status text.
Keep model selection, download/load/reset/delete, permissions, history, corrections
and appearance controls available. Use red for destructive model deletion.

### Dialogs and transcription

Carry the same shapes, typography and tonal surfaces into model selection,
dictionary, history and confirmation dialogs. The transcription card has a clean
document icon, a close action, tonal transcript surface, blue Copy button and tonal
Share button. Android's floating window provides the backdrop dimming: do not add
a second rectangular scrim inside the window.

Dictionary text fields use the primary-container fill and 12 dp corners, with
distinct focus outlines. History entries use tonal rounded surfaces. Empty,
processing and error states are real content states, not decorative placeholders.

### Recording, IME and floating microphone

One native panel serves the voice IME and overlays. The whole waveform area is an
accessible finish button; actual PCM levels drive its white bars. The cancel
action is a separate tonal control. During processing, progress replaces the
waveform. A shared palette and preference observer keep native and Compose
appearances coordinated.

The idle floating microphone uses the voice gradient; active recording retains
red and the recording glyph. Preserve native ripple feedback, focus semantics,
existing content descriptions and system-inset handling.

### Motion and decorative motifs

The header waveform is static and decorative. It must not suggest a live
microphone. The functional waveform shows real capture levels; do not replace it
with a decorative looping animation. Standard control feedback is sufficient;
the redesign adds no ambient motion.

## 7. GitHub repository card

Selected composition: **app showcase**. Original wordmark and product statement on
the left, a simplified recording-panel illustration on the right.

- Canvas: **1280 × 640 px**, opaque RGB PNG, less than 1 MB.
- Safe inset: **80 px** on all sides, following the supplied template's visual
  inset. Every important text/image bound is validated by the generator.
- Wordmark: original transparent navy/blue artwork, with its tagline intact.
- Slogan, line 1: **Near real-time transcription. On your Android phone.**
- Slogan, line 2: **100% offline. 100% private. 100% free.**
- Repository identity: **github.com/lrq3000/Utterlane**.
- Illustration: original app icon, white recording sheet, A's violet-to-blue
  waveform and tonal Cancel control. Sample waveform/transcript, not a screenshot.
- The background uses the light header gradient. Red template guides appear only
  in a separate proof document, never in the upload-ready card.

Files: [PNG](utterlane-repo-card.png), [SVG](utterlane-repo-card.svg),
[safe-area proof](utterlane-repo-card-safe-area.svg).

GitHub recommends 1280 × 640 px for best display (at least 640 × 320), PNG/JPG/GIF,
under 1 MB. See the [official social-preview instructions](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview).
The 80 px inset is this composition's conservative interpretation of the supplied
template, not a claim of an additional GitHub upload requirement.

## 8. Implementation and maintenance

| Source | Responsibility |
| --- | --- |
| `ui/theme/Color.kt` | Shared immutable palette |
| `ui/theme/Theme.kt` | Material color roles, shapes, system bars, appearance selection |
| `ui/BrandComponents.kt` | Header identity and Settings section cards |
| `ui/theme/NativeBrandStyle.kt` | Native palette resolution, gradient and tonal ripples |
| `ui/RecordingPanel.kt` | Native capture/progress presentation |
| `tools/generate_brand_assets.py` | Original-art-derived logo/icon exports |
| `tools/generate_design_docs.py` | Shared SVG/PNG scenes and exported color tokens |

Regeneration and upload instructions are in the [design-kit index](README.md).
When the palette changes, regenerate the kit and review its images, token table
and contrast. Keep documentation examples distinguishable from actual application
state. The recorded implementation checks and runtime limits remain in
[the Blue harmony QA report](../qa/blue-harmony.md).
