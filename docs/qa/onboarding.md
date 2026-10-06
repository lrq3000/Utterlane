# Native onboarding verification

Date: 2026-10-06. Refreshed branch/worktree: `integrate/onboarding-latest`,
`.worktrees/onboarding-latest`. Integration base: `a3f8e90`, freshly fetched
`origin/main`. The original implementation and its evidence are preserved below.

## Latest-main replay

The source was preserved as `9cdf9d0` (DNS cancellation), `0cffc43` (pre-29
FileObserver), and `c6067b3` (native guide), based on `1b1c12e`. Replay started
from main rather than replacing main's newer files with the old feature tree.

### Hunk checklist and adaptations

| Original change | Outcome |
| --- | --- |
| `CHANGELOG.md`: unreleased section | Applied |
| `README.md`: first-dictation introduction | Applied |
| Manifest: internal onboarding activity | Applied |
| `ModelManager`: async imports | Applied with adaptation: retain upstream runtime-option and response imports too. |
| `ModelManager`: download/header acquisition | Applied with adaptation: await headers asynchronously inside upstream's `openDownload`; retain its owner cancellation hook through body reads, per-transfer timeout snapshot, and cleanup guarantees. |
| `ModelManager`: suspending transfer opener | Not applicable: main already includes the same signature. |
| `SettingsActivity`: lifecycle import | Applied |
| `SettingsActivity`: onboarding imports | Applied |
| `SettingsActivity`: launcher gate | Applied |
| `SettingsActivity`: replay action | Applied |
| `SettingsRepository`: configured-installation signal | Applied with adaptation: read the injected `dataStore`, preserving the constructor and atomic advanced-option transactions. |
| `AudioMonitorService`: observer constructor | Applied |
| `docs/development.md`: module explanation | Applied |
| `docs/development.md`: catalog-default distinction | Applied |
| `docs/qa/README.md`: onboarding link | Applied |
| `docs/user-guide.md`: setup section | Applied with adaptation: explain main's no-auxiliary-model fixed-one speaker mode. |
| `docs/user-guide.md`: model table | Applied |
| All 44 added source/resource/test/design/documentation/tool files | Applied; the small compatibility adaptations below supplement these additions. |

Fixed-one speaker replay now bypasses the model-download page in forward/back
navigation and restoration, changes its action/copy, and enables labels without
requiring auxiliary weights. Auto/multiple counts retain the original route.
The microphone adapter still delegates to `MicrophoneSessionFactory`; runtime
snapshots, progress-aware watchdogs, diagnostics consent, worker finalization,
native speaker processing, and opt-in statistics remain upstream-owned.

### Refreshed verification

- **257 JVM tests passed**, zero failures/skips: 201 ASR, 15 diagnostics,
  8 history, 17 onboarding, 16 settings. The stalled-DNS regression failed on
  main's synchronous header acquisition, then passed with asynchronous headers
  while main's body-read/publication/hook-disposal tests continued to pass.
  The fixed-one route regression also failed with the original routing policy.
- QA app/test APKs and the normal-identity debug APK assembled. The initial
  low-output build tool call was interrupted after artifacts/reports were
  produced; a normal incremental assembly confirmed success without cleaning or
  forcing tasks.
- **19 distinct Android tests passed** on API-28 LDPlayer instance 0. The first
  batch exposed a non-void Kotlin coroutine test and a portrait-only test lookup.
  Explicit `Unit` return and accessibility-container scrolling corrected these
  test issues. Only the affected controller class (5) and voice trial (1) were
  rerun; those 6 passed, complementing the 13 earlier successful checks.
- Both real Redux trials passed against the refreshed worker, including the
  share receiver. Controller coverage includes fixed-one enable/forward/back
  without any model transfer. No private acoustic recordings were needed.
- Offline bundled-audio digest and staged/unstaged whitespace checks passed.
  A separate read-only integration review found no merge-blocking issues.
- Device display remained 1920×1080, font scale 1.0; the QA app was stopped.

Commands were the incremental commands below, the focused JVM filters
`*DownloadCancellationTest`, `*RuntimeOptionsPersistenceTest`, `*OnboardingFlowTest`,
and one six-class `am instrument -w -e class` batch plus its six-check follow-up.
The final normal build used `'-PqaApplicationIdSuffix=' assembleDebug`.

