# Progressive archival UI / real SAF QA — 2026-10-11

**Status: DONE for this delegated QA scope. Final actual-device result: 8 PASS,
0 SKIP, 0 FAIL.** The parent-owned emulator remains running for the accepted
immediate handoff. No production source change, commit, model download, emulator
restart/shutdown/removal, or adb-server operation was performed.

## Target and installed identity

- Worktree: `C:\git\TranSlander\.worktrees\progressive-archival`.
- LDPlayer: `C:\LDPlayer\LDPlayer9`, index **0**, name
  `agent-progressive-archival-a418f9`, serial **emulator-5554**.
- API **28**, physical display **540×960 portrait**, density **240**. These were
  observed, not changed for this run.
- Parent owner: `progressive-archival-20261010-a418f9`; generation
  `progressive-archival-20261010-a418f9-1791683540023582900`.
- Installed app: **io.github.lrq3000.utterlane.dhome**; instrumentation:
  **io.github.lrq3000.utterlane.dhome.test/androidx.test.runner.AndroidJUnitRunner**.
- Version **2.1.0 / 210**, min SDK **26**, target SDK **36**.
- Model-free: both import tests assert no ready model. Final app-private `files`
  listing contains only DataStore, profile marker, and empty microphone history;
  no weights were installed. Missing-model failure/Toast is expected in the import
  fixtures; this run does not claim successful ASR.
- Final resumed activity:
  `io.github.lrq3000.utterlane.dhome/io.github.lrq3000.utterlane.settings.SettingsActivity`.
  Saved-audio quality is expanded with compression **off**; automatic Markdown is
  **off**, audio storage remains app-private, and the Markdown folder/grant below
  is retained for parent repeat testing. No instrumentation remains in flight.

The `.dhome` suffix was supplied only on Gradle command lines. It was not written
to `gradle.properties`. The default parent QA app remains installed separately.

## Launch failure diagnosis and fixture corrections

### Isolated reproduction

The unmodified external import test failed on a newly installed, model-free
`.dhome` identity too: `startActivitySync` timed out after **45000 ms**. This rules
out the earlier default-package suite interaction as a prerequisite.

Evidence:

- `qa-artifacts/import-external-isolated-before.txt`: 0 PASS / 0 SKIP / 1 FAIL.
- `qa-artifacts/import-external-isolated-before-activities.txt`: target
  `TranscribeActivity` already **RESUMED** during the wait.
- `qa-artifacts/import-external-isolated-before-logcat.txt`: PRE_ON_CREATE through
  RESUMED at 04:56:25.446–04:56:25.515, well before the timeout.
- `qa-artifacts/import-external-timeout-logcat.txt`, lines 138–145: runner's actual
  timeout thread dump has Main in `MessageQueue.nativePollOnce` / `Looper.loop`,
  not blocked in app IO or a busy application loop. Worker threads are parked.
- The attempted `debuggerd -j` command is unsupported on this image; its usage
  output is retained in `import-external-isolated-before-threads.txt`. It is not
  counted as a successful thread dump. The runner-provided thread dump above is
  the valid Java-thread evidence.

This demonstrates a launch-completion/idle-wait fixture failure despite a resumed
activity, not an established production Main-thread hog. The precise AndroidX /
API-28 idle-wait implementation defect was not reduced separately.

### Changes

- New test-only `NativeActivityLaunch.kt`: correlates one explicit launch with a
  unique intent marker, waits for its **RESUMED** lifecycle callback with a
  **10-second** timeout, and removes the callback afterward. Tests then wait for
  real visible controls and model state. No production readiness is fabricated.
- `ImportRetentionAndroidTest.kt`: uses that helper for external Open, Share, and
  Home; keeps the `.dhome` identity guard and every retention/ownership assertion;
  bounds explicit retirement waits to 10 seconds. It adds streamed SHA-256
  comparison of the actual original/private bytes for both external copies and
  the kept Home import. Screenshots and accessibility XML are captured after UI
  assertions without an instrumentation-global idle wait.
- `ManagedArchivalAndroidTest.kt`: its supervisor fixture explicitly completes
  and restores onboarding, and uses the bounded native launcher so Home is really
  resumed rather than redirecting to onboarding and finishing.

After the launch-fixture change, both import tests passed in **6.635 seconds**.
Final combined runs below also prove they pass after the managed-archival tests.

