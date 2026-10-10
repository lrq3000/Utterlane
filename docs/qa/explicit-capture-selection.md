# Explicit capture choices: latest AudioRecorder fixes

Date: 2026-10-10. Baseline: main-first feature `1cf769db`, on main `f6975ce`.
Donor follow-up: `e1c8020..2c7664afac3fece3a7f3debfdf5eb32676ff6978`, four commits
on `lrq3000/AudioRecorder` / `feat/bluetooth-mic-experiments`. Rechecked the remote
tip after implementation; it remained `2c7664a`.

The requested main squash was only staged when the user raised these newer fixes.
Verified that its index exactly matched the feature, then restored only the known
staged integration and removed the obsolete squash message. Main and its four
unrelated untracked audio/transcript fixtures were preserved. No earlier squash
commit or push was published.

## Findings and adaptations

1. **Hidden API/mode substitutions were present in Utterlane.** Android 12+ Standard
   routing selected `setCommunicationDevice` and forced communication mode. Standard
   SCO now uses `startBluetoothSco` on every SDK. `COMMUNICATION_DEVICE` is an explicit
   third option requiring Android 12+; HFP voice recognition stays separate. Each
   route requests the selected mode and validates its observed value. Unsupported
   APIs/mode rejection show a persistent Phone-fallback reason and retain preferences.
2. **Callbacks alone could leave stale input success.** Read actual native input and
   mode at both read boundaries. Freeze/check the native descriptor against the
   selected hashed identity, reject contradictory addresses/types and stale reused
   IDs, resolve a matching classic SCO port for a dual-mode selection, and avoid a
   null preferred-device request while the desired source remains unknown.
3. **Buffer transitions require honest attribution.** Track recorder routing epochs
   and selected/bound source-loss epochs. Retain all returned PCM but withhold new
   input/fallback confirmation until stable observations have consumed one actual
   client-buffer capacity. The donor instead discards these samples and rejects
   Phone fallback; that conflicts with Utterlane's input-preservation and unattended
   continuity requirements. The adaptation keeps Phone startup/fallback explicit.
4. **Validate declared capture configuration.** Reject a substituted non-default
   source, incompatible 16 kHz mono PCM16 declaration, unadvertised UNPROCESSED, or
   unusable client capacity before capture/effects. DEFAULT intentionally delegates
   source choice. Diagnostics include observed encoding and redacted failure reasons.
   Optional hardware effects retain their existing best-effort/observed-status contract.
5. Donor MediaProjection consent, MediaRecorder fallback and AAC bitrate coercion
   branches do not exist in Utterlane's fixed-format AudioRecord capture. Those
   backend-specific changes were reviewed but are not imported. Route help remains
   contextual; no Settings access restriction or recording-panel visibility change
   was introduced, and resizing stays pinch-only.

The complete [32-commit disposition table](audiorecorder-port-review.md) includes
these four updates and all original port decisions.

## Regressions and review corrections

- The initial 21 explicit-route cases produced **20 failures** before correction:
  wrong API/mode requests, missing explicit communication option, callback-delayed
  input/mode changes and silent continued use of another input after selecting Phone.
- Thirteen further SDK-specific classic/dual-mode, reused-identity and client-buffer
  cases all failed before correction.
- Declared-configuration checks initially produced **12 failures out of 21 cases**.
- Independent review found that fixed stall timers could reopen/stop healthy Phone
  capture while verifying a supported 4–10-second buffer, and that callback readiness
  could be combined with an earlier unsampled mode. Nine regressions failed before
  correction. Matching unsilenced PCM now renews liveness while verification has its
  own fixed deadline: client capacity's audio time plus 1.5 seconds of scheduling
  grace. Endless epoch churn remains bounded. Mode is sampled independently of the
  callback's readiness state.
- Three regressions exposed unrelated USB events resetting verification. Only
  owned source loss advances the source-verification epoch; every device event still
  invalidates catalogue/binding publication. Follow-up review found the narrower
  interleaving of an unrelated event during a native query; three more regressions
  failed before the bounded inventory reconciliation and one re-read correction.
