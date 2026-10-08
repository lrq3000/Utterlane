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
name, tooltip, and at least a 48-pixel interactive target. A red outline trash icon
sits at the far right of the audio-control row, after retranscribe and download;
a top-left back arrow is the sole dismissal control in each open dialog.
This follow-up revision separates dismissal from pin/copy/share, particularly in
B's header, to reduce accidental exits. The title separates Back from these tools;
on narrow phones, B's tools occupy a second row while Back stays at the top left.
The transcript fills remaining vertical space instead of being capped at 300 dp.
Its persistent, high-contrast scrollbar must reflect and control actual scrolling.
The latest revision removes page controls and page counts: the entire sample is
one continuous scroll region. A future native implementation should retain bounded
disk-backed loading behind continuous scrolling rather than loading arbitrarily
large transcripts into memory.

Every trash-icon tap opens a confirmation with **Cancel** and **Delete**:
**Delete this audio recording?** for recording-origin views, or **Delete this
transcript?** for transcript-origin views. The source of the view determines the
question, even when a saved transcript still has audio available. Cancel receives
initial focus, Escape cancels, and backdrop clicks never confirm. The preview's
underlying phone controls are inert until the confirmation is dismissed.

## Function inventory

Checked against `TranscriptionDialog.kt`, `AudioPlaybackControls.kt`, and
`TranscriptionDialogModel.kt` at the worktree baseline (`b5e3074`).

| Existing capability | Preview representation |
| --- | --- |
| Close / go back | Top-left outline back arrow; preview can be reopened |
| Playback, pause/resume, stop, seek | Compact audio strip, simulated playback state and seek position |
| Retranscribe using same model / choose another model | Circular-arrow icon opens the two existing choices |
| Save audio to history / share audio / save to device | Audio-save icon opens the three existing choices |
| Copy and share transcript | Outline copy and share icons; preview-only feedback |
| Keep transcript forever | Outline pin becomes filled after saving |
| Read the complete transcript | Continuous scrolling; pagination controls removed at the user's request |
| Delete transcript / delete audio / discard | Red trash icon on the audio row, followed by source-specific confirmation |
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
   Check all layouts, light/dark, a narrow phone, larger text, pin state,
   continuous scrolling, deletion confirmation/cancellation, playback controls,
   both menus, close/reopen, and conditional states.
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
try the close/reopen and delete/cancel interactions. Compare **Complete**,
**Transcript + audio**, and **Text only** to see the deletion subject follow the
view's source. Functional Android implementation follows only after selection
and design approval.

## Browser review record

Reviewed in Chrome through browser-controller on 2026-10-08:

- All three complete designs inspected together; individual view restores full
  size. Comparison scales the entire phone, preserving its internal geometry.
- Light/dark palette, consistent outline icons, Material pin states, thin document
  boundary, visible scrollbar, typography, and labeled deletion inspected.
- Desktop viewport: 1150 × 791. Phone canvases: 392 and 320 pixels wide. A separate
  360 × 780 iframe viewport verified responsive page stacking without horizontal
  overflow. Transcript text checked at 100% and 150%.
- Initial review fixed B's narrow layout by giving actions a second row while
  keeping dismissal in the header. The subsequent user-requested revision replaces
  the top-right cross with a top-left back arrow in all three concepts.
- Follow-up review verified Back precedes the title in all three layouts at 392
  and 320 pixels, with 48 × 48 targets and separate transcript actions. At 320
  pixels, B's actions occupy the second row and all visible controls fit inside
  each phone. Back/reopen was exercised in B and restored focus to Go back.
- Initial review checked scrolling and pagination. Revision 3 replaces pagination
  with continuous scrolling and preserves all 11 sample paragraphs, including the
  former second page's final paragraph. Other proposals retain independent state.
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

Revision 3 was additionally checked in light and dark appearance:

- No page controls or counters remain in any concept. The audio-history saved
  message is retained, and each red trash icon is rightmost and aligned with Play.
- All visible toolbar buttons fit the 320-pixel phone, including during playback
  when Stop appears. The seek slider moves below the action row only when needed;
  icon targets remain 48 pixels. The retranscription menu also fits within the phone.
- Tapping trash leaves the transcript visible behind a confirmation. Cancel closes
  the confirmation, preserves the text, restores toolbar interaction, and returns
  focus to trash. A second tap asks again; explicit Delete then closes the preview.
- Audio-origin, transcript-with-audio, and text-only confirmation subjects were
  verified. Text-only disables playback while retaining an enabled trash icon.
- Keyboard Tab stays between confirmation choices, and Escape cancels and restores
  focus without deleting.

The three layouts are proposals for selection, not an approved native redesign.
No Android runtime behavior is established by this browser-only review.
