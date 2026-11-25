# Compact live speaker control

**Follow-up decision:** Match the Home waveform instead of placing the toggle in the listening header. The shared native panel uses the same outlined speaker icon and checked colors, inside a circular button at the waveform's top trailing corner. The off state has no fill. Keep the native tooltip, the panel height, and separate speaker and finish hit areas. The original design below is retained for historical context.

The existing live toggle must not add a row to the recording panel. Reuse the shared native panel in the keyboard, overlay and onboarding. Put a two-person conversation icon at the end of the listening header, use the app’s selected blue and theme-neutral off colors, and provide Android’s standard long-press tooltip and checked accessibility state. Preserve the existing recording and speaker-label behavior. Keep the normal recording height unchanged relative to the panel without the toggle; allow text to wrap if accessibility settings or a translation require it.

An icon in the existing header is preferable to shrinking a standalone switch row (still adds height) or putting a control over the waveform (interferes with Stop).
