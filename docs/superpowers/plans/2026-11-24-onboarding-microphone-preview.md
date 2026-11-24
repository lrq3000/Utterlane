# Onboarding Microphone Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the actual keyboard recording panel in the “Something to say?” onboarding trial.

**Architecture:** Onboarding continues to own the recording and editable transcript. A session-scoped binding hands existing capture metrics and live speaker control to the `RecordingPanel` hosted through Compose `AndroidView`; the panel consumes metrics directly rather than recomposing the wizard for every waveform update.

**Tech Stack:** Android Kotlin, Compose, native Android Views, coroutines `StateFlow`, JUnit and Android instrumentation.

**Spec:** [Onboarding microphone preview design](../specs/2026-11-24-onboarding-microphone-preview-design.md)

## Global Constraints

- Keep existing onboarding microphone permission, recording/retention behavior, and trial limit; do not require an enabled keyboard, an overlay permission, or a separate recorder.
- Keep panel controls reachable on short displays by allowing the panel to scroll.
- No inference algorithm or external dictation contract changes.

## Review Focus

- Gate the panel by the active trial, not by model readiness after capture starts.
- On a second trial, do not carry the previous preview or bind to a preceding session. Exercise Cancel and reopen.
- Keep the panel visible while recognizer results drain and preserve the final field text after dismissal.
- Verify the panel lifetime is tied to its Compose host and that backgrounding retains the existing controller policy; use the existing backgrounding regression.
- Exercise Stop during the capture-to-processing boundary, Cancel with delayed release, and the transcript's visible area on the short LDPlayer window.
- If real-model inference is unavailable in the isolated guest, distinguish controlled UI/pipeline-input evidence from real speech-recognition evidence.

### Task 1: Host the shared keyboard panel

**Files:** `onboarding/OnboardingVoiceTrial.kt` and `AppOnboardingServices.kt` for the binding, `OnboardingScreen.kt` and `OnboardingTrialPages.kt` for the bottom host and idle mic, `OnboardingControllerAndroidTest.kt` for the panel journey, `OnboardingVoiceTrialTest.kt` for state lifetimes, and `OnboardingRecognitionAndroidTest.kt` for the real-model stop selector.

**Interfaces:** `OnboardingTrialSession.panel: VoiceTrialPanel` supplies `StateFlow<CaptureSnapshot>` and `LiveSpeakerControl`; `VoiceTrialState.panel` is present only for the active session and `VoiceTrialState.preview` carries only that session’s recognized text. Bind `RecordingPanel.bind(scope, metrics, speakerControl)` on creation, update its text with `preview(text)`, and release it on removal.

- [x] Add `voiceTrialUsesKeyboardWaveformThroughProcessing` and `cancelClosesThePanelAndTheNextTrialStartsWithAnEmptyPreview`; run the waveform regression against the isolated baseline. Expected: the keyboard waveform is not found on the active onboarding trial.
- [x] Add a trial-state assertion for session-specific preview and presentation lifetime; wire the real session's metrics and control through `VoiceTrialPanel`.
- [x] Host the panel outside the scrollable page body, remove the duplicate active-caption/stop/cancel UI, hide the soft keyboard when the panel opens, and use the existing trial controller for Stop and Cancel. Give the panel a scrollable height cap so it cannot overflow the window.
- [x] Run `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` and isolated `OnboardingControllerAndroidTest` / `CapturePanelAndroidTest` / `WaveformButtonAndroidTest`. The guest had no verified model, so the Android checks use controlled recognition callbacks.
- [x] Inspect the complete diff and commit the coherent feature and regression tests locally.
