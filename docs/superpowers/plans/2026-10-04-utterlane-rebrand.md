# Utterlane rebrand implementation plan

**Goal:** Fully rebrand the application and its source as Utterlane using the
approved artwork, separate Android identity and maintainer attribution.

**Architecture:** Rename first-party packages, component references and JNI
symbols as one coherent change. Generate artwork from the archived design,
preserve historical attribution, and verify the packaged app and live UI.

**Tech stack:** Kotlin, Android/Compose, C++/JNI, Gradle, Python/Pillow.

## Approved tasks

- [x] Preserve the original image and brand specification in the isolated
  `feat/utterlane-rebrand` worktree; commit the design separately.
- [x] Move `app/src/{main,test,androidTest}/java/com/translander` to
  `app/src/{main,test,androidTest}/java/io/github/lrq3000/utterlane` and change
  declarations/imports, application class, theme, manifests and ProGuard rules.
- [x] Set application ID and namespace to `io.github.lrq3000.utterlane`, root
  project to `Utterlane`, library to `utterlane_crisp`, and exported JNI names
  to `Java_io_github_lrq3000_utterlane_asr_CrispParakeetBackend_*`.
- [x] Update localized branding, action names, notification labels and current
  test commands. Commit the source rename with the breaking identity documented.
- [x] Add a reproducible Python/Pillow asset generator, README banner, store
  icon/feature graphic, adaptive foreground/background and monochrome mark.
  Inspect generated artwork and commit it with the generator.
- [x] Revamp README: banner, concise pitch, install/quick start, capabilities,
  screenshots, honest streaming/model constraints, privacy, build/test commands,
  contributing, license and bottom-of-page lineage/maintainer section.
- [x] Add Stephen Karl Larroque <LRQ3000@GMAIL.COM> to license attribution and
  document human sanity checking of AI contributions. Preserve upstream notices.
- [x] Update privacy policy, project context, Fastlane metadata, CI artifact
  names and unreleased changelog; keep authentic historical links/evidence.
- [x] Run `gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest`
  incrementally. Check release build and F-Droid feasibility independently.
- [x] Inspect APK manifest/resources/native exports; launch on an available
  Android target, check new branding and entry points, exercise native loading,
  and replace store screenshots with genuine captures.
- [x] Audit old names and paths, review `git diff --check`, record verification
  evidence and limitations, and finish with focused conventional commits.

## Commit convention

Each commit explains its motivation and includes:
`Harness: OpenCode; model: OpenAI gpt-6-astra.`
No automatic push, repository rename, merge or release is part of this plan.
