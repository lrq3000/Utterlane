# Model Idle Unloading Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan inline.

**Goal:** Free model memory after configurable transcription inactivity.

**Architecture:** A pure idle-deadline policy drives one manager-owned timer;
the existing inference mutex serializes timer expiry with loading and decoding.
Application-scoped preference and wake observers update the timer.

**Tech Stack:** Kotlin, coroutines, DataStore, Compose, JUnit, Android instrumentation.

## Task 1: Policy and deterministic tests

- [x] Add `asr/ModelIdlePolicyTest.kt` under the existing unit-test namespace.
  Test stable preference keys and all exact millisecond durations; a default
  deadline of 1,200,000 ms; no deadline when busy; idempotent idle notifications;
  restart after activity; Immediate/Never; shortening/extending an existing
  deadline; sleep represented by elapsed-clock advance.
- [x] Run `gradlew.bat testDebugUnitTest --tests '*ModelIdlePolicyTest' --console=plain -q`
  and confirm missing policy prevents compilation.
- [x] Create `asr/ModelIdlePolicy.kt` in main with `ModelIdleTimeout` enum
  (`key`, nullable `milliseconds`, `fromKey`) and `ModelIdlePolicy`:
  `setIdle(Boolean)`, mutable `timeout`, `remainingMillis(): Long?`.
  Keep the idle timestamp on repeated `setIdle(true)`; clear it on false.
- [x] Run the focused tests and confirm they pass.

## Task 2: Manager ownership and live preference

- [x] Add focused integration cases in `ModelIdleAndroidTest.kt` for timed
  expiry, Immediate, concurrent sessions, pending loads, stale timers and reload.
  Follow ModelRecoveryAndroidTest's verified-model setup and injectable backend; use an injected clock
  to expire long timeouts without waiting minutes.
- [x] Extend `RecognizerManager.kt`: track pending model operations as well as
  sessions; update idle state on operation/session completion. Timer callbacks
  acquire the inference mutex, check a revision token under the state lock,
  recheck the deadline and detach/close only an idle backend. Invalidate timers
  on detach and busy transitions. Expose timeout update and wake recheck methods.
- [x] Extend `SettingsRepository.kt` with a stable string preference and typed
  flow/setter. Observe it from `UtterlaneApp.kt`, retain a DeviceWakeObserver,
  and skip startup initialization when the stored timeout is Immediate.

## Task 3: Settings and documentation

- [x] Add `settings/ModelIdleSettings.kt`, using the existing ListItem/dialog
  pattern and accessible single-choice radio rows. Add it beside auto-load in
  `settings/SettingsActivity.kt`; explain preload suppression for Immediate.
- [x] Add English labels/help text to `res/values/strings.xml`; update README
  with the default, choices, on-demand reload and sleep behavior.

## Task 4: Verify

- [x] Run unit tests and debug/application-test builds using normal incremental
  Gradle execution. Prepare pinned native sources with `tools/prepare_native.py`
  if absent. Use the existing local sherpa AAR in this worktree.
- [x] Install on the explicit emulator and run only relevant instrumentation
  cases, restoring settings afterward; inspect selector and readiness states.
- [x] Review the diff for unsafe reset calls, stale timer races, leaks and
  accidental service shutdown. Run `git diff --check`. Leave changes in the
  isolated feature worktree for review; commits were not requested.

## Integration findings

- The existing Settings resume/toggle checks required a resident recognizer.
  A new device test reproduced the resulting floating-microphone disablement.
  These checks now require installed model files, preserving on-demand loading.
- Another concurrent APK installation killed the first full device run. Added
  `tools/qa/isolated_app.gradle` for a unique QA package without modifying the
  production application identity, then completed device tests under `idleqa`.
