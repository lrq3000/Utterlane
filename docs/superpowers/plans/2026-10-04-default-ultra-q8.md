# Ultra Q8 first-launch default implementation plan

**Goal:** Select Moondream Parakeet Ultra Q8_0 when no model preference is saved.

**Approved design:** Use the catalog default for both the initial model-manager
state and the settings fallback. Retain explicit saved choices and stable model
storage paths. NVIDIA Parakeet v3 keeps `parakeet-v3/`; GGUF models keep
`models/<model-id>/`. Share that path rule between the manager and worker.

**Tech stack:** Kotlin, Android DataStore, JUnit, Gradle.

## Steps

- [x] Inspect every `ModelCatalog.DEFAULT` caller and run the existing catalog tests.
- [x] Update `ModelCatalogTest` to expect `parakeet-ultra-q8_0` as the default;
  run `gradlew.bat --offline --console=plain --quiet testDebugUnitTest --tests '*ModelCatalogTest'`
  and confirm the old default fails the assertion.
- [x] Name the ONNX entry `PARAKEET_V3`, define `DEFAULT` with the existing Ultra
  Q8 artifact, and retain all five catalog entries and O(1) ID lookup.
- [x] Add `ModelDefinition.relativeDirectory` and use it in `ModelManager`
  and `RecognitionWorkerService` so changing defaults never relocates models.
- [x] Use `ModelCatalog.DEFAULT.id` in `SettingsRepository` only when its
  stored model preference is absent. Pin ONNX fixture tests and switch-away
  recovery tests to `ModelCatalog.PARAKEET_V3` where that is their actual intent.
- [x] Cover explicit selections and both legacy/GGUF storage paths in catalog
  tests. Run JVM tests, `assembleDebug`, `compileDebugAndroidTestKotlin`, and
  `git diff --check`; inspect results and the final diff.

Changes remain in `.worktrees/default-ultra-q8`, branch `feat/default-ultra-q8`.

## Verification

- The baseline's three catalog tests passed. The updated default assertion then
  failed with expected `parakeet-ultra-q8_0`, actual `parakeet-v3` before implementation.
- `gradlew.bat --offline --console=plain --quiet testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin`
  succeeded: 31 JVM tests, zero failures/errors/skips; debug APK built and Android
  instrumentation test sources compiled. Device tests were not run.
- `git diff --check` passed and all production default references were reviewed.
- Build prerequisites (the existing sherpa AAR and native source cache) were
  copied into this isolated worktree from the local rebrand worktree.
