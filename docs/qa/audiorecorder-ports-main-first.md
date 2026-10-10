# AudioRecorder ports: main-first rebase

Date: 2026-10-10. Branch: `feat/audiorecorder-ports`.

Subsequent update before squash/publication:
[Explicit capture choices](explicit-capture-selection.md). Its newer donor fixes,
verification and artifact supersede this report's earlier final-state counts/hash.

## Baselines and method

- Original feature tip: `3a6c37acac5beff63fbeb8b10ae10b7f204f42e2`.
- Preserved local ref: `backup/audiorecorder-ports-before-main-first-20261010`.
- Original common base: `9dc33a80dff38ed675fdaefabce0af9bf99a76bd`.
- Fetched main: `f6975ce9dbc1565325ab461e2ec680a2e7b12018`; local main and
  `origin/main` matched before and after the operation.
- Replayed tip: `00013fb7e12d495086c3d78175dc5325daab66c1`.

Read current Core Values and main's Home, progress/ETA and retention-aware exit
contracts before replaying all **25 commits**, including the unmerged Bluetooth
lifecycle hardening on which the port depends. At each conflict, reset the whole
conflicted file to the main-based **ours** side of the rebase, then reapply that
feature commit's intents. Earlier adapted feature intents on that side remain.
Do not restore obsolete Settings-owned service restoration or dialog/history APIs.

The original feature patch is reconstructible with:

```text
git diff --binary 9dc33a80dff38ed675fdaefabce0af9bf99a76bd..3a6c37acac5beff63fbeb8b10ae10b7f204f42e2
git range-diff 9dc33a8..backup/audiorecorder-ports-before-main-first-20261010 f6975ce..00013fb7
```

Range-diff confirms **20 equal patches, 5 adapted patches, no dropped commits**.
This is a local feature rebase: it does not squash into main or publish rewritten
history. The root checkout's four unrelated audio/transcript fixtures were preserved.

## Strict original-hunk checklist

Anchors identify the original feature patch's old-line hunk starts. These **19
hunks** account for every feature hunk in each conflicted file, including clean
hunks that had to be reapplied after resetting that file to the main-based version.
Paths below abbreviate the package `io/github/lrq3000/utterlane`.

| Original commit / file / hunk | Disposition | Main-first resolution |
| --- | --- | --- |
| `b181e0dd` / SettingsActivity / 207 | Adapted | Keep `AppEntryServices.restore`, now shared by Home and Settings. Remove model availability gating in that helper; preserve permission checks, both model-status refreshes, cancellation/error handling, audio monitor and boot-reminder logic. |
| `b181e0dd` / SettingsActivity / 479 | Applied | Model unloading does not stop the independent floating recorder. |
| `b181e0dd` / SettingsActivity / 653 | Applied | Floating enablement depends on mic/overlay permissions, not installed ASR weights. Preserve main's surrounding Home navigation and wizard replay UI. |
| `bbde05d5` / TranscriptionDialogModel / 9 | Applied | Import storage failure classification. |
| `bbde05d5` / TranscriptionDialogModel / 441 | Adapted | Preserve main's cancellation propagation and action toast. Compute the friendly error once and use it for both message state and toast. |
| `0e2059ff` / RecordingHistory / 5 | Adapted | Import/append nullable microphone options after main's speaker-label and recovered-origin fields; existing positional parameters stay compatible. |
| `0e2059ff` / RecordingHistory / 65 | Adapted | Read microphone properties alongside main's labels/recovery evidence; extend `begin` after `keepUntilDismissed`, rather than replacing it. Old/imported entries still have no implicit gain. |
| `0e2059ff` / RecordingHistory / 80 | Adapted | Add microphone options to the new entry while retaining main's exact temporary-retention condition and `Recording(..., keepUntilDismissed)`. |
| `0e2059ff` / RecordingHistory / 299 | Applied | Save microphone properties alongside labels and recovered origin, preserving original PCM and all retention/discard/lease rules. |
| `0e2059ff` / TranscriptionDialogModel / 175 | Adapted | Construct per-source replay gain without replacing main's exact/provisional completed-audio progress accounting. |
| `0e2059ff` / TranscriptionDialogModel / 199 | Adapted | Apply gain to accepted derived PCM. Keep progress publication, recovered provenance, actual-label metadata and main's decoder call; do not restore decoded-byte progress. |
| `0e2059ff` / TranscriptionDialogModel / 211 | Adapted | Drain gain before `finish`, retaining its speaker-finalization observer, saving phase, metadata save and transcript history-owner registration. |
| `d44e3c1c` / MicrophoneSession / 57 | Applied | Pause app-owned playback after admission, independently of capture success. |
| `d44e3c1c` / MicrophoneSession / 66 | Applied | Freeze processing options, configure the recorder and create the bounded gain processor. |
| `d44e3c1c` / MicrophoneSession / 80 | Adapted | Pass **both** `keepUntilDismissed = keepResultAudio` and `microphone = microphoneOptions` by name. Preserve Home's current-result ownership. |
| `d44e3c1c` / MicrophoneSession / 105 | Applied | Deliver derived gain and drain its tail before finish, retaining main's labeled-result metadata/publication. |
| `d44e3c1c` / MicrophoneSession / 128 | Applied | Use actionable storage messages for processing/storage failures without changing capture/recovery ownership. |
| `d44e3c1c` / MicrophoneSession / 158 | Applied | Preserve finalization warnings and main's current metadata handling while classifying storage failure. |
| `08a3d9ec` / OnboardingTestUi / 48 | Adapted | Combine main's `windowId` parameter/filter with optional `scroll = false`; retain scoped navigation callers and add accessible switch/popup helpers and failure diagnostics. |