## Actual Settings and system folder picker

The real Settings activity was driven using UI-tree-derived targets through
`tools/qa/emulator_ui.py` and local helpers in `qa-artifacts/`, always specifying
`emulator-5554`. The installation uses French system/existing UI strings; new
archival/compression strings correctly fall back to default English.

Manually exercised and visually inspected at 540×960:

1. Saved audio quality **collapsed**: compression detail controls hidden.
2. Expanded, **compression off**: unchecked switch; full native 16 kHz mono PCM
   explanation and new-operation/recoverability help visible; format/rate/channel/
   bitrate controls hidden.
3. Compression **on**: checked switch; CPU contention, 192-kbps versus 256-kbps
   storage comparison, and re-transcription accuracy warning visible.
4. Scrolled expanded options: M4A/AAC-LC, 16000 Hz, Mono, 96 kbps, and disabled
   Apply button for the unchanged tuple; snapshot/import/recovery explanation
   readable. Compression was then restored **off**.
5. Markdown **Choose folder** launched the API-28 legacy permission prompt and
   the real system DocumentsUI picker supplied by the SimpleStorage contract.
   Granted the requested permission through its visible **AUTORISER** button.
6. Selected **Téléchargements**, used **Nouveau dossier** to create the uniquely
   QA-owned `UtterlaneQA-a418f9-ui` subfolder, then **SÉLECTIONNER**. No unrelated
   storage was modified or deleted.

Long labels and help wrap inside the narrow viewport; all controls/warnings above
are readable by scrolling. No horizontal text/control clipping was observed.
Content cut at the fixed header/navigation boundaries in intermediate screenshots
is normal scroll-viewport clipping, not inaccessible content. Import question
buttons and the full remembered-choice text also fit. The Home result screenshot
contains the expected transient missing-model Toast over the bottom navigation.
This was a visual/native-tree check, not a TalkBack audit.

### Exact real tree URI for repeats

```text
content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FUtterlaneQA-a418f9-ui
```

Observed grant for target package `.dhome`, UID 10062:

```text
mode=0x3 ... persistable=0x3 persisted=0x3
```

The final grant still has `persisted=0x3` with `owned=0x0`, after instrumentation
process/activity changes. The native real-folder test additionally checks exact
URI equality and persisted read/write permissions before writing, and retained
write permission after cleanup. Evidence: `archival-uri-grants.txt` and
`archival-final-grants.txt`.
This grant belongs to `.dhome`; a repeat using the default application identity
must select the folder for that identity rather than assuming permission transfers.

The real-provider case now proves **both** linked explicit Delete and ordinary
unpinned pruning remove their actual provider files. It also checks that a
separate fixture-created unrelated document survives those operations. The test
then removes its own unrelated document; the QA folder is empty at handoff
(`archival-qa-folder-contents.txt`). The folder itself and grant are preserved.

### Real-provider fixture corrections

The first combined run after strengthening provider assertions had 6 PASS /
0 SKIP / 2 FAIL:

- API-28 Downloads reported the `.md` file as `application/octet-stream`, unlike
  the controlled provider, which remembers `text/markdown`. Its extension-derived
  MIME behavior cannot be assumed to echo `createDocument`'s MIME. The test now
  strictly verifies the requested `text/markdown` MIME and `.md` name, accepts the
  platform `MimeTypeMap` fallback **only for Downloads**, and still compares every
  line of the full Markdown body/header and EOF. Controlled-provider MIME remains
  strictly `text/markdown`.
- A direct query of a deleted controlled-provider child threw `SecurityException:
  not a descendant of root` before its `queryDocument` ran. The absence assertion
  now walks the accessible parent's child cursor and checks the exact opaque ID,
  rejecting loading/error cursors. It does not broadly swallow permission errors.

Both were test assumptions; no production provider or cleanup behavior was changed.
The corrected combined group passed 8/8, then passed 8/8 again after adding the
bounded byte-digest assertions and XML evidence capture.

## Final on-device results

Evidence: `qa-artifacts/archival-import-export-final-proof.txt`.
Elapsed instrumentation time: **12.593 seconds**.

| Suite | PASS | SKIP | FAIL |
|---|---:|---:|---:|
| ManagedArchivalAndroidTest | 5 | 0 | 0 |
| ImportRetentionAndroidTest | 2 | 0 | 0 |
| AudioExportSnapshotAndroidTest | 1 | 0 | 0 |
| **Total** | **8** | **0** | **0** |

