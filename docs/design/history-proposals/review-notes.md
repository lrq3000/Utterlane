# Concept review notes

These are three alternatives for maintainer selection, not an accepted Android
implementation. Each direction has a recording and transcript screen.

## Inspected visual points

1. **Hierarchy:** larger/bolder primary information; dates, durations and previews
   have distinct roles. Model/retention metadata stays secondary.
2. **Entry boundaries:** A uses outlined raised cards, B uses compact grouped rows,
   C separates chronology into a gutter. No duplicate global pin-state banner.
3. **Palette:** colors come from the current Kotlin Blue Harmony tokens. Measured
   contrasts: primary text/white 15.21:1, muted text/white 5.02:1, blue/light-blue
   5.29:1. An auto-darkening browser extension initially changed colors; the review
   page opts out locally with `darkreader-lock`, without changing browser settings.
4. **Text and bounds:** all nine SVG/PNG renders passed the shared renderer's safe
   bounds checks. All three comparison PNGs were inspected at their complete sizes.
   Browser text bounds do not intersect pin targets in any of the six screens.
5. **Controls:** every entry has a whole-row hit area and one independent 48 × 48
   pin target. Timeline whitespace is included. No visible Open or Delete control
   is present inside list entries.
6. **Density:** A prioritizes separation, B fits more entries, C makes chronology
   and duration/content more prominent. The same example records support comparison.

## Interaction verification

Verified with the browser-controller extension in an owned localhost tab:

- Pin toggles on both list types in all three directions without incrementing the
  item-open counter or opening the detail dialog.
- Whole entries open the appropriate recording/transcript detail in all directions.
- The detail exposes Delete recording or Delete transcript; mock deletion removes
  the example entry and compacts subsequent rows.
- Closing/reopening a history pane preserves its current mock pin state. Selecting
  a direction resets its synthetic examples.
- Rapid overlapping direction changes leave only the latest direction displayed.
- Browser rendering uses the native 440-pixel screen width, with vertical page
  scrolling at a 1150 × 791 CSS-pixel viewport and no horizontal overflow.
- Reloaded page interaction produced no application console errors. Earlier
  `createLegacyRefRuntime` errors came from browser-control reference injection;
  direct prototype interaction after clearing those messages was clean.

PNG/SVG generation uses the existing supersampled Pillow/Arial documentation
renderer. Browser screenshots of the SVG review page were compared for typography,
geometry, palette, wrapping and pin states. Browser-control borders/banners are
tool overlays and are absent from the delivered PNGs.

No Android runtime changes or APK build are part of this concept-review milestone.
Native Compose typography, dark theme, large text, and device-sized touch targets
will be validated when the selected direction is implemented.
