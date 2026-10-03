# Audio history and long-audio QA

## Target and build

- Worktree: `.worktrees/audio-history-streaming`; branch: `feat/audio-history-streaming`.
- Baseline source: `db74ab2`, version 1.2.4; debug package `at.webformat.translander`.
- LDPlayer 9, serial `emulator-5554`, Android API 28, ARM64 compatibility on an x86 emulator.
- JDK 21, Gradle wrapper 8.12.1, Android SDK 35, Kotlin 2.0.21.
- Local validation uses the ignored official sherpa-onnx 1.12.23 AAR. Source-built release/F-Droid packaging is unchanged.
- The three downloaded ONNX files matched the SHA-256 hashes in `ModelManager`.

Final incremental verification on 2026-10-03:

- JVM: **22 passed**, zero failures.
- Android: **12 passed, 1 explicitly skipped**, zero failures.
- Debug APK build and `git diff --check`: passed.
- APK SHA-256: `f7012f762d71face9297dc71d1a652dcbcbdffe3823154b1d682e848f8b4597f`.
- Final APK installed and Settings launched in LDPlayer with **No history** selected by default; model files are present for manual testing.
- Persisted cleanup job 2401 was forced through `cmd jobscheduler run` and returned to `waiting` after completion.
- The temporary baseline comparison worktree was removed after recording its evidence; the feature worktree is preserved.

## Reproduced failure and comparison

`tools/qa/audio_fixture.py` creates a repeated spoken WAV from the official
Parakeet v3 JFK example, without allocating the complete fixture in RAM.

The baseline failed on a 24-minute, 48 kHz stereo WAV (276,480,044 bytes):

```text
java.lang.OutOfMemoryError: Failed to allocate a 32784 byte allocation
with 26296 free bytes and 25KB until OOM,
max allowed footprint 201326592, growth limit 201326592
at com.translander.transcribe.AudioDecoder$decodeToRawPcm$2
    .invokeSuspend(AudioDecoder.kt:205)
```

Recognition had initialized, but the failure occurred while accumulating
decoded PCM, before inference. The process exited.

The bounded pipeline completed that same source:

- 144 completed ownership intervals; final endpoint 23,040,000 samples at 16 kHz.
- Maximum native inference input: 192,000 samples (12 seconds).
- Completed text visible at 22% source progress, not just at completion.
- Complete transcript file: 30,398 bytes.
- No crash-buffer entries in that run.
- Early/late memory snapshots: Java summary about 11.2/11.6 MB; native PSS about
  1.40 GB in both snapshots. The emulator/native model still has a substantial
  fixed footprint; these are not physical-phone performance measurements.

## Automated coverage

### JVM tests

`AudioPipelineTest` and `HistoryTest` cover:

- Window cap, disjoint sample ownership, tail/empty flush, subword ownership,
  legitimate repeated words, and correction phrases crossing segments.
- Stateful stereo/noninteger-rate conversion independent of buffer boundaries.
- Bounded microphone queue overflow without growth.
- Bounded previews, complete immutable snapshots, recovered previews, UTF-8
  page edges, and deferred deletion while export readers hold leases.
- Correct platform error categories instead of treating every failure as audio.
- All eight retention policies, exact expiration deadlines, backward clock
  movement, No history creating no audio, and active-reader protection.
- Interrupted-header repair, interrupted-session recovery, sample-offset reads,
  hourly WAV rollover, and export surviving deletion of original history.

### Android codec/capture/storage/UI tests

`AudioAndroidTest` uses actual Android codecs, AudioRecord, storage permissions,
MediaPlayer, Compose UI, and native Parakeet:

- Two-hour 16 kHz WAV decoding: 115,200,000 owned samples, 720 bounded windows.
  A measured run peaked at 6,821,432 bytes of Java heap. This test exercises the
  complete two-hour decode/segmentation path, not two hours of native inference.
- Native short speech: “Ask not what your country can do for you. Ask what you
  can do for your country.”
