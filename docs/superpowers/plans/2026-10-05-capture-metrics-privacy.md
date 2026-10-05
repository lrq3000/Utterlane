# Capture Metrics and Privacy Implementation Plan

**Goal:** Use the approved CaptureMetrics naming throughout project code, tests,
and documentation while preserving local recording feedback and requested downloads.

**Architecture:** Rename the in-memory capture-state producer and its consumers
without changing its calculations, lifecycle, or bounded waveform. Explain local
metrics separately from remote analytics, and document the existing permissions
against their actual feature uses. No tracking-only permission was found.

**Tech Stack:** Kotlin, Android, StateFlow, JUnit 4, Gradle.

## Approved scope and implementation

- Rename `app/src/main/java/io/github/lrq3000/utterlane/asr/CaptureMetrics.kt`
  and its class, `MicrophoneSession.metrics`, and the corresponding JVM test.
  Update the IME, recording overlay, Android panel test, and QA/planning references.
- Document that capture metrics retain only in-memory display state, with no
  persistence or network reporting, in the class comment and `PRIVACY_POLICY.md`.
- Expand the privacy policy permission table to cover wake locks, foreground
  services, boot handling, and keyboard integration. Keep the model-download
  permission and explain its purpose beside the manifest declaration.
- Review the diff for naming/documentation-only changes and search all tracked
  text for the superseded terminology.

## Verification

Run `gradlew.bat :app:testDebugUnitTest --tests
io.github.lrq3000.utterlane.asr.CaptureMetricsTest --console=plain -q`, then
`gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain -q`.
Existing assertions cover waveform/silence, blocked capture, progress, and bounded
queues. If native prerequisites block Gradle, report that limitation and use the
cached Kotlin compiler/JUnit to execute these existing JVM tests independently.
Inspect the resolved runtime dependency graph and merged manifest when available.
Run `git diff --check` before handing off.

## Baseline

The initial focused Gradle run stopped at `:app:checkSherpaOnnxAar` because
`app/libs/sherpa-onnx-1.12.23.aar` is absent. Native source caches are also absent
from this fresh worktree. This is a build prerequisite failure, not a failed test.

## Verification results

- Compiled `CaptureMetrics.kt`, `BoundedAudioQueue.kt`, and
  `CaptureMetricsTest.kt` with cached Kotlin 2.0.21, JVM target 21, and the
  project's coroutines 1.7.3/JUnit 4.13.2 dependencies. Ran
  `org.junit.runner.JUnitCore io.github.lrq3000.utterlane.asr.CaptureMetricsTest`:
  **4 tests passed**. The generated test JAR is under the ignored `.gradle/` directory.
- Case-insensitive source/documentation and filename searches found no remaining
  occurrences of the superseded name.
- `gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
  --console=plain -q` succeeded. No recognizable analytics SDK appeared in the
  resolved graph; the absent native AAR's contents could not be inspected.
- `gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain -q`
  stopped at the same missing-AAR prerequisite. No APK or Android test result is
  claimed. A full build still requires `python tools/build_sherpa.py` and
  `python tools/prepare_native.py` before rerunning Gradle.
- `git diff --check` passed. The implementation retains all capture algorithms
  and existing permission declarations; the manifest change is explanatory only.