- Three existing activation-timeout cases caught stale Bluetooth-mode evidence after
  entering Phone fallback. Read evidence now consistently excludes Bluetooth mode
  once fallback is active; active selected Bluetooth mode remains checked.
- Final read-only review confirmed all findings resolved with no remaining concrete
  blocker. Native queries remain outside the binding lock and reconciliation performs
  at most two inspections; true selected-source loss still invalidates evidence.

## Final automated and native evidence

- **914 JVM tests passed**, zero failures/skips. This includes 52 explicit-route
  cases and 21 capture-declaration cases across SDK 28/31/36, the existing HFP/lifecycle
  matrix, Home/recovery retention and the rest of the combined suite.
- Debug app and Android-test APK builds passed on the final production changes:

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest "-PqaApplicationIdSuffix=.audiorecorderports" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain -q --offline
  ```

- **32 distinct API 34 device cases passed**, in three successful invocations:
  - Nearby Devices denial/settings-return: **1 passed**;
  - existing actual-toast action/error verification, isolated from queued toasts:
    **1 passed**;
  - combined capture/input/playback/floating/settings/Home/exit batch, excluding only
    that already-run toast method: **30 passed**.
- The new real-`AudioRecorder` UNPROCESSED rejection case passed with zero emitted
  samples. Its raw instrumentation status was **0 (passed), not an assumption skip**.
- The combined batch includes all Custom options (including the new third route),
  real Phone PCM, absent-headset fallback, pinch grow/shrink outside Settings without
  stopping or losing samples, playback, Home ownership/navigation and retention-aware
  exit. Device identity is `emulator-5584`, isolated `.audiorecorderports`, 3 GB RAM,
  two cores, file-backed quickboot RAM disabled.

Use the existing [main-first reproduction setup](audiorecorder-ports-main-first.md),
running the toast method separately, then excluding it from the combined class list:

```powershell
adb -s emulator-5584 shell am instrument -w -e class 'io.github.lrq3000.utterlane.TranscriptionExitAndroidTest#completedActionsAndExistingFailurePathsProduceToastFeedback' io.github.lrq3000.utterlane.audiorecorderports.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class 'io.github.lrq3000.utterlane.CapturePanelAndroidTest,io.github.lrq3000.utterlane.AudioInputAndroidTest,io.github.lrq3000.utterlane.AudioPlaybackAndroidTest,io.github.lrq3000.utterlane.FloatingControlsAndroidTest,io.github.lrq3000.utterlane.MicrophoneSettingsAndroidTest#presetsCustomControlsAndDiagnosticsStayIndependent,io.github.lrq3000.utterlane.HomeWorkspaceAndroidTest,io.github.lrq3000.utterlane.HomeDetailOwnershipAndroidTest,io.github.lrq3000.utterlane.TranscriptionExitAndroidTest' -e notClass 'io.github.lrq3000.utterlane.TranscriptionExitAndroidTest#completedActionsAndExistingFailurePathsProduceToastFeedback' io.github.lrq3000.utterlane.audiorecorderports.test/androidx.test.runner.AndroidJUnitRunner
```

**Evidence boundary:** no physical Bluetooth radio/headset was connected. JVM service
responses and emulator client-format/input observations do not prove acoustic device
identity, hardware codec parameters, OEM DSP bypass or gap-free handover. Returned PCM
is preserved, but transition frames are not represented as individually attributed
to a verified physical input. The existing physical-device matrix still applies.

## Updated standard APK

`assembleDebug -PqaApplicationIdSuffix=` passed after the final changes. Artifact:
`app/build/outputs/apk/debug/app-debug.apk`, verified package
`io.github.lrq3000.utterlane`. SHA-256:
`4dacaf4a45147c58e5d95b6841d1a955bb7c1b82e29e5a9d22438427caa5beb0`.
This supersedes the pre-follow-up artifact in the main-first report.
