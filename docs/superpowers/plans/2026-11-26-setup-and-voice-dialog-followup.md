# Setup and voice dialog follow-up implementation plan

> **For agentic workers:** Use `executing-plans` or `subagent-driven-development` to implement the plan task by task.

**Goal:** Make interruption recovery honest, add the Settings gear to Utterlane-owned system dictation dialogs, and expose project information in Settings.

**Architecture:** Reuse app-private model checkpoints, the existing phase-aware dictation handoff, and the Settings page directory. Keep the three changes independently testable.

**Tech stack:** Kotlin, Compose, Android Views, OkHttp, JUnit/Robolectric and Android instrumentation.

**Spec:** [Setup and voice dialog follow-up](../specs/2026-11-26-setup-and-voice-dialog-followup-design.md)

**Global constraints:** No new permissions or dependencies. No commit to or push of main in this follow-up. Do not weaken model integrity or reinterpret idle Settings navigation as consent to discard audio.

## Review focus

- A connection loss before retry headers arrive must retain the previous offset and must not show an invented restart.
- Range rejection may require a full transfer; a valid suffix is not tied to the ETag when the full digest is pinned. Incorrect combined bytes must still fail verification.
- Floating and accessibility dialogs look like keyboard dictation; verify real title and gear controls rather than the prompt in isolation.
- Opening Settings must neither discard a live recording on the first tap nor hand the queued transcript to a different editor.
- About must use the installed package version and must not contact GitHub on entry.

## Tasks

- [x] **Download:** Add `ModelResumeTest` cases using a known prefix, delayed headers, a changed CDN validator, and a wrong joined digest. Update `ModelDownloadCheckpoint`, `ModelManager`, and the onboarding adapter and page so retained and actual transfer progress are distinguishable. All 44 focused download and onboarding checks passed; `4c5a79ae`.
- [x] **Shared dictation:** Reproduced the missing onboarding gear, then verified position and dismissal in onboarding, the actual voice IME, and the shared native adapter for floating/accessibility dictation. The voice-intent Android test kept the microphone running on dismissal, discarded only after confirmation, and preserved the original audio and one transcript on background handoff. Tests also reproduced the pending-handoff cancellation and detached-clipboard defects before the fixes.
- [x] **About:** Android navigation verified the installed version, license, project lead, open-source statement, offline privacy copy, GitHub `ACTION_VIEW` destination, and Back. Committed independently as `f1e89081`.
- [x] **Integration:** The standard `assembleDebug` build and APK signature verification succeeded. The full `:app:testDebugUnitTest` rerun passed all 1,223 tests; the first run had one failure in the unchanged `QueueJournalTest`, which also passed in isolation. Eight Android 9 QA tests passed, covering download recovery and display, About, onboarding, voice intent, the native voice IME, and the common floating/accessibility overlay. These checks did not invoke the floating and accessibility shortcuts individually. Restored the isolated guest's package baseline and verified that both owned emulator processes exited.
