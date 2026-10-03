# Models and live capture QA — 2026-10-03

Base: `669a5a3`, fast-forwarded into local main. Feature worktree:
`.worktrees/models-live-capture`, branch `feat/models-live-capture`.

## Implemented

- Catalog/default migration, per-model storage, verified atomic download/import,
  cancellation, persisted selection, deletion and session-safe model switching.
- Pinned minimal CrispASR Parakeet CPU runtime and JNI timestamp bridge; existing
  sherpa-onnx default retained. Source preparation added to debug/release CI.
- Shared large bottom capture panel for IME, voice activity, accessibility and
  floating mic. Actual PCM RMS drives fixed-length waveform history. The whole
  central waveform is an accessible Stop button.
- During capture: sustained low signal, missing frames, and Android-reported
  silencing have separate feedback. Capture updates every 50 ms; queue capacity
  remains bounded by samples, not merely block count.
- After stop: completed-owned-sample progress and measured ETA; finalization
  cannot be presented as 100% before completion.

## Evidence

- Android ARM64 native build succeeded with NDK 28.2.13676358 and CMake 3.22.1.
- Ultra Q8_0 and Redux Q8_0 each transcribed the real spoken fixture through
  native JNI, with matching token/timestamp arrays. Both model SHA-256 hashes
  matched the catalog before deployment to LDPlayer.
- 29 JVM tests passed, including catalog integrity, signal/silence/no-frame
  distinctions, constant waveform length, progress, ETA and sample-budget queue.
- Focused Android batch: 5 passed — model selection persistence/verification,
  active-session exclusion/deletion, panel silence/central Stop/progress,
  voice-activity output, IME output and actual microphone history on/off (panel
  behavior is one test; selection and microphone each one test).
- Final targeted Android batch: 3 passed — blocked network cancellation restores
  controls and removes staging, accessibility focus-change recovery, floating
  microphone live text output. Final report: 56.616 seconds, zero failures.
- Source review findings addressed: atomic model deletion exclusion, cancelled
  I/O state restoration, retry of same-sized checksum-invalid models, clean CI
  native preparation, and first-tap loading feedback for overlays.
- `git diff --check` passed. Normal incremental builds were used; no clean or
  forced task reruns. Large network artifact downloads were resumed, not restarted.
- Actual AudioRecord visual check: `qa-artifacts/capture-panel.png` shows the
  bottom panel, waveform stop target, selected model, elapsed time and live
  low-signal warning. Final APK installed and Settings launched in LDPlayer.
- Final APK SHA-256: `d14716a8f7b37f550d8724d09c62fd8f2ef64eaa167e97908e2d181314eff4a2`.

Reports: `app/build/test-results/testDebugUnitTest/` and
`app/build/outputs/androidTest-results/connected/debug/` (later targeted runs
replace earlier report summaries).

## Limits

- Q4_K variants are catalogued and integrity-pinned, but have not received a
  separate device inference run. Q8_0 was tested for both model families.
- Android 9 LDPlayer cannot test Android 10+ silenced-capture callbacks; the
  callback path is API-guarded and PCM-based silence/no-frame logic is tested.
- The previous baseline LDPlayer SpeechRecognizer binding refusal remains a
  platform validation limitation; it was not retested in this focused pass.
- ETA is based on measured recent inference, not a guarantee of remaining wall
  time. Native inference remains chunked; percentage updates at window completion.
- Native CI preparation was inspected and local source builds passed; GitHub
  Actions and F-Droid release builds were not executed in this Windows session.
- New strings use English fallback until the project's translation batch.

## Faster checks

Compile/install once per source change. For runtime-only iterations use direct
`adb shell am instrument -w -e class ...` with the installed test APK. For code
changes run only the relevant JVM test class and assembleDebug incrementally;
avoid repeated connectedDebugAndroidTest install/orchestration cycles.
