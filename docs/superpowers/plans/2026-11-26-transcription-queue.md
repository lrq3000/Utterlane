# Background transcription and live priority implementation plan

**Goal:** A person can submit multiple recordings, dictate while files are waiting or processing, and safely retrieve each result independently.

**Architecture:** One recognizer serves live requests first at safe inference boundaries. A durable, application-owned job identity connects each captured or imported source to its foreground processing notification and review dialogue; notification dismissal and Android lifecycle events never acknowledge review.

**Tech stack:** Kotlin coroutines, Android foreground services, Storage Access Framework and Compose. Reuse the existing transcription, private import, history and exit-retention machinery.

**Spec:** The approved queue and microphone workflow in this conversation and the responsiveness example in `CORE_VALUES.md`.

## Constraints and review focus

Preserve input, context and speaker attribution across yields; a past capture must not own the microphone after its hardware has closed. Keep file inference serial, with no second model or folder import. Never prune unread results, retarget an old notification, treat a temporary URI grant as durable, or let failure of one file cancel its siblings.

### Task 1: Live priority and hardware admission

- [x] In `asr/RecognizerManager.kt` and `MicrophoneSession.kt`, give ready live work priority at recognition call boundaries, including preparation and speaker operations. Demote stopped captures and release only the physical capture guard once the recorder closes. Preserve reset/cancellation safety for draining sessions.
- [x] Test serialized ordering, cancellation and admission/late-release isolation using deterministic coroutine tests. Run the focused unit tests and commit.

### Task 2: Durable jobs and review

- [x] Add an application-owned queue under `transcribe/`, using ordered durable descriptors and individual attempt/result identity. Secure input grants or stage private copies before relying on them. Process a single file producer at a time and retain unopened audio and text independently across restart.
- [x] Exercise three jobs, out-of-order review, interrupted publication and independent file failure. Wire foreground notifications and review through stable job identity. Keep retention prompts in the visible setup path.

### Task 3: Connect the user journeys

- [x] Connect multi-selection and confirmed share/open to the queue, add the Home queue/review list, background action, and independent microphone tail handoff. Preserve normal inline feedback and explicit close/pin/discard.
- [x] Run focused and full relevant tests, assemble the debug APK and exercise batch, live-priority and retention journeys on a controlled emulator where available. Review the full branch and record measured limitations.

## Verification

All 1,148 JVM tests passed in 147 suites with zero skipped. The final Android instrumented journey and cold Home checkpoint test passed on API 28 with the same production source as the normal debug APK. The journey used a deterministic document picker response and public-domain speech fed to the capture pipeline. It verified live text before Stop during file processing, admission of the next recording while the prior take drained, four independently retained results, exact notification routing, and out-of-order discard without losing the first result. This verifies the tested Android lifecycle, not physical microphone latency or Android 13+ notification permissions on a device.
