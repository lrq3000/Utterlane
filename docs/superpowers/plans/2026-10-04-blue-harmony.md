# Blue Harmony Implementation Plan

> Execute inline in the existing `design/brand-ui` worktree, following the approved
> revision-3 option-A specification. Integration approved as one conventional
> commit followed by a fast-forward into `main`.

**Goal:** Apply the approved blue/violet identity consistently to the Android UI.

**Architecture:** Share an immutable light/dark palette between Compose and native
views. Encapsulate header decoration and section-card styling in UI components;
keep the existing screen actions and recording pipeline intact.

**Tech stack:** Kotlin, Material 3 Compose, Android Views/XML, Python/Pillow assets.

## Tasks

- [x] Update `tools/generate_brand_assets.py` to export transparent original
  wordmarks into `app/src/main/res/drawable-nodpi/`, including a white dark variant.
  Run `python tools/generate_brand_assets.py` and inspect the exports.
- [x] Replace the starter palette in `ui/theme/Color.kt` and `Theme.kt` with
  Blue harmony roles and shared shape sizes. Disable wallpaper-derived colors.
  Align native XML colors and system-bar styling with these roles.
- [x] Add reusable `ui/BrandComponents.kt` header/card presentation. Apply it to
  `settings/SettingsActivity.kt`, retaining accessible app-name semantics and
  all existing model, permission, history, dictionary and appearance actions.
- [x] Apply the theme to transcription and history dialogs; use clean icons,
  rounded fields and cards, consistent text styles and spacing.
- [x] Use the shared palette in `ui/RecordingPanel.kt` and the floating-mic service.
  Retain stop/cancel semantics, PCM-driven waveform, and red active-recording state.
- [x] Prepare worktree-local native prerequisites, then run
  `./gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain`.
  Expected: successful compilation, passing existing unit tests, APK artifacts.
- [x] Install to the explicitly selected emulator and run
  `CapturePanelAndroidTest`. Use `tools/qa/emulator_ui.py` for snapshots and
  tree-derived taps. Verify light/dark Settings, model/dictionary/history dialogs,
  transcription and the recording panel; inspect crash logs.
- [x] Review `git diff --check` and the final diff; document verification evidence
  and the direct-ADB voice-activity focus limitation in `docs/qa/blue-harmony.md`.

## Review

All approved surfaces are covered; native and Compose colors have one runtime
source. The generated source-derived wordmark avoids new fonts or network assets.
Existing behavior tests are preferred over tests that merely repeat styling values.
