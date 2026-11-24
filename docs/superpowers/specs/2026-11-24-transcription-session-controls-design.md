# Transcription session controls and completion

## Intent

Users alternate between single-speaker dictation and multi-speaker files. A file needs a clear, extensible setup dialog before recognition; a live recording needs to start immediately and let the user change speaker labeling without losing audio. Reopened transcripts must distinguish a complete result from surviving partial text.

## File setup

Use a dedicated, localized Android activity for Android Share/Open with and the Home file picker. Show **Transcribe audio**, a **Speaker labels** switch (off for every new file), **Cancel**, and **Transcribe**. The choice is local to the file and does not read or write the global enable setting. Keep the configured speaker count/model; when the model is needed but unavailable, explain how to enable labeling and let plain transcription proceed. Cancel before any owned import or recognition. Preserve URI access and rotation state, route accepted Home files back to the Home result owner, and reuse the existing retention/import and recognition flow. No other per-file options without the maintainer's approval. Do not add the dialog to live microphone entry points or change notification automation.

## Live control

Use a compact accessible toggle by the waveform on Home and on the shared microphone panel. Start with the configured live default and never write it back from the recording toggle. Apply the latest choice at the next uncommitted recognition window, drain pending words before leaving speaker mode, and leave committed text untouched. Preserve absolute audio ownership and use a fresh speaker run when re-enabled; speaker numbers are not identities across unlabeled gaps. Missing optional speaker resources must not block recording or plain recognition. Do not replay the earlier recording or add a second continuous ASR pass.

## Completion

Use per-result states `UNKNOWN` (legacy/untracked), `INCOMPLETE`, and `COMPLETE`. Register `INCOMPLETE` in the working transcript's atomic provenance before text can be published. Promote only after successful EOF, decoder/correction drain, and, for live recording, no capture or storage interruption. Keep/copy/history reopening must carry the state of the exact transcript version, not infer it from linked audio or another retry. Show **Partial transcript** on inactive, incomplete results without inserting it into the user's text. Keep existing history and deletion policies. No periodic metadata write or progress polling; omit a stopping position rather than invent one.

## Approaches

The maintainer selected explicit file-only setup over automatic recognition with a destructive/redundant redo. For live capture, reuse the existing owned windows and speaker drain rather than restart capture or relabel historic text. For completion, reuse the working sidecar and saved history metadata rather than infer state from audio recovery or create a separate database.