Refreshed normal APK: `app/build/outputs/apk/debug/app-debug.apk`, application ID
`io.github.lrq3000.utterlane`, version 2.0.0 / code 11, **71,438,905 bytes**.
SHA-256: `73cb6d0807f720eb011b482abd6e3e17717c6e44c31b08d47eae4bdb86b22a54`.

## Original baseline evidence

The following records describe `feat/onboarding-wizard` in
`.worktrees/onboarding-wizard`, based on `1b1c12e`, before the latest-main replay.

## Implementation

The approved revision-03 design is implemented with native Compose, the existing
Blue harmony palette and source-derived wordmark. All pages expose appearance
selection. Content/ordering, navigation policy, app integration, Android handoffs,
trial capture and artwork are separate units in `onboarding/`. No runtime
framework or dependency was added.

Setup is resumable, optional features remain opt-in, and Settings offers replay.
Recommendations use inclusive 1/2 GiB boundaries. Replay retains explicit model
and speaker-count choices. Both trial pages are skippable. The summary uses
full-width vertically stacked cells.

## Automated checks

Final results: **80 JVM tests passed** (zero failures/skips), **18 Android
instrumentation tests passed** in the six classes below, and both debug app/test
APKs assembled successfully. The normal, unsuffixed debug APK was then rebuilt
and inspected: application ID `io.github.lrq3000.utterlane`, min SDK 26, target
SDK 36, version 2.0.0 / code 11 (unreleased feature; no version bump).

Final normal debug APK SHA-256:
`16363dee8cd056e7734eb25f70c38b022ffb592dd23887efdfb5c36eb00b018c`.

Normal incremental commands, with Java 21 and the existing Android SDK/NDK:

```text
gradlew.bat --offline --console=plain --quiet -PqaApplicationIdSuffix=.onboarding testDebugUnitTest assembleDebug assembleDebugAndroidTest
python tools/fetch_onboarding_sample.py --check
git diff --check
```

PowerShell callers should quote `'-PqaApplicationIdSuffix=.onboarding'`.
No clean/forced rebuild flags were used. Native source preparation used the
existing `tools/build_sherpa.py` and `tools/prepare_native.py` tools. Low-output
build logs are retained locally in `.native-cache/onboarding-*.log`.

The unit suite covers RAM boundaries, unknown RAM, stable-ID navigation,
conditional pages, initialization policy, permission prerequisites, bounded trial
text, cancellation/late callbacks and validated filesystem paths.

Android tests:

| Class | Coverage |
| --- | --- |
| `OnboardingAndroidTest` | Progress persistence, completion/replay, every page's appearance control, sample digest/URI/receiver |
| `OnboardingControllerAndroidTest` | Backgrounding during preparation, returned picker results during initialization, retry, early theme/model-selection race |
| `OnboardingFolderAndroidTest` | Stale-folder removal, empty-set behavior, failed edits preserving enabled monitoring |
| `ModelDownloadCancellationAndroidTest` | Stalled-DNS cancellation and corrupt-body/retry publication |
| `OnboardingRecognitionAndroidTest` | Actual in-app text insertion, actual share receiver output, explicit speaker-count preservation |
| `FileObserverAndroidCompatibilityTest` | Actual folder-observer construction/start on pre-29 Android |

Run these explicitly with `am instrument -w -e class` and the test package
`io.github.lrq3000.utterlane.onboarding.test/androidx.test.runner.AndroidJUnitRunner`.
The recognition class requires a verified speech model and the speaker model
installed in this QA app. It substitutes only microphone PCM capture with the
bundled public-domain recording; model loading, isolated-worker IPC, inference,
text delivery and the actual UI remain real. No personal microphone audio is
needed for those checks.

## Device and visible flows

Primary target: LDPlayer instance 0, `emulator-5554`, Android 9 / API 28 with ARM64
translation. The QA application suffix prevents replacing the normal app.
Initial checks used instance 1 (`emulator-5556`); the operator authorized moving
to instance 0 after another session's tests were found on the shared target.

Observed on the real app:

