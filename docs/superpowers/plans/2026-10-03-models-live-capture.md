# Models and live capture implementation plan

> Execute inline using executing-plans. The user approved the design and explicitly waived further validation prompts.

**Goal:** Download/select Ultra and Redux, and show truthful, immediate capture and processing feedback in a large bottom panel.

**Architecture:** Keep bounded audio sessions and replace the model-specific recognition dependency with a timestamped backend contract. Native CrispASR Parakeet code is version-pinned. Shared metrics/state and a bounded Canvas waveform drive a reusable bottom recording panel.

**Tech stack:** Kotlin, coroutines, MediaCodec/AudioRecord, native Android views/Canvas, DataStore, C++ JNI, Android NDK 28.2.13676358, CMake, pinned CrispASR/ggml, sherpa-onnx 1.12.23, JUnit/Android instrumentation.

1. Inspect pinned native headers/build targets; build the minimal Parakeet runtime with its pinned ggml dependency. Add a small JNI bridge and verify actual GGUF inference/timestamps.
2. Add tests first for model catalog/artifact integrity/defaults. Generalize ModelManager storage/download/import and Settings model controls. Add backend selection/loading with active-session exclusion.
3. Add tests first for sample-budget queues, RMS/silence states, owned-sample progress, ETA validity and stop/finalization. Capture at shorter intervals without increasing total audio memory.
4. Implement one descriptive bottom panel: real signal-driven waveform, large central stop region, cancellation, silence/blocked states, and real post-stop progress. Connect all four visible microphone integrations to the shared controller/metrics.
5. Run focused JVM/native/Android tests and assembleDebug incrementally. Validate Ultra and Redux, switching, failure recovery, panel interactions, zero-input feedback and history-off behavior. Record evidence, limits, APK and source revision.

Keep changes in this worktree; do not mutate main or publish a branch. All QA
test source stays in the repository. Reuse the prior ignored AAR only as the
version-matched local build prerequisite; native CrispASR sources remain pinned.
