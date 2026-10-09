# Blue Notebook Home verification

Date: 2026-10-09. Native implementation: `feat/blue-notebook-home` in
`.worktrees/d-home`, through `c9d7415`. Approved design and ownership requirements
are in the [implementation plan](../superpowers/plans/2026-10-08-blue-notebook-home.md).
The [integration record](home-latest-main-integration.md) records each conflict
hunk, reproduced races and the preserved latest-main contracts.

## Environment and deliverable

- JDK 21, SDK/target 36, NDK 28.2.13676358; incremental offline Gradle with two
  workers and in-process Kotlin. Existing prepared native source/AAR inputs were
  reused without modifying another worktree's source cache.
- API 28 LDPlayer `emulator-5556`, ARM translation, isolated `.dhome` and
  `.dhome.test` identities. The regular installed application was not replaced.
- Final normal APK: `app/build/outputs/apk/debug/app-debug.apk`, application ID
  `io.github.lrq3000.utterlane`, version **2.1.0 / 210**, ARM64, launcher
  `io.github.lrq3000.utterlane.home.HomeActivity`, confirmed with aapt2.
- APK SHA-256:
  `81a643a35085970aaf38a81369e06b78685d94fe6d210c38a0fea17d282fb60b`.
- Latest-main integration includes `aa1950a` progress D and `9dc33a8` Bluetooth
  continuity. The feature retains the continuous reader, EOF anchoring, measured
  file progress/qualified ETA, route selection and persistent fallback feedback.

## Automated evidence

The original pre-feature baseline passed 300 JVM tests. The final full JVM run
passed **479 tests, zero failures or ignored tests** (5.133 seconds in the report).
Debug QA app/test APKs and the final normal-identity debug APK built successfully.

Native evidence covers **93 distinct selected tests across batches and focused
reruns**, not a single all-green monolithic run:

| Run | Result and interpretation |
| --- | --- |
| Journal/result metadata after review correction | 16 passed. The interrupted-label hydration/Keep case failed before the fix. |
| Initial broad Home/history/dialog/onboarding batch | 72/73 passed; the pin-order fixture included an unrelated expiring recording. |
| Corrected pin fixture | All 3 passed; compare all test-owned rows in relative order, preserving retention/launch-grace and UI assertions. |
| Expanded combined batch including journal/metadata | 88/89 passed; the workspace navigation test clicked an identically tagged stale Home window during Settings launch. |
| Corrected workspace and real-recognition acceptance | All 5 passed in 83.683 seconds: both workspace tests and the three selected speech tests below. Settings navigation now awaits its actual window. |
| Legacy journal compatibility | 1 passed. |

The 89-test combined selection includes `HomeLauncherAndroidTest`,
`HomeFlowAndroidTest`, `HomeWorkspaceAndroidTest`, `HomeDetailOwnershipAndroidTest`,
`HomeFileReplacementAndroidTest`, `home.HomeJournalAndroidTest`,
`TranscriptionResultMetadataAndroidTest`, `HistoryPresentationAndroidTest`,
`HistoryNavigationAndroidTest`, `HistoryScreenAndroidTest`, `HistoryPinsAndroidTest`,
`HistoryListInteractionAndroidTest`, `WaveformButtonAndroidTest`,
`CapturePanelAndroidTest`, `AudioInputAndroidTest`, `TranscriptionProgressAndroidTest`,
`TranscriptionDialogDAndroidTest`, `TranscriptionDialogAndroidTest`,
`AudioPlaybackAndroidTest`, and `OnboardingAndroidTest`.

Coverage includes launcher/onboarding/replay, permission failure, rapid Stop,
capture during model failure, tab/Settings navigation and activity recreation,
independent retained scroll positions, temporary input ownership, rejected file
replacement, full-store text transfer, duration/label metadata, canonical cache
leases, cross-owner Keep/delete and explicit deletion scopes. Main's seven progress
UI tests, 19 dialog D tests and actual emulator phone-routing tests are included.

### Real recognition

