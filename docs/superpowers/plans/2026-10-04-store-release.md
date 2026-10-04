# Store Release Implementation Plan

> **For agentic workers:** Execute inline using the executing-plans skill. Keep all edits and build artifacts in this session's worktree.

**Goal:** Prepare Utterlane 2.0.0 for F-Droid and free Google Play distribution.

**Architecture:** Share one application identity and one release codebase. Keep
source preparation, native compilation, artifact validation and submission
metadata separate. Signing is optional for local/F-Droid builds and mandatory for
published GitHub artifacts.

**Tech Stack:** Kotlin/Compose, Gradle/AGP, Android NDK/CMake, Python tooling,
Fastlane metadata, GitHub Actions, F-Droid YAML, ADB/LDPlayer.

## 1. Establish baseline and prepare native dependencies

- [x] Run baseline JVM tests using the existing AAR and pinned CrispASR sources.
- [x] Add a portable `tools/build_sherpa.py` source builder; validate pinned Maven
  ONNX Runtime content and native ELF alignment before packaging the AAR.
- [x] Add focused Python checks for malformed/nonaligned ELF inputs and checksum
  rejection. Run `python -m unittest discover -s tools/tests`.
- [x] Retain the old Bash entry point as a compatibility wrapper to the Python tool.

## 2. Release configuration

- [x] Set `versionName = "2.0.0"`, `versionCode = 11`, `compileSdk = 36`,
  `targetSdk = 36` in `app/build.gradle.kts`; select a supported AGP for API 36.
- [x] Add optional environment-based release signing that rejects incomplete
  configurations and leaves F-Droid builds unsigned when none is provided.
- [x] Build with `gradlew.bat --console=plain --quiet testDebugUnitTest assembleDebug
  assembleRelease bundleRelease assembleDebugAndroidTest`.
- [x] Update `.github/workflows/build_apk.yml` to build/test APK and AAB artifacts,
  check their release properties and publish only a signed release APK.

## 3. Metadata and documentation

- [x] Update `CHANGELOG.md` and `fastlane/metadata/android/en-US/changelogs/11.txt`.
- [x] Add tracked release notes and the local `github-release.md` convenience copy.
- [x] Update `README.md` buttons, install instructions, build requirements and
  screenshot descriptions. Keep not-yet-published store channels marked pending.
- [x] Add `docs/distribution/` instructions and F-Droid metadata tooling accepting
  a full release SHA, with pinned source revisions and no binary scanner bypasses.
- [x] Include Play reviewer setup, privacy/data-safety decision guidance,
  Accessibility and foreground-service declaration text and video scripts.

## 4. Visual and runtime evidence

- [x] Build/install to explicit LDPlayer serial `emulator-5554`.
- [x] Capture English portrait Settings and a real successful file transcription
  into the existing Fastlane screenshot paths. Restore locale and display size.
- [x] Verify sherpa and CrispASR inference after dependency/toolchain changes.
- [x] Validate store text lengths and image dimensions, APK/AAB identity/version,
  non-debug release flags, ELF alignment and APK ZIP alignment.
- [x] Run available F-Droid metadata lint/scan; report full build/runtime gaps.
- [x] Review `git diff --check` and record evidence and remaining publication steps.