- Fresh launcher → Welcome; incomplete setup resumed on restart.
- Device-appropriate recommendation, explicit Redux selection, cancellable
  download, verified local model import and readiness.
- Optional speaker model imported and enabled through its dedicated step.
- Microphone denial, permanent denial from shortcut setup, app-settings recovery
  and return to the same guide.
- Local Downloads subfolder chosen through Android's document picker, audio
  permission granted, monitoring enabled and later disabled.
- A new public-domain WAV in the selected folder triggered an actual
  transcription. Logcat recorded observer start, detection and launch; the UI
  showed the recognized reading.
- In-app dictation field and shared-audio receiver both displayed real Redux
  recognition of the nine-second reading.
- Native Android share sheet included Utterlane. Multiple QA variants on the
  emulator have the same label; automated receiver checks target this QA package.
- Completion → Settings → Setup guide returned to Welcome without resetting
  settings or completed state.
- Light/dark portrait views and the full-width summary at 320 dp width and 200%
  font scale. Changing display/font configuration retained progress and theme.

Screenshots and UI trees are local run artifacts in `qa-artifacts/`, including
`onboarding-welcome-light`, `onboarding-replayed-welcome`,
`onboarding-summary-rows-dark`, `onboarding-summary-model-large`,
`onboarding-folder-confirmed`, `onboarding-folder-auto-transcript`, and
`onboarding-denied-shortcut-recovery`. Recognition tests also save result images
under the QA app's external `files/onboarding-qa/` directory.

## Evidence-driven corrections

- Synchronous OkHttp header acquisition held cancellation behind a stalled DNS
  resolver. A failing 1.5-second cancellation regression reproduced it; cancellable
  async acquisition releases staging and the transfer mutex promptly.
- Controller regressions reproduced late capture after backgrounding, discarded
  restored picker results, and early appearance writes changing the initial model
  selection. Those cases were corrected without duplicating the ASR pipeline.
- Folder additions are validated before publishing; failed changes retain the
  old configuration. Pure removals remain possible even with multiple unavailable
  entries, and only an already-running watcher is refreshed. Removing the last
  folder disables the Downloads fallback.
- Actual Downloads-provider raw tree IDs are accepted only inside the known
  Downloads root. Unknown/cloud providers are rejected with an actionable route;
  a direct Use Downloads action is also available.
- Enabling labels no longer overwrites an explicitly selected speaker count.
- On API 28, the existing `FileObserver(File, int)` call crashed with
  `NoSuchMethodError`. The equivalent string-path constructor supports API 26+;
  the new regression failed before the change and passed afterward.

## Sample choice and rights

The initial five-second Apollo radio sample exposed obvious recognition errors
with the compact model. The final bundled sample is a clearer nine-second
LibriVox reading of *Alice's Adventures in Wonderland*, read by Kristen McQuillin,
from Wikimedia Commons. Its source uses the LibriVox public-domain dedication;
the original text is also public domain. Source and excerpt hashes, the 00:48–00:57
range, processing command and credits are in
`app/src/main/assets/onboarding/ATTRIBUTION.txt`.

## Limits and harness observations

- The emulator's DNS stalled live external downloads. Actual model installation
  was verified through local import; asynchronous HTTP success/checksum failure/
  retry and cancellation were separately tested with deterministic responses.
- Device evidence is API 28. Android 13+ notification/media permission dialogs
  and newer OEM storage/foreground restrictions still need device coverage.
- New guide copy uses English fallback in Android string resources, following the
  project's batch-translation-before-release workflow.
- API-28 accessibility cached the old progress tree after the transcription was
  visibly complete. Screenshots and idle recognizer state proved completion;
  the test driver now refreshes virtual nodes. Run only one UiAutomation client
  per device; a simultaneous `uiautomator dump` is not a valid independent probe.
- One Windows Kotlin-daemon temporary-directory cleanup failed; its fallback
  compilation succeeded. The quieter build wrapper retains the complete log.
- Streaming ADB install stalled once; subsequent installs used `--no-streaming`
  without restarting the shared ADB server. The dedicated target's font scale
  and display-size override were restored after validation.
- With explicit operator approval, the early shared-target display override was
  also reset. Both emulators reported their original 1920×1080 size afterward;
  the onboarding QA app was stopped without stopping other app/test packages.
