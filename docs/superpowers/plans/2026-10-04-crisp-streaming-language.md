# Crisp streaming and language implementation plan

**Goal:** Custom CrispASR speech models, optional streaming speaker labels, and a
persistent app-language selector.

**Architecture:** Retain the isolated worker and bounded sample pipeline. Generic
CrispASR sessions handle custom ASR; separate per-recording Sortformer streams
provide speaker tracks. Locale handling is independent of recognition settings.

**Tech stack:** Kotlin/Compose, DataStore, Android locale APIs, C++ JNI,
pinned CrispASR/ggml, Gradle/JUnit/instrumentation.

## Tasks (inline execution)

- [x] Add tested speaker timeline/count/word ownership objects in `asr/`.
  Reject counts outside 0–8, keep frames bounded, never recycle emitted IDs.
- [x] Integrate generic native sessions and persistent diarizer handles in
  `app/src/main/cpp/`; link upstream library with desktop/download extras disabled.
- [x] Extend the worker protocol with recording identity, window ownership,
  final flush and session release. Route labels before append-only delivery.
- [x] Add private custom-model manifests/imports and a model-picker entry;
  retain filenames and support multiple companion files. Validate digests on load.
- [x] Add diarization download/import UI and settings, snapshot options per
  session, preserve the disabled path, and release on cancellation.
- [x] Add locale catalog/context handling and Settings selector, plus manifest
  locale configuration. Cover System and all packaged language resources.
- [x] Run focused JVM tests, then `./gradlew.bat assembleDebug --console=plain -q`;
  exercise available Android/native paths and record evidence and limitations.

Baseline: `testDebugUnitTest --console=plain -q` passed in the new worktree.
All edits/tests belong to `.worktrees/crisp-streaming-language`.

Final review fixed pending-boundary EOF flushing and explicit audio-tokenizer
registration. Evidence and remaining runtime matrix limits: `docs/qa/crisp-streaming-language.md`.
