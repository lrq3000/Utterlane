# History listing proposals

Three review concepts, each showing recording and transcript history. These are
mockups, not Android screenshots or a selected implementation. All data is synthetic.

## Shared interaction contract

- The entire entry opens its item. There is no per-entry Open button.
- The pin is the sole independent control on an entry; activating it never opens
  the item. Pinning does not reorder the list under the user's finger.
- Delete recording/transcript appears only after opening the item, never in the list.
- Pin/unpin keeps the approved forever/fresh-countdown semantics.
- Date, duration and transcript preview are more prominent than secondary metadata.
- All variants use the implemented Blue Harmony palette and the original wordmark.

## Directions

| Direction | Intended strength | Comparison | Detailed screens |
| --- | --- | --- | --- |
| A — Focused cards | Clearest entry boundaries, stronger text hierarchy, generous touch targets | [A](a-comparison.png) | [Recordings](a-audio.png), [Transcripts](a-text.png) |
| B — Compact grouped list | More entries visible, chronological grouping, lower visual bulk | [B](b-comparison.png) | [Recordings](b-audio.png), [Transcripts](b-text.png) |
| C — Chronological timeline | Time separated from content; duration/words become the focal point | [C](c-comparison.png) | [Recordings](c-audio.png), [Transcripts](c-text.png) |

`index.html` is an interactive review page: choose a direction, tap a pin, or open
an entry to see where deletion belongs. Its actions affect synthetic browser state
only. The retained Android application remains the production implementation until
the maintainer selects a visual direction.

## Reproduction

```text
python tools/generate_history_mockups.py
python -m http.server 8769 --bind 127.0.0.1 --directory docs/design/history-proposals
```

Open `http://127.0.0.1:8769/`. The generator reuses `generate_design_docs.py` and the
existing Pillow dependency. PNG and SVG share one set of scene primitives, with
safe-bound checks and bounded text wrapping. Individual screens are 440 × 920;
comparison boards are 976 × 1080. SVGs remain editable.

No image-generation service is available in this harness; these targeted concepts
are rendered from the existing design system with exact readable text and icons.
Mockup typography uses the existing documentation renderer's Arial/Liberation Sans
fallback. An approved Android implementation will use the app's native typography
and layout constraints, including font scaling and theme support.