The managed suite includes real persisted-folder output/linked cleanup, pin versus
prune behavior, revoked-provider retry, connection deadline handling, and killing
a hung isolated archival worker while preserving the parent and private input.
The imports prove size/DISCARD and duration/KEEP remembered choices, no copy before
the question, cancellation preserving the old Home owner/journal, unchanged source
bytes, actual PCM track inspection despite the misleading extension, ordinary
unpinned retained audio, and recovery confirmation after expected ASR failure.

### Exact build/install/repeat commands

All commands run from this worktree. Build calls used a 600000 ms tool timeout.
Normal incremental compilation was used; the earlier generated-ABI workaround was
not needed. Latest app/test APKs are installed on the same delegated emulator.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.dhome" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

adb -s emulator-5554 install -r "C:\git\TranSlander\.worktrees\progressive-archival\app\build\outputs\apk\debug\app-debug.apk"
adb -s emulator-5554 install -r "C:\git\TranSlander\.worktrees\progressive-archival\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

# Subsequent test-only edits were compiled before their test-APK reinstall:
.\gradlew.bat :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.dhome" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

adb -s emulator-5554 shell am instrument -w -r -e class "io.github.lrq3000.utterlane.ManagedArchivalAndroidTest,io.github.lrq3000.utterlane.ImportRetentionAndroidTest,io.github.lrq3000.utterlane.AudioExportSnapshotAndroidTest" -e archivalTreeUri "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FUtterlaneQA-a418f9-ui" io.github.lrq3000.utterlane.dhome.test/androidx.test.runner.AndroidJUnitRunner

adb -s emulator-5554 pull /sdcard/Android/data/io.github.lrq3000.utterlane.dhome/files/import-retention-qa qa-artifacts
git diff --check
```

The local `qa-artifacts/import_ui_run.py` wrapper executes the same instrumentation
command, saves raw output/logcat and bounds the host wait. No timeout recovery was
needed in the final runs. Artifacts are intentionally ignored by Git.

No production or JVM-test code changed in this delegated pass, so the inherited
648-test JVM result was not rerun or relabeled as this session's verification.
The separately documented Windows/JDK deletion intermittence remains unresolved;
see `progressive-archival-jvm-deletion-2026-10-11.md`. The parent-reported codec suite
result (9 PASS / 1 unavailable-C2 SKIP) was not rerun or counted in the eight above.

## Screenshots / UI trees

All paths below are relative to this worktree; each PNG has a same-basename XML:

- `qa-artifacts/import-retention-qa/import-external-size-question.png`
- `qa-artifacts/import-retention-qa/import-home-pending-duration-question.png`
- `qa-artifacts/import-retention-qa/import-home-retained-result.png`
- `qa-artifacts/archival-settings-quality-collapsed.png`
- `qa-artifacts/archival-settings-compression-off.png`
- `qa-artifacts/archival-settings-compression-on.png`
- `qa-artifacts/archival-settings-expanded-options.png`
- `qa-artifacts/archival-picker-permission.png`
- `qa-artifacts/archival-system-folder-picker.png`
- `qa-artifacts/archival-picker-owned-folder.png`
- `qa-artifacts/archival-real-folder-selected.png`
- `qa-artifacts/archival-settings-handoff-compression-off.png`

Additional final identity/activity evidence: `archival-installed-package.txt`,
`archival-final-activity.txt`, `archival-model-free-files.txt`.

## Handoff

The only source edits in this pass are test fixtures:
`NativeActivityLaunch.kt` (new), `ImportRetentionAndroidTest.kt`, and
`ManagedArchivalAndroidTest.kt`, under `app/src/androidTest/java/io/github/lrq3000/utterlane/`.
This report and ignored local evidence/helpers complete the change set. Parent
production changes, including the `:archive_test_provider` process name, are intact.

Exclusive delegated ADB use is finished. The accepted immediate handoff returns
use to parent `progressive-archival-20261010-a418f9`; ownership/generation are
unchanged. The coordinator record remains `keep_running=true`,
`shutdown_ready=false`, with evidence saved. Do not stop/remove/reboot this instance
or the adb server: the parent has remaining QA.
