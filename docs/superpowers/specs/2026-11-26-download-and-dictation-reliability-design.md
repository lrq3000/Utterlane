# Model downloads and keyboard dictation reliability

## Model setup

Keep the display awake only while an active speech or speaker model **download** is visible in onboarding or Settings. Use Android’s ordinary window flag; do not request permissions, change the system timeout, or prevent manual locking. Preserve safe partial progress in app-private storage, including across process restart, so the next Download or Retry does not need to start from zero. This is not a new background download service.

Keep the existing OkHttp timeouts and prompt cancellation. Reuse finished artifacts only when they still pass existing verification. Resume an incomplete file using HTTP byte ranges. For an artifact without a pinned checksum, require the server to repeat the matching strong validator; with a pinned checksum, verify the full representation before use. Never append a full response, a wrong range, or a representation without identity evidence. Publish only after the whole model verifies. An explicit Cancel removes the download’s checkpoint; an interruption merely stops the active request. Local imports do not become resumable downloads.

Alternatives considered: screen-on alone does not solve interrupted Internet access; Android’s download service can continue in the background but would replace the app’s existing transfer policy and add a second storage/lifecycle integration. The current downloader with private checkpoints is the smaller change.

## Keyboard gear

Add a small top-right gear with an accessible touch target to the keyboard voice-input panel, both for the speech-recognition activity and the voice IME. During recording, show an inline warning with **Keep recording** and **Stop and discard**. Warn that any text already inserted into the editor remains. Canceling the warning changes nothing; explicit confirmation discards this recording, including when automatic audio history is enabled. Once capture has closed and the app is processing, offer **Back to transcription** and **Continue in background**. Once confirmed, publish the exact existing transcription to the app’s queue before opening Settings. Do not start recognition a second time or attempt to insert delayed text into a different editor. The result remains reviewable in History if there is no notification permission. If the recording ends while a discard warning is visible, the stale action cannot discard it.

Alternatives considered: opening Settings directly can invalidate the editor, and keeping capture active inside Settings obscures when the microphone is live. The approved two-path confirmation gives an explicit discard only while recording and preserves recorded input and work in progress otherwise.

## Small independent changes

Seed the built-in `utterlane` → `Utterlane` whole-word, case-insensitive correction only where no dictionary file exists. A saved dictionary, including an empty one, remains authoritative. Reproduce the exact [AudioRecorder #183 SCO regression](https://github.com/Dimowner/AudioRecorder/commit/f9d83e478e40aced5353c5542d73b32a1f715cea) in Utterlane: registration returns sticky CONNECTED; no new broadcast follows. Retain Utterlane’s mode/device verification and phone fallback. Change production routing only if this exposes a gap.