### Automatic and semantic preservation

- Manifest changes add Bluetooth permissions/hardware capability alongside main's
  Home launcher/service. `UtterlaneApp` retains Home ownership and initializes local
  microphone diagnostics alongside the existing input controller.
- Remaining Settings changes retain current entry/navigation and wizard labels.
  The size selector remains removed; direct pinch resizing is retained. No new
  Settings access restriction or recording-panel visibility behavior was added.
- The guide retains main's Home entry points, independent history metadata and
  retention-aware exit description alongside new processing/pinch/playback guidance.
- At replay completion, a path-by-path comparison found **121 main-only files
  unchanged**; the sole adapted main-only file was `AppEntryServices`. Similarly,
  **83 feature-only files were unchanged**; `FloatingAvailabilityTest` was adapted
  to exercise the relocated policy. These counts precede the validation-only test
  enhancements described next.
- Independent read-only integration review checked the range-diff, overlapping
  owners, automatic merges, history constructor compatibility and progress/gain
  ordering, and found no actionable issue.

## Verification

- The adapted availability regression first failed with main's installed-model gate
  intact. After moving the feature intent into the shared helper, all three cases
  passed, including permission-denied behavior.
- Added a real-filesystem combined regression: Home retains current audio under
  Immediate retention; gain settings, exact original PCM, committed speaker labels
  and recovered origin survive retry/history handoff and repository restart.
- Strengthened import/export ENOSPC tests to require the same friendly message in
  both dialog state and an actual Robolectric Toast.
- Allowed the existing Home workspace/detail tests to run on the isolated
  `.audiorecorderports` identity. Their behavioral assertions remain unchanged.
- Full combined JVM suite: **841 passed, zero failures/skips**. Debug application
  and instrumentation APK builds passed with the usual incremental offline command:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest "-PqaApplicationIdSuffix=.audiorecorderports" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

- **31 distinct API 34 instrumentation cases have passing evidence** on the owned
  `emulator-5584` target (3 GB RAM, two cores, file-backed quickboot RAM disabled):
  - one separately invoked Nearby Devices denial/settings-return case;
  - four `CapturePanelAndroidTest`, three `AudioInputAndroidTest`, seven
    `AudioPlaybackAndroidTest`, one `FloatingControlsAndroidTest`, and the remaining
    `MicrophoneSettingsAndroidTest` selector/diagnostic case;
  - both `HomeWorkspaceAndroidTest` cases, `HomeDetailOwnershipAndroidTest`, and all
    eleven `TranscriptionExitAndroidTest` cases.
- Exact native outcomes: permission method **1/1 passed**; combined batch
  **29/30 passed**; the remaining toast-feedback method **1/1 passed unchanged** in
  isolation. The combined timeout's event trace showed repeated queued exit-pin
  toasts while waiting for the next pin-action toast. No production notification
  behavior or assertion was weakened. This is not a claim of one green 31-test batch.
- Main's native cases verify capture across navigation/recreation, replacement and
  temporary ownership, borrowed-detail Back behavior, retention-aware exit, Pin both,
  scoped discard, save failure/retry, refreshed consent and actual toast feedback.
  Home workspace tests use deterministic injected PCM; the separate AudioInput and
  floating tests use real AudioRecord. No new physical Bluetooth evidence is claimed.

Native reproduction uses the same package/runner as
[the port QA record](bluetooth-input.md#audiorecorder-ports-and-pinch-only-resizing--2026-10-10).
Add `HomeWorkspaceAndroidTest`, `HomeDetailOwnershipAndroidTest` and
`TranscriptionExitAndroidTest` to that combined class list. The isolated toast check is:

```powershell
adb -s emulator-5584 shell am instrument -w -e class 'io.github.lrq3000.utterlane.TranscriptionExitAndroidTest#completedActionsAndExistingFailurePathsProduceToastFeedback' io.github.lrq3000.utterlane.audiorecorderports.test/androidx.test.runner.AndroidJUnitRunner
```

### Rebuilt standard artifact

```powershell
.\gradlew.bat assembleDebug "-PqaApplicationIdSuffix=" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Verified package
`io.github.lrq3000.utterlane`, **HomeActivity launcher**, version **2.1.0 / 210**,
target SDK **36**, ARM64. SHA-256:
`c4734304e735ef9d4e0072abb03b4134be2b686a37f49a70175f5a130d7699ad`.

This supersedes the earlier pre-rebase APK checksum. Physical HFP/LE transport,
acoustic input identity, OEM effect behavior and handover gaps remain outside the
emulator evidence, as recorded in the existing hardware matrix.
