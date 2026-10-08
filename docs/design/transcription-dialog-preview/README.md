# Transcription dialog — three design proposals

## Approved brief

Produce three polished browser mockups of the existing Android transcription
dialog. The user approved these directions on 2026-10-08:

- **A · Quiet focus:** a balanced reading surface with a compact bottom toolbar.
  Recommended for familiar placement and full-width text.
- **B · Open page:** transcript actions in the header, maximizing reading height.
  Best for long transcripts; the header is more densely populated.
- **C · Reading rail:** a document surface with a narrow vertical action rail.
  Keeps actions close to the text, exchanging some line width for reading height.

Use the existing [Blue harmony palette](../blue-harmony-spec.md), system fonts,
thin rounded borders, and consistent outline icons. The pin uses the app's
Material PushPin silhouette, filled when retained. Every icon has an accessible
name, tooltip, and at least a 48-pixel interactive target. Delete remains written
out; the top-right close icon is the sole close control in each open dialog.
The transcript fills remaining vertical space instead of being capped at 300 dp.
Its persistent, high-contrast scrollbar must reflect and control actual scrolling.

## Function inventory

Checked against `TranscriptionDialog.kt`, `AudioPlaybackControls.kt`, and
`TranscriptionDialogModel.kt` at the worktree baseline (`b5e3074`).

| Existing capability | Preview representation |
| --- | --- |
| Close | Top-right cross; preview can be reopened |
| Playback, pause/resume, stop, seek | Compact audio strip, simulated playback state and seek position |
| Retranscribe using same model / choose another model | Circular-arrow icon opens the two existing choices |
| Save audio to history / share audio / save to device | Audio-save icon opens the three existing choices |
| Copy and share transcript | Outline copy and share icons; preview-only feedback |
| Keep transcript forever | Outline pin becomes filled after saving |
| Previous and next transcript page | Back/forward chevrons in the document footer |
| Delete transcript / delete audio / discard | Explicit destructive text, matching the preview source |
| Progress, retention, recovery information | Preview state selector and compact inline status |
| Optional processing statistics | Separate review toggle; rendered only while processing |
| Transcript without retained audio | Text-only preview; audio-dependent controls are disabled |

The current native save-transcript action pins but does not toggle unpinning.
The mockup therefore keeps the pin filled after saving; clicking it again reports
that it is already retained. Reset preview restores the initial state. A native
unpin action would be a separate functional decision, not an implicit redesign.

## Artifact plan and verification

1. Create `index.html` for the review controls and the three phone-sized canvases.
2. Create `styles.css` for shared tokens, three layout variants, responsive
   comparison/focus views, and light/dark appearance.
3. Create `preview.js` with a shared dialog renderer/controller, local sample
   transcript, icon definitions, accessible menus, and simulated interactions.
4. Serve this directory on loopback and inspect it in the real Chrome browser.
   Check all layouts, light/dark, a narrow phone, larger text, pin state, paging,
   scrolling, playback controls, both menus, close/reopen, and conditional states.
5. Check JavaScript syntax and `git diff --check`, then commit the complete study.

These are design-review artifacts. All displayed recordings, transcripts, progress,
and action outcomes are illustrative. The preview must not read real recordings,
write to the clipboard, share files, or call transcription services. It uses no
external assets, fonts, libraries, or network dependencies.

## Review

Open `index.html` directly, or run from this directory:

```console
python -m http.server 8773 --bind 127.0.0.1
```

Then open <http://127.0.0.1:8773/>. Compare all three, or focus one at full size.
Use the review controls to change appearance, phone width, text size, and state.
Scroll inside the transcript, tap the pin, open audio/retranscription menus, and
try the close/reopen interaction. Functional Android implementation follows only
after selection and design approval.

## Browser review record

Reviewed in Chrome through browser-controller on 2026-10-08:

- All three complete designs inspected together; individual view restores full
  size. Comparison scales the entire phone, preserving its internal geometry.
- Light/dark palette, consistent outline icons, Material pin states, thin document
  boundary, visible scrollbar, typography, and labeled deletion inspected.
- Desktop viewport: 1150 × 791. Phone canvases: 392 and 320 pixels wide. A separate
  360 × 780 iframe viewport verified responsive page stacking without horizontal
  overflow. Transcript text checked at 100% and 150%.
- Fixed a review finding: B's narrow layout now gives actions a second row while
  keeping the sole close icon at the top right. Visible controls remained within
  the phone bounds at 320 pixels.
- Scroll changed the transcript position by 300 pixels without moving the dialog.
  Paging replaced the content, reset scrolling, updated boundary-button states,
  and preserved the pin state. Other proposals retained independent pin states.
- Play/pause/stop and simulated seek checked; setting the range to 84 seconds
  produced `01:24 / 02:48`. Pointer dragging could not be verified because the
  automation drag call timed out; input-driven seek feedback was verified.
- Both menus, model-choice feedback, copy/share feedback, close/reopen, keyboard
  Escape for menus, and the 48 × 48 unscaled icon targets checked.
- Processing with optional stats, recovery, text-only, and temporary-audio
  information checked. Audio-dependent actions disable in text-only state;
  pin/retranscribe disable while processing.
- Fresh page load had no console messages. HTML, CSS, and JavaScript returned
  HTTP 200 locally; the final page has no external requests or missing favicon.
- `node --check docs/design/transcription-dialog-preview/preview.js` and
  `git diff --check` passed.

The three layouts are proposals for selection, not an approved native redesign.
No Android runtime behavior is established by this browser-only review.
