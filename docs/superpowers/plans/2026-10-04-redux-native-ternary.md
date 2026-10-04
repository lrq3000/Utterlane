# Compact Redux / transcribe.cpp implementation plan

The user approved adding one extra model while retaining all existing backends,
and explicitly selected native ternary kernels to prioritize compact RAM.

## Contract

- New option: Moondream Parakeet Redux TQ1_Q8_0, 159,121,504 bytes.
- Model repository revision: `87cbc354ce32bc9fe144b5b7bcdd9c68538907a9`.
- SHA-256: `74f43ba852479e86e29df92cdbc89aa8215c7e8070f711be424ff466415b6184`.
- transcribe.cpp revision: `ba949120d60f29daaaa13eec65b9c28c2c2112a6`.
- Force `TRANSCRIBE_TERNARY_RUNTIME=native` before model loading inside the
  private recognition worker. Do not silently expand to its default Q4_0 layout.
- Preserve all five existing model IDs/backends and the Ultra Q8 default.
- Preserve bounded 12-second windows, timestamps, warm-up, force unload,
  microphone history, live feedback, and device-sleep protection.

## Execution

1. Pin/download source; inspect native public API, build dependencies and ternary
   layout selection. Add catalog/native tests first.
2. Add a separate Android native library module with its own CMake scope and
   statically embedded patched ggml. Export only the JNI bridge so it cannot
   interpose CrispASR's different ggml. Compile Parakeet only, CPU, optimized even
   in debug. Keep existing API 26 / ARM64 compatibility.
3. Implement RAII model/session ownership and copied token results through the
   transcribe.cpp C API. Convert milliseconds to the common seconds contract.
4. Add the catalog entry, worker dispatch and model information; reuse generic
   download/import/checksum/deletion/selection behavior. Update CI preparation
   and licensing/documentation.
5. Batch focused JVM/native checks and one APK/test build. On LDPlayer use direct
   instrumentation for actual speech, repeated windows, verified native layout,
   memory snapshots, worker reset and switching to existing backends. Avoid full
   repeated Gradle/installation cycles. Report any device/runtime limits honestly.

Download size is not total runtime RAM: activations, dense tensors, tokenization,
and process state still consume memory. Report measurements rather than promise
159 MB RSS. No commits, merge or push were requested for this implementation.
