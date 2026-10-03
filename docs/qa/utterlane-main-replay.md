# Utterlane replay onto updated main

Date: 2026-10-04.
Branch: `feat/utterlane-rebrand-latest`.
Worktree: `.worktrees/utterlane-rebrand-latest`.

Fetched `origin`; local `main` and `origin/main` both resolved to
`e426439a568c0966425b8a10aa8a71bf5df27314` before replay.
The two new main commits are:

- `0929920`: APK builds on pushes, pull requests and published releases.
- `e426439`: red model-deletion button, confirmation dialog, and a lock-protected
  confirmed-model identity check in the recognizer manager.

## Replay and conflict resolution

Created a fresh branch from main and cherry-picked the six original rebrand
commits, preserving the original branch and worktree:

| Original | Replayed |
| --- | --- |
| `f42ce85` | `cb42ed2` |
| `f462b13` | `af95fff` |
| `2af7710` | `e3760ce` |
| `c5cb64b` | `b6b5c3d` |
| `f66192e` | `1ebb3f4` |
| `2117480` | `09e3bce` |

The only conflict was in
`app/src/main/java/io/github/lrq3000/utterlane/settings/SettingsActivity.kt`.
Main's model-specific `remember(selectedModel.id)` confirmation state was kept;
the application reference was updated to `UtterlaneApp`.

### Original conflicting-file hunk checklist

Original line numbers refer to the source-rename commit `f462b13`:

| Hunk | Outcome |
| --- | --- |
| 1: package declaration | Applied |
| 39: imports | Applied |
| 119: activity settings repository | Applied |
| 130: Compose theme | Applied |
| 166: model status and service restart | Applied |
| 329: SettingsScreen application references | Applied with adaptation: retained main's confirmation state and comment while renaming the recognizer application reference |
| 350: transcription manager | Applied |
| 504: history settings component | Applied |

`git range-diff cc23968..2117480 e426439..09e3bce` shows five unchanged patches;
the source-rename patch differs only in context around the preserved confirmation
state. A complete-file comparison additionally verified that SettingsActivity
equals main's file after only namespace/application/theme substitutions and the
approved logo-header insertion. RecognizerManager equals main's file after only
namespace/application substitutions. Thus main's deletion logic was preserved,
not inferred merely from the absence of conflict markers.

## Integration follow-ups

- Renamed the new workflow's upload/download artifact and release attachment
  consistently to `Utterlane-debug`; permissions, triggers and build steps are
  preserved.
- Pointed the README build badge to that new workflow.
- Kept the workflow's dated implementation plan as historical evidence and added
  it to the rebrand audit's explicit history exceptions.
- Refreshed both store/README screenshots from the combined app; the model
  screenshot now shows the filled red Delete button rather than the old text link.

## Verification

- `gradlew.bat --offline --console=plain --quiet testDebugUnitTest assembleDebug
  assembleDebugAndroidTest assembleRelease`: passed (29 JVM tests, debug,
  instrumentation and minified unsigned release APKs).
- `tools/qa/check_rebrand.py` with SDK `aapt` and both APKs: passed source/link/
  artwork, application ID, labels, component, provider, intent, DEX and JNI checks.
- `go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 -shellcheck=
  -pyflakes= .github/workflows/build_apk.yml`: passed with no diagnostics;
  ShellCheck/Pyflakes were not part of this YAML check.
- Three focused Android tests passed in 140.16 seconds on LDPlayer API 28:
  both Q8 models transcribed through the native worker; capture panel behavior;
  voice IME live text insertion and drain-on-Done.
- Live UI: red Delete button opens a confirmation naming Redux Q8. Cancel
  dismisses it and the model remains available. Confirmed destructive deletion
  was not exercised in this replay check.
- Inspected the actual confirmation dialog and refreshed English portrait
  screenshots. Restored the emulator's French-only language list and original
  1920 × 1080 display size afterward.
- `git merge-base --is-ancestor e426439 HEAD` and `git diff --check`: passed.

No hosted Actions run, F-Droid build, push or main-branch merge was performed.
The original rebrand report remains a record of its earlier validation; this
report covers the updated-main integration.
