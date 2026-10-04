# Transcription sleep recovery implementation plan

**Goal:** Prevent automatic screen timeout during live/file transcription, and preserve an active session across screen-off/wake so accepted audio can finish and live capture can continue.

**Architecture:** Session-owned, renewable CPU and screen protection; nonblocking microphone reads with owner-thread recovery; sleep-aware IME lifecycle; disk-backed delivery cursors that never advance before successful insertion. Screen-off is not cancellation. Explicit Stop still drains and explicit Cancel still cancels. Android cannot recover samples its microphone never supplied, or retain in-memory work after process termination; the change targets suspension of a running process.

**Tech stack:** Kotlin, Android API 26+, coroutines, existing JUnit/instrumentation infrastructure.

The user approved implementation and on-device follow-up without requiring reproduction on their phone. Work proceeds inline in the isolated `fix/transcription-awake` worktree. The user subsequently requested a conventional commit; no push was requested.

## Tasks

- [x] Add regressions for a retryable final audio window, disk-backed undelivered text, power ownership and keyboard screen-off lifecycle. The final-window regression failed on the original code before its completion flag was fixed.
- [x] Add session-owned `TranscriptionPower` with separate partial/screen locks, bounded renewable acquisitions, compatible dynamic receiver registration, wake callbacks, and deterministic cleanup. Protect microphone/file work before model loading through finalization. Keep visible recording/file views awake only while working.
- [x] Replace blocking microphone reads with nonblocking polling. Reopen stalled/dead capture on its owner thread after wake, preserving queued samples and the recognition session. Keep Stop/Cancel responsive even without arriving frames.
- [x] Make keyboard input-view suspension retain recording while the device is asleep/locked. Pin delivery to the original input connection; a matching resource ID does not authorize a different connection. Otherwise preserve the full result for recovery. Buffer undelivered text on disk with a byte cursor; flush after wake without duplication.
- [x] Make final-window completion retryable and worker deadlines based on awake time, retaining finite deadlines for genuinely stuck work while awake. Cancellation interrupts only the caller's wait, preserving the shared backend for other active sessions.
- [x] Run focused unit/instrumentation checks and `gradlew.bat assembleDebug`; inspect diff and provide APK path and phone-test steps. Runtime limitations are recorded below.

## Verification and limits

Run `gradlew.bat :app:testDebugUnitTest --console=plain --quiet` and `gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain --quiet`. Use targeted `adb shell am instrument` classes to exercise power release, display timeout, forced screen-off/wake, resumed capture, queued delivery, and final tail draining. Restore emulator settings after tests. Report actual device/API coverage separately from source/API compatibility. Preserve existing logging and keep changes specific to session power, capture and delivery.

References: Android PowerManager and Doze documentation; elastic-rock/KeepScreenOn and both OpenAppsLabs/Coffee and mueller-ma/Coffee. Window flags are preferred for visible UI; screen wake locks are deprecated but retained as session-scoped protection for entry points without an app-owned foreground window. Never modify global screen timeout or force the display on.

## Verification results

- Final `:app:testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain --quiet`: success; **41 unit tests**, zero failures/errors/skips. `git diff --check`: success.
- API 28 emulator development checks passed for overlapping power leases/cleanup, real AudioRecord stopping after screen-off/wake, panel screen-on flags, cancellation during pending disk delivery, and interruption of a sleeping worker wait. Keyboard 30-second timeout + same-session screen-off/wake + new speech + Stop/cleanup, normal IME Stop, and floating delivery also passed during development.
- Forced Doze is explicitly skipped on this emulator: `force-idle` does not make `PowerManager.isDeviceIdleMode` true. No Doze runtime success is claimed.
- Final end-to-end IME revalidation is **inconclusive**: another shell client opened Settings during capture and subsequently replaced the installed application APK. ActivityManager logged those shell-UID launches/installations, and subsequent IME logs lacked this branch's new diagnostics. Further shared-emulator mutation was stopped. Earlier runtime passes do not establish a complete pass for the final revised APK.
- Read-only code review findings addressed: pin original editor connection; invalidate delivery after cancellation even across disk suspension; retain capture watchdog after stale buffered PCM but pause it during later sleep; interrupt cancelled waits without killing a backend still owned by another session.

## Phone acceptance check

Install `app/build/outputs/apk/debug/app-debug.apk` from this worktree. With a 30-second screen timeout, dictate for at least one minute without touching the screen; it must remain awake. Press Stop and verify the tail. Start another session, deliberately lock/unlock, speak more, and press Stop. Verify either complete insertion into the original still-valid editor or the complete recoverable transcript if Android replaced that connection. Repeat a second lock/unlock within the same session. Check that automatic screen timeout returns after completion/cancellation.