- `HomeRecognitionAndroidTest#realSpeechStreamsIntoHomeAndKeepsIndependentLabeledHistory`
- `OnboardingRecognitionAndroidTest#speechTrialInsertsRealRecognitionWithoutKeyboardSetup`
- `OnboardingRecognitionAndroidTest#sampleShareReachesTheRealAudioReceiverAndProducesText`

These use the bundled public-domain nine-second Alice reading and the verified
local Parakeet Ultra Q8 model. Home verifies actual recognized text, incremental
publication, complete clipboard contents, independently saved timestamp/duration,
actual fixed-one speaker labels, temporary audio, rejected-file preservation, and
both history destinations. The fixed-one mode checks label plumbing; it is not a
multi-speaker attribution benchmark. No model download or private speech fixture
was required.

The broader `OnboardingRecognitionAndroidTest` class was also attempted: its
optional `speakerOptInKeepsAnExistingExplicitCount` test failed its explicit
speaker-model-installed prerequisite. That test is **not included** in the 93
selected passing cases. The three relevant speech methods were rerun explicitly
and passed. Physical multi-speaker inference remains outside this acceptance run.

## Reproduced review corrections

- Actual input ownership, not readiness or an allocated ID, gates replacement of
  the previous workspace. Missing/empty/revoked file selections preserve it.
- Cross-owner Keep publication and confirmed deletion share authoritative identity
  guards. Old provenance, aliased Android paths or late observers cannot restore
  an explicitly discarded source or silently broaden confirmed deletion.
- Interrupted live text checkpoints actual committed-label state before audio
  finalization. Audio hydration fills chronology without erasing that state;
  explicit Keep retains it. The new native assertion originally failed with
  `Audio hydration must not erase committed text labels`, then passed after fixing
  the journal/caller/hydration path.
- A final independent read-only review and targeted follow-up found no remaining
  blocking issue in the integration/correction. Review is separate from runtime
  evidence; it did not itself execute tests.

## Visual evidence and limits

Inspected native Record screenshots at 700×1500 / 100% font and 560×1400 / 130%
font in both light and dark modes. Local evidence is in ignored `qa-artifacts/`:
`home-final-portrait`, `home-final-compact-large-text`, and
`home-final-dark-large-text` (PNG and XML). The title/load icon, centered wordmark,
compact transfers, speaker switch, flat idle waveform and fixed navigation remain
available. At narrow/enlarged sizes the workspace scrolls while navigation stays
fixed; the privacy line can initially be below the visible scroll viewport.
Native history and recognized-result screenshots are also produced under the QA
app's external `files/onboarding-qa/` directory by the tests.

The approved privacy sentence is width-fitted to **one line**. This deliberately
limits visual enlargement in a fixed-width narrow window; full semantic text
remains available to accessibility services. Do not interpret these screenshots
as universal accessibility certification. New strings use English fallback pending
the release translation batch, alongside existing localized actions.

Runtime evidence is API 28 emulator evidence. It does not establish Android 14+
foreground-service behavior, physical microphone acoustics or Bluetooth radio
handover. See [Bluetooth QA](bluetooth-input.md) for its hardware matrix. The
separate main progress test requiring a ternary model was not rerun here; the
shared progress UI and real Ultra file-recognition path were exercised.

Display size, font scale (1.0) and night mode (off) were restored after screenshots.
Failure-path dialog tests temporarily parked only the isolated QA speech model
and restored it in `finally`; no regular-app data was cleared.

## Commands

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.dhome" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Choose the classes/methods and installed-model prerequisites documented above.
adb -s emulator-5556 shell am instrument -w -e onboardingTimeoutSeconds 40 -e class CLASS_OR_METHOD_LIST io.github.lrq3000.utterlane.dhome.test/androidx.test.runner.AndroidJUnitRunner
python tools/qa/emulator_ui.py snapshot --serial emulator-5556 --name home-final-portrait
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug "-PqaApplicationIdSuffix=" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
git diff --check
```

No forced rebuild/clean, cache deletion or foreign process termination was used.
The final APK is a debug build, not a signed store release. The separately
requested performance-branch backup was previously pushed; final remote inspection
found `perf/diarization-speed-study` already at the newer `520c367`.

Agentic stack: OpenCode with OpenAI GPT-6 Astra (openai/gpt-6-astra).
