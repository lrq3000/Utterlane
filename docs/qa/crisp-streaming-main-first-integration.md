# Main-first integration: custom models, speakers, app language

Feature commit: `c424a2a`. Common base: `cad85a1`. Canonical main: `b293212`.
Main adds automatic multilingual README wording (`4947b71`), native ternary Redux
(`70601d2`), and configurable idle model unloading (`b293212`).

Fetched origin, began a synchronization merge, then reset **each of the six
conflicted files to main's version** (`git checkout --theirs -- <files>`). Replayed
the original feature patch manually. The patch was preserved locally as
`app/build/main-first-feature.patch`; the equivalent reproducible command is:

```text
git diff cad85a1..c424a2a -- README.md app/build.gradle.kts app/src/main/assets/native-licenses.txt app/src/main/java/io/github/lrq3000/utterlane/asr/RecognitionWorkerService.kt app/src/main/java/io/github/lrq3000/utterlane/asr/RecognizerManager.kt app/src/main/res/values/strings.xml
```

## Strict original-hunk checklist

Hunk numbers refer to their order within each file in the command above.

| File | Hunk | Disposition | Rationale / retained intent |
| --- | --- | --- | --- |
| `README.md` | 1 | Not applicable | Main already removes the incorrect Parakeet default label and identifies Ultra Q8 as the first-launch default. |
| `README.md` | 2 | Applied with adaptation | Feature documentation follows main's new ternary explanation without displacing its model table or multilingual guidance. |
| `app/build.gradle.kts` | 1 | Applied | Copy the pinned full native runtime's license/notices assets. |
| `app/build.gradle.kts` | 2 | Applied | Attach notices generation to preBuild. |
| `app/build.gradle.kts` | 3 | Applied with adaptation | Keep main's more general `qaApplicationIdSuffix` setting and update this feature's QA command to `.crispqa`, rather than introduce a competing `isolatedQa` property. |
| `app/build.gradle.kts` | 4 | Applied | Package generated native notices in main assets. |
| `app/src/main/assets/native-licenses.txt` | 1 | Applied | Describe generic CrispASR and include complete-notices reference. |
| `app/src/main/assets/native-licenses.txt` | 2 | Applied | Add optional Nemotron weights attribution, preserving transcribe.cpp and compact Redux notices. |
| `asr/RecognitionWorkerService.kt` | 1 | Applied | Add speaker and end-session IPC operations. |
| `asr/RecognitionWorkerService.kt` | 2 | Applied | Add per-recording speaker ownership and serialized cleanup. |
| `asr/RecognitionWorkerService.kt` | 3 | Applied | Dispatch speaker inference. |
| `asr/RecognitionWorkerService.kt` | 4 | Applied with adaptation | Resolve custom manifests and dispatch them within main's exhaustive backend switch, preserving TRANSCRIBE_CPP and SHERPA paths. |
| `asr/RecognitionWorkerService.kt` | 5 | Applied | Record text-only custom backend state after successful validation. |
| `asr/RecognitionWorkerService.kt` | 6 | Applied | Preserve bounded decoding while adding speaker results and generic text. |
| `asr/RecognitionWorkerService.kt` | 7 | Applied | Close speaker sessions before backend destruction. |
| `asr/RecognizerManager.kt` | 1 | Applied | Snapshot speaker configuration and verify auxiliary weights. |
| `asr/RecognizerManager.kt` | 2 | Applied with adaptation | Preserve main's model-operation reservation and idle-timer refresh on last-session close, adding speaker cleanup before that accounting. |
| `asr/RecognizerManager.kt` | 3 | Applied | Share serialized, generation-checked session inference. |
| `asr/RecognizerManager.kt` | 4 | Applied | Exclude active sessions during auxiliary-weight deletion. |
| `app/src/main/res/values/strings.xml` | 1 | Applied | Add feature strings alongside main's ternary/idle strings. |

Totals: **20 hunks — 15 Applied, 4 Applied with adaptation, 1 Not applicable**.
No original feature hunk was dropped. Clean automatic merges preserve the
additional backend catalog, native-source preparer, application idle collector,
idle settings UI, and all tests from main.

## Integration verification

Added a real-device regression for ternary transcription with speaker labels
under Immediate idle unloading: the session must stay alive through inference,
return labels, then release its worker. Existing fake-backend idle tests now
snapshot/disable/restore the new diarization preference so test behavior does not
depend on the user's saved settings.

- `python tools/prepare_native.py`: both pinned native runtimes prepared.
- `gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
  "-PqaApplicationIdSuffix=.crispqa" --console=plain -q`: passed; **63 JVM tests**
  with zero failures and successful ARM64 debug/test APK builds.
- Dedicated LDPlayer index 1, endpoint `127.0.0.1:5557`, ADB server port 5038:
  **13 Android tests passed in 60.263 s**. These comprise the ternary + diarization
  + Immediate idle regression, custom import/Off/Auto native regression, and all
  11 tests in main's `ModelIdleAndroidTest` (including real-worker reload and
  floating-microphone Settings reopen behavior).
- `git diff --name-only --diff-filter=U`: empty after resolution.
- `git diff --cached --check`: passed.