- Actual microphone capture with history enabled and disabled.
- Busy/cancelled capture cleanup and subsequent microphone reuse.
- Persisted history policy versus the initial Compose placeholder (the old
  destructive placeholder-policy race was reproduced, then fixed).
- A real writer permission-denial failure: immediate warning, live capture
  continues, then normal stop/drain.
- Saved recording playback and retranscription through the history UI.
- Caller cancellation during final-result delivery does not lose recovery or
  strand a cache owner.

### Input integrations

`AudioIntegrationAndroidTest` runs real services, editor actions, caller/result
IPC, and Parakeet. Its factory substitutes only capture with a deterministic
real-time PCM source (200 ms blocks); actual AudioRecord is tested separately.

- Accessibility input: text appears during capture; changing focus prevents
  delivery to the unrelated field and recovers the complete result to clipboard.
- Floating microphone: actual overlay taps insert text during capture.
- Voice IME: text is committed during capture, Done drains the tail, and the
  prior keyboard is restored.
- Voice activity: attached preview view updates during capture; the actual Done
  listener returns the final transcript to a separate calling activity.

LDPlayer hides its navigation accessibility button and omits the non-focusable
voice activity from accessibility window traversal. The test invokes the
unchanged accessibility-button callback entry point and reads the actual
attached voice-overlay view. These are documented QA adaptations, not changes
to the production input behavior.

### Explicit SpeechRecognizer limitation

An external test caller could resolve the recognition service and had granted
RECORD_AUDIO permission, but LDPlayer returned false from direct service
binding before starting recognition. The same refusal was reproduced with a
freshly built, unchanged `db74ab2` APK in `.worktrees/baseline-bind-check`, using
the same standalone Java caller. Evidence screenshot/XML:
`qa-artifacts/baseline-speech-bind.png` and `.xml`.

The automated test records a **skip**, not a pass, only for this refusal on the
identified LDPlayer/API-28 target. Other targets still fail normally. Platform
partial/final callbacks therefore require validation on another Android
target; their error mapping and shared pipeline have independent test coverage.

## Reproduction commands

From the feature worktree:

```powershell
python tools/qa/audio_fixture.py --seconds 1440 --rate 48000 --channels 2
adb -s emulator-5554 push qa-artifacts/long.wav /sdcard/Download/long.wav
adb -s emulator-5554 push qa-artifacts/speech-source.wav /sdcard/Download/speech-source.wav
```

The Android tests expect the four model files in
`/sdcard/Download/parakeet-qa/`, named `encoder.onnx`, `decoder.onnx`,
`joiner.onnx`, and `tokens.txt`. They copy these into private model storage
because Gradle's connected-test installer can recreate app data.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug connectedDebugAndroidTest --console=plain --quiet
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n at.webformat.translander/com.translander.transcribe.TranscribeActivity --es file_path /sdcard/Download/long.wav
```

No `clean`, forced rerun, or verbose/debug Gradle flags are needed. An observed
Windows Kotlin-daemon temporary-directory cleanup error fell back successfully
to normal compilation; it did not fail the targeted playback/retranscription run.

Reports are under `app/build/test-results/testDebugUnitTest/` and
`app/build/outputs/androidTest-results/connected/debug/`; Android per-test
logcat captures are in the device subdirectory. UI evidence generated by
`tools/qa/emulator_ui.py` is under ignored `qa-artifacts/`.

## Boundaries of the evidence

- End-to-end native inference was validated on the 24-minute source; two-hour
  source decoding/segmentation was validated independently.
- Chunked recognition can differ near boundaries. Timestamp ownership avoids
  blind text deduplication, but tests do not establish perfect transcription of
  arbitrarily slow/long words across every forced cut or every supported language.
- Android 9 does not establish Android 14+ foreground-service behavior or timing
  on a physical ARM phone. Background pruning is best-effort under OS scheduling.
- New UI strings use English fallback until the project's normal translation batch.
- Automatic audio history and temporary/export text lifetimes are described in
  `PRIVACY_POLICY.md`; No history creates no microphone audio files.
