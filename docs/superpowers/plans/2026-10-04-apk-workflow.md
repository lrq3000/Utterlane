# APK Workflow Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan inline.

**Goal:** Build a downloadable debug APK for every push, pull request, and published release; also attach the APK to published releases.

**Architecture:** Adapt PolyCalX's build workflow to TranSlander's Java 21 and native dependencies. A read-only build job produces an artifact; a separate release-only job downloads that artifact and uploads it to the triggering release with write permission. Use the latest stable action versions verified through GitHub's releases API.

**Tech Stack:** GitHub Actions, Ubuntu, Java 21, Android SDK/NDK, Python, Gradle, GitHub CLI.

## Approved design

- Build debug APKs, matching the reference workflow. Release attachments remain explicitly named as debug builds.
- Trigger on unrestricted `push` and `pull_request`, plus `release: published` (including prereleases). Allow manual runs with `workflow_dispatch`.
- Install SDK platforms 34 (sherpa-onnx) and 35 (app), build tools, NDK `28.2.13676358`, and CMake `3.22.1`.
- Reuse `python tools/prepare_native.py` and `bash build-sherpa-onnx-aar.sh`; cache the generated AAR with an exact key tied to its build inputs. Cache Gradle dependencies through setup-java.
- Fail when the APK is missing. Attach `TranSlander-debug.apk` using the release tag passed through an environment variable, with replacement enabled for retries.
- Build jobs have `contents: read`; only the release-upload job receives `contents: write`.

## Implementation and validation

- [x] Add `.github/workflows/build_apk.yml` using checkout `v7.0.1`, setup-java `v6.0.1`, cache `v6.1.0`, upload-artifact `v7.0.1`, and download-artifact `v8.0.1` (verified via `gh api repos/actions/<name>/releases/latest`).
- [x] Configure native source preparation, the missing AAR build, and `./gradlew assembleDebug --console=plain` before artifact upload.
- [x] Add the dependent release-only upload job using `gh release upload` and the workflow token.
- [x] Run `go run github.com/rhysd/actionlint/cmd/actionlint@latest -shellcheck= -pyflakes= .github/workflows/build_apk.yml` and `git diff --check`; inspect permissions, artifact paths, cache inputs, and event conditions.
- [x] Record local validation separately from a real GitHub-hosted build. Do not commit, push, or publish a release without a user request.

## Verification boundary

The workflow's Ubuntu native dependency build cannot be verified by YAML lint alone. End-to-end validation requires a GitHub-hosted run after the branch is published; this task does not authorize publishing the branch.

Local validation: actionlint v1.7.12 passed with no diagnostics. ShellCheck and Pyflakes integration were disabled; no Android build or GitHub Actions run was performed.
