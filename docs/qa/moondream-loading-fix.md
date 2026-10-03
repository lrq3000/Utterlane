# Moondream loading/recovery investigation

Base `5bbd77b`, committed and fast-forwarded to local main at the user's request.
Fix worktree: `.worktrees/fix-moondream-loading`, branch `fix/moondream-loading`.

## Reproduction before changes

- Selected Ultra Q8_0 from actual Settings, using the catalog-verified GGUF.
- Native load completed in about 230 ms on LDPlayer (memory-mapped weights).
- Opening the standard long WAV then stayed at 0%, with an empty transcript,
  native CPU threads running, and no completed first ownership interval for
  several minutes. The recognizer mutex is held during this call and prevents
  unloading/switching. This reproduces the reported apparent-ready/unrecoverable
  behavior, though the user's physical-phone logs are not yet available.
- Inspecting the actual generated `build.ninja` showed `-O3` only on the JNI/
  Parakeet target, not on ggml-base or ggml-cpu, including quantization kernels.
- Load currently marks ready without executing an encoder/decoder graph. Native
  link errors also bypass `catch (Exception)`, and the UI ignores false returns.

## Fix scope

1. Compile ggml CPU/base targets with optimization even in debug builds; retain
   portable ARMv8 and symbols. No model changes or re-download are required.
2. Validate native inference during load before ready, log timing/model identity,
   report meaningful load failure persistently in Settings.
3. Own inference in one private bound worker process for both backends. PCM IPC
   remains bounded to one window. Native hangs/crashes no longer own the UI,
   capture or model-selection lifecycle. Force unload kills only that worker,
   invalidates old session generations, cancels capture and releases queued work.
4. The unload/reset control remains available during loading and failure. No
   unsafe thread cancellation or freeing a model underneath a running native call.
5. Use targeted regression tests for cancel/load races, stale-session invalidation,
   worker failure, repeated load/unload, and Q8 speech through the real worker.

The prior user request to proceed without further design validation still
applies. Existing native/model artifacts are reused locally to avoid downloads;
only focused build/test batches are planned.

## Verification after changes

- `tools/qa/check_native_optimization.py` failed against the preceding branch's
  actual compile database: `ggml.c`, `ggml-quants.c`, and `ggml-cpu.cpp` all lacked
  optimization. It passed against the new debug build with all three optimized.
- 29 JVM tests passed with normal offline/incremental Gradle tasks.
- Initial direct `adb shell am instrument` recovery batch: **4 passed** in
  113.402 seconds. Both Ultra/Redux Q8_0 completed validated load and 24 seconds
  of repeated speech through 12-second native windows; force-reset broke a
  blocked load, LinkageError was reported, and real inference could be terminated
  by closing only its worker process.
- Follow-up recovery regressions: **3 passed** (idle worker death/reload, retry
  preserving the failed worker for reset, pre-dispatch capture reset callback).
- Original-model integration regressions: **2 passed** in 53.937 seconds (live
  floating microphone insertion and voice-activity preview/final result).
- One preceding mixed-model integration run hit legitimate backlog overload on
  ARM-emulated LDPlayer because it inherited Ultra selection. The regression
  fixture now explicitly selects/restores the original model instead of relying
  on persistent settings left by other tests. This does not establish real-time
  throughput for Moondream on a physical phone.
- Read-only review verified fixes for idle worker death, failed-retry ownership,
  and reset-before-dispatch cleanup. Force unload does not await the inference
  mutex or run native model destruction in the application process.
- Manual Settings observation confirms the independent Force unload control is
  visible while Ultra is selected but unloaded; evidence:
  `qa-artifacts/force-unload-control.xml` and `.png`.
- Debug APK installed in LDPlayer; `git diff --check` passed.
- APK SHA-256: `3eecded3ab6ed027991a6cf6adc84e6fbf8e5bb8b9ad67665d8a9f6538ed9c2f`.

## Remaining evidence limits

The user's physical phone/Android version was requested but has not been supplied.
The reproduced apparent-ready/stalled-native/recovery failure was fixed on
LDPlayer; a device-specific native failure may have an additional cause. Such
failures now report their details instead of hiding behind a ready flag, and
Force unload terminates the private inference process. Q4 variants share the
fixed runtime but were not separately downloaded/tested in this fix pass.
The previously documented Android-9 SpeechRecognizer binding refusal remains
outside this load/recovery change. No release/F-Droid build or remote push was run.
