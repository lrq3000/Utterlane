# Transcription progress: review concepts and D refinement

Design review requested on 2026-10-09. The user subsequently approved refinement D;
its native implementation is documented in [the QA note](../../qa/transcription-progress-d.md).
These browser files remain illustrative review artifacts. Open `content/progress-layouts.html`
directly in a browser; it is self-contained, with inline icons, local fonts, and
no external dependencies. The brainstorming visual companion can also serve it.
Its transient review state is ignored by Git.

## Current behavior verified in source

Baseline: `9dc33a8` (local main).

- `TranscriptionDialog.kt:108-124` renders a progress bar and “Transcribing…” but
  no numeric percentage or remaining-time label. Optional statistics report
  recognition activity and accepted/processed/backlog audio, not a completion ETA.
- `TranscriptionDialogModel.kt:183-213` periodically exposes growing transcript
  text and metrics. Its separate `latestProgress` comes from decoding callbacks
  or a recording reader's sample offset, capped at 99 until completion. This
  measures input advancement, not necessarily completed recognition.
- `RecordingPanel.kt:168-174` already renders percentage and approximate remaining
  seconds in the microphone panel.
- `CaptureMetrics.kt:123-149` smooths measured processing cost per sample and
  estimates the outstanding accepted audio once capture has ended. File input
  needs a whole-file denominator while reading is still underway; copying these
  fields alone does not provide a whole-file completion estimate at that point.
- `TranscriptionDialogModel.kt:213-228` waits for session finalization (including
  the enabled speaker finisher) and result persistence before publishing completion.

This was a source inspection, not an observation of the user's installed APK.

## Proposed alternatives

1. **A — Compact strip (initial recommendation).** Stage and percentage, linear bar, then
   approximate time remaining and processed/total audio above the reader. Closest
   to the existing dialog; a small, persistent footprint supports reading.
2. **B — Time-first card.** A tonal card emphasizes the ETA alongside a progress
   ring, with stage and audio position below. Easier to spot, less room for text.
3. **C — Reader status dock.** A compact status area fixed below the scrollable
   document, inside its border and above the playback/text controls. Clear top
   area, but remaining-time information is farther from the dialog title.
4. **D — Minimal reader dock (requested refinement).** C's bottom placement with
   A's plain surface, thin linear bar, and compact stage/percentage/ETA labels.
   A subtle divider replaces the colored card treatment. On completion the bar
   and timing row give way to a single completion row, expanding the reader
   downward while keeping its top fixed.

The user preferred C because top-mounted progress competes with reading, freeing
space above the reader can shift its position, and new text arrives toward the
bottom where the progress belongs. These reasons supersede the initial A
recommendation. D preserves that reading-first placement while borrowing A's
minimal visual style. The preview now opens with C and D side by side; the Compare
control also offers D alone and all four designs. State changes retain each
visible reader's scroll offset when the resulting document has enough content.

All share the existing BlueHarmony palette, dialog-D action arrangement, visible
scrollbar, and partial transcript. Dropdowns preview light/dark themes, 360/320 px
outer widths, and seven states. The progress fixtures and transcript are invented
examples, not runtime measurements. The seven states are loading, early ETA
calibration, measured progress, unknown duration, speaker finalization, complete,
and interrupted with partial text retained. Native action icons are illustrative;
the preview controls and transcript scrolling are interactive.

## Implementation principles for whichever layout is selected

- Keep progress visible by default, independent of advanced statistics, and
  outside the transcript's scrolling region. Preserve the reader's position when
  segments arrive. Avoid rebuilding the reader just to refresh its status label.
- Percent means completed audio transcription, using the processed ownership
  watermark and a trustworthy total, excluding overlapping context. Do not use
  decoded/read bytes as recognition completion. If only approximate duration
  metadata is available, label that uncertainty; if unavailable, omit percentage.
- Estimate time to the complete result using observed processing throughput and
  any measurable outstanding preparation/finalization work. The existing smoothed
  rate is a useful building block, not proof of an end-to-end estimate. Do not
  mislabel a speech-only estimate as time to the finished transcript.
- Before reliable measurements, show “Estimating time…”. If the total is unknown,
  show processed duration and “Time not yet available”. An ETA may increase when
  measured conditions change; it must not count down merely because time passes.
- Optional speaker finalization gets an explicit stage. The example deliberately
  replaces percentage with indeterminate progress and says “Estimating time…”
  because no measured finishing ETA is supplied. Only show complete/100% when the
  full requested operation has succeeded. Never reserve arbitrary percentage
  ranges for unknown stages or disguise them with a frozen 99%.
- Preserve partial text and an actionable retry message on interruption. Confirm
  actual recoverable data before stating that it was kept. Respect retention,
  dismissal, and deletion policies; this is not a new background execution design.
- Use the existing presentation cadence and constant-size numeric state. No
  transcript scans, extra inference, waveform processing, or unbounded history
  are needed for this presentation. Speak major state changes accessibly without
  announcing every ETA tick. Native labels must wrap under large font settings.

## Review and validation

Before native implementation, choose a layout. Validate its progress accounting
with deterministic cases for unknown totals, partial input, finalization, errors,
and completion; inspect native geometry with large fonts and narrow windows.
Browser mockups do not establish Compose geometry or ETA accuracy on a device.

### Browser checks performed

- Served locally at `http://127.0.0.1:8786/` with the existing brainstorming
  companion. Its random port first landed in a Windows-reserved range; selecting
  port 8786 resolved the verified bind failure.
- Chrome browser-controller created the review tab, but snapshot returned no
  tree and screenshot/evaluate/CDP-action requests timed out. Validation used the
  already-installed Python Playwright with a separate headless Chrome instance.
- Page identity and three meaningful dialog previews verified at 1320 × 1100.
- Exercised all 7 states × 2 themes × 2 widths: **28 combinations, 84 layouts**.
  Checked horizontal overflow, title fit, usable reader height, status containment,
  complete-only 100%, and no invented total in the unknown-duration state.
- At 390 × 844, confirmed no page-width overflow and scrolled B's document to its
  end. The first scroll check targeted A, whose sample text fit without overflow;
  inspecting scroll/client heights identified that test precondition, then the
  check was corrected to use the actually overflowing B reader.
- No JavaScript page errors or console errors in the successful matrix run.
- Visually inspected the light comparison, narrow dark finalization, and stacked
  mobile screenshots. Evidence is local, outside the repository, in
  `C:/Users/33632/AppData/Local/Temp/opencode/transcription-progress-{light,finalizing-dark,mobile}.png`.
- No Android build was needed for these self-contained design artifacts. Native
  large-font/localization behavior and real ETA accuracy await an approved design
  and its implementation.

### D refinement verification

- Rechecked all seven states in both themes at both widths for all four designs:
  **28 combinations, 112 layouts** passed, with no runtime/console errors.
- Verified C/D comparison, D-only, and all-four preview selection.
- Checked D's finalizing-to-complete transition at 320 px: the reader's top stayed
  at the same measured browser coordinate, gained 39.5 px downward, and retained a
  50 px scroll offset. The completed footer contains no progress bar.
- Inspected the light C/D comparison, isolated light D, and narrow dark D at a
  390 × 844 mobile viewport; no horizontal page overflow.
- Used the existing Python Playwright/Chrome validation path and
  `git diff --check`. Updated screenshots are local:
  `transcription-progress-c-d.png`, `transcription-progress-design-d.png`, and
  `transcription-progress-d-mobile-dark.png` in the same temporary directory.
