# Store preparation main-first integration

Release preparation: `236d59983409dd40e27e2e58e719763b02157690`.
Latest main at integration start: `0779bf0`.
Merge base: `cad85a131edbd7387baba011a8825f9c37df9504`.
Integration worktree: `.worktrees/store-release-latest`.
Integration branch: `integrate/store-release-latest`.

## Method and preservation

Created a fresh integration branch from the published release-preparation commit
and merged `origin/main` into it. This leaves the published branch/history intact.
The final integration into main is a squash commit, so main remains linear.

Conflicts occurred only in `app/build.gradle.kts` and `build.gradle.kts`. For each,
saved `git diff $(git merge-base HEAD origin/main)..HEAD -- <file>` in ignored
`qa-artifacts/`, checked out `--theirs` (main), and reapplied the release intent
hunk-by-hunk. Main's source notices, QA application-ID suffix, native module,
language picker, model idle settings and recognition changes are preserved.

## Strict original-hunk checklist

| File / original hunk | Outcome |
| --- | --- |
| `app/build.gradle.kts` `@@ -12,7 +12,7` | Applied: missing-AAR guidance points to the Python builder. |
| `app/build.gradle.kts` `@@ -25,14 +25,14` | Applied: compile/target SDK 36, version 2.0.0 / 11. |
| `app/build.gradle.kts` `@@ -45,8 +45,26` | Applied with adaptation: restored optional release signing around main's new debug QA-suffix block, retaining both. |
| `app/build.gradle.kts` `@@ -83,7 +101,7` | Applied: dependency provenance comment retained alongside main's transcribe-native dependency. |
| `build.gradle.kts` `@@ -1,5 +1,5` | Applied with adaptation: update both application and newly added library AGP plugins to 8.10.1 to avoid mixed plugin versions on the same classpath. |

The original release hunks in all other files merged automatically. Comparison
against `origin/main` confirmed that SettingsActivity differs only in the original
release disclosure replacement and privacy-link addition; new main UI and
recognition code remain intact.

## Packaging adaptations

- Both AGP plugins use 8.10.1; CI/F-Droid SDK installation retains platform 35 for
  main's unmodified transcribe-native module alongside platform 36 for app/sherpa.
- F-Droid source staging now includes transcribe.cpp, which main's preparer fetches.
- Reproduced a CMake configuration failure in the scanned source copy: the old
  pruning removed `crispasr/examples`, but main's generic runtime requires that
  directory even when examples are disabled. A new regression test first failed
  on removal of `examples/CMakeLists.txt`. Pruning now targets only unused example
  subdirectories, preserving common helpers and the vendored talk-llama core.
- Updated release notes/build instructions for the additional native module and
  main's new release features. Historical preparation evidence remains unchanged.
- Adapted main's existing `android.yml` too: SDK/CMake installation now precedes
  the Python AAR builder; both jobs use the input-hashed AAR cache instead of a
  stale version-only key. Tag publishing remains enabled and shares the release
  environment, signing helper, filenames and credential contract with
  `build_apk.yml`. The helper retains main's alpha/beta/prerelease-tag support;
  a new test covers accepted tags and rejected branch/path/shell syntax.

## Verification

- `gradlew.bat --console=plain --quiet testDebugUnitTest assembleDebug
  assembleRelease bundleRelease assembleDebugAndroidTest`: passed; **63 JVM tests**,
  zero failures/errors/skips, and successful debug/release/AAB/instrumentation builds.
- `python -m unittest discover -s tools/tests`: **12 passed**, including the new
  source-pruning regression (observed fail before fix and pass afterward).
- Store metadata/manifest checks passed: version 2.0.0 / 11, target SDK 36,
  min SDK 26, ARM64, non-debug/non-test release. **Five** packaged native libraries
  pass 16 KB ELF alignment checks in both APK and AAB.
- Real fdroidserver source scan and metadata lint passed using the current
  category taxonomy. Scanner warnings concern upstream audio/SentencePiece test
  data; Windows fdroidserver still warns that its apksigner discovery is unavailable.
- Built `assembleRelease` successfully from the **pruned/scanned combined source
  copy** `.native-cache/fdroid-integration-2/source`, using installed Gradle 8.12.1
  because the scanner removes wrappers. Reused the already-verified unchanged
  sherpa AAR; rebuilt the combined CrispASR/transcribe.cpp app libraries. This is
  a Windows rehearsal, not an official Linux `fdroid build`.
- Built debug/test APKs with `-PqaApplicationIdSuffix=.storeqa` and installed to
  LDPlayer `emulator-5556`, keeping existing application packages separate.
  **Four Android tests passed in 108.784 seconds**:
  - custom model import persistence and inference with speaker labels Off/Auto;
  - original ONNX streaming labels before recording finishes;
  - native ternary plus diarization with Immediate idle unload;
  - app-language override and system fallback.
- Verified **69 main-changed files outside the original release overlap** match
  latest main exactly. The sole additional overlap is `android.yml`, adapted as
  described above to preserve main's publishing with the new native builder.
- Workflow Actionlint and APK ZIP 16 KB alignment passed. Bundletool reports
  `PAGE_ALIGNMENT_16K` for the combined release AAB.

The original publication gates remain: Linux F-Droid buildserver, modern Android
and real 16 KB runtime testing, production signing/hosted publishing, and store
account declarations. The new integration does not claim those unperformed checks.
