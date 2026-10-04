# Model idle unloading verification (2026-10-04)

Implemented on `feat/model-idle-unload`, based on `4947b71`.

## Automated checks

- `gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain -q`
  passed. The unit suite contains 48 tests, including seven new deterministic
  idle-policy tests. Durations up to 24 hours use an injected elapsed clock.
  The final normal-identity rebuild recovered from a Kotlin daemon temporary
  directory cleanup error using the compiler fallback (command exit 0); fresh
  XML reports again show 48 tests with zero failures/errors. APK output metadata
  confirms the normal `io.github.lrq3000.utterlane` application identity.
- `ModelIdleAndroidTest`: **11 passed**, including actual delayed expiry,
  concurrent sessions, pending load protection, cancellation/error cleanup,
  stale-timer reset, preference persistence, live policy changes, real worker
  process exit and on-demand replacement, and floating-microphone preservation.
- `ModelRecoveryAndroidTest`: **7 passed**, including real Q8 transcription for
  both Ultra and Redux, blocked loading/reset, worker death, native-inference
  termination, and failed-decode recovery.

The floating-microphone resume regression was observed failing before its fix
and passing afterward. The initial policy/manager tests also failed compilation
before their new APIs were implemented.

## Device test isolation

Device: `emulator-5554`, Android API 28. An initial full test run under the normal
application ID was killed by a concurrent install (`ActivityManager` logged
`installPackageLI` immediately before killing the instrumented process).
Subsequent runs used the isolated package `io.github.lrq3000.utterlane.idleqa`.

```text
gradlew.bat "-PqaApplicationIdSuffix=.idleqa" assembleDebug assembleDebugAndroidTest --console=plain -q
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell appops set io.github.lrq3000.utterlane.idleqa SYSTEM_ALERT_WINDOW allow
adb -s emulator-5554 shell am instrument -w -e class io.github.lrq3000.utterlane.ModelIdleAndroidTest io.github.lrq3000.utterlane.idleqa.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class io.github.lrq3000.utterlane.ModelRecoveryAndroidTest io.github.lrq3000.utterlane.idleqa.test/androidx.test.runner.AndroidJUnitRunner
```

Fixtures follow `docs/qa/README.md`. The idle suite needs the catalog-verified
Ultra Q8 GGUF and overlay permission; the recovery suite also uses Redux and
the speech fixture. The QA suffix changes only that debug build invocation;
run normal Gradle commands again to produce the regular application APK.

## UI/startup checks

Using `tools/qa/emulator_ui.py` and UI-tree coordinates:

1. Opened Settings: idle timeout initially **20 min**.
2. Opened its selector: all seven requested choices visible; 20 min selected.
3. Selected Immediate: summary updated and auto-load explanation displayed.
4. Enabled auto-load, force-stopped only the isolated QA app, and reopened it.
   Confirmed the toggle remained enabled, Immediate persisted, the installed
   model remained unloaded, and `pidof <qa-package>:recognition` returned no PID.
5. Restored auto-load off and 20 min.

Screenshots/UI trees are local artifacts under `qa-artifacts/idle-*.png` and
`qa-artifacts/idle-*.xml`. No real twenty-minute/24-hour wait or physical deep
sleep cycle was run; elapsed-time and wake-style deadline rechecks are covered
by deterministic and instrumentation tests.

New English resources follow the project policy of deferring translations until
release preparation.

## Replay onto latest main

The original feature commit `69da86b` was replayed as `2009434` on main's
`70601d2` (native ternary Redux support). There were no conflicts. Stable patch
IDs with zero context matched, confirming all original feature hunks applied.
README, settings UI, and resources retain main's newer backend additions.

Main already provides debug-only `qaApplicationIdSuffix`, so the replay uses
that canonical mechanism and drops the redundant feature-local Gradle init
script. The commands above reflect this adaptation.

Verification on the combined tree:

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.idleqa" --console=plain -q` passed.
- All 49 JVM tests passed, including main's new ternary catalog test; a normal
  `gradlew.bat :app:assembleDebug --console=plain -q` also passed afterward.
- All 11 `ModelIdleAndroidTest` cases passed again on `emulator-5554` in 16.595 s.
- The replay introduced no changes to main's native source pins, native modules,
  backend implementation, or model catalog.
