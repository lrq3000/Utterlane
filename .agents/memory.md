# Agent memory

## Diarization latency presets

Saved from the discussion on 2026-10-08. This explanation describes the configuration
at that time (Low latency, batch 8); consult `RuntimeOptions.kt` for current defaults.

**Yes—this is an expected trade-off, and NVIDIA’s own benchmarks show the same ordering.** “Ultra-low latency” means **less audio must be buffered before inference can begin**, not that inference runs faster or that the complete transcript finishes sooner.

After tracing our Kotlin → JNI → native implementation, I found an additional reason this trade-off is particularly noticeable in Utterlane: **the presets also change how much audio our batching processes per model invocation.** I found no indication that the options are wired backward.

### 1. “Latency” and processing speed measure different things

The three presets use these parameters:

| Preset | New audio processed per base step | Future context | Nominal input-buffer latency |
|---|---:|---:|---:|
| **Low latency** | 720 ms | 320 ms | **1,040 ms** |
| **Very low latency** | 480 ms | 160 ms | **640 ms** |
| **Ultra-low latency** | 240 ms | 80 ms | **320 ms** |

NVIDIA explicitly defines that last column as **chunk duration + future context, excluding computation time**. These are also the values used by our pinned native implementation.

The trade-off is:

- **Larger steps:** wait for more audio, then process it with fewer model invocations.
- **Smaller steps:** become eligible to produce labels sooner, but invoke the model more frequently.

If computation is sufficiently fast, the smaller steps can deliver earlier labels. If computation is the bottleneck, the extra work can create a backlog and make the observed experience **slower**.

For a prerecorded file, the audio is already available, so reducing the amount you wait to collect offers little benefit; processing efficiency matters much more.

Source: [NVIDIA Nemotron-3-Diarization model card, streaming configuration](https://huggingface.co/nvidia/nemotron-3-diarization#setting-up-streaming-configuration).

### 2. Why smaller steps require substantially more work

Each native inference step processes **new audio plus retained speaker/context embeddings**. The historical context does not shrink proportionally when selecting a shorter step.

In the source, `n3d_stream_step()`:

1. Computes embeddings for the incoming chunk.
2. Retrieves the speaker cache and recent-context queue.
3. Concatenates those historical embeddings with the new embeddings.
4. Runs the transformer over that combined sequence.

Consequently, ultra-low latency processes less new audio per call while repeatedly paying much of the same historical-context and inference overhead.

Without catch-up batching, ultra-low latency needs approximately **three times as many base steps** as low latency for the same recording: 720 ms versus 240 ms of progress per step. **That does not imply exactly three times the wall-clock time**, because individual calls differ in size and overhead.

This is the distinction between caching **speaker embeddings** and caching all the computation necessary to make subsequent inference almost free; our runtime does the former.

### 3. Our batching makes the difference especially relevant

Utterlane’s default diarization batch setting is **8**. It means “merge up to eight already-buffered base steps into one forward pass.”

Because base-step duration depends on the preset, the same batch setting allows different amounts of new audio per merged pass:

| Preset | Maximum new audio per merged pass, batch 8 |
|---|---:|
| Low latency | **5.76 seconds** |
| Very low latency | **3.84 seconds** |
| Ultra-low latency | **1.92 seconds** |

These are **processing caps, not mandatory waiting periods**: the native code processes available complete steps without waiting to fill the batch.

However, when several seconds of audio arrive together, ultra-low latency often needs more forward passes to handle them.

That matters in our integration because [`DiarizedWindowProcessor.process()`](../app/src/main/java/io/github/lrq3000/utterlane/asr/DiarizedWindowProcessor.kt#L42) currently runs ASR for a window and then feeds its fresh audio into diarization. **Changing the diarization preset does not make those ASR windows smaller.** Thus, we can pay the extra cost of smaller native steps without obtaining the full benefit of a diarizer receiving and publishing audio every few hundred milliseconds.

This is an **integration trade-off and potential optimization opportunity**, rather than evidence of a reversed setting or broken model.

### 4. Both upstream and our existing measurements support this

NVIDIA reports the following inference throughput for this model with batch size 1:

| Preset | Eager execution | Compiled execution |
|---|---:|---:|
| Low latency | **38× real time** | **164×** |
| Very low latency | **25×** | **113×** |
| Ultra-low latency | **12.5×** | **54×** |

Higher is faster. These are NVIDIA GPU/BF16 measurements, **not Android performance predictions**, but they demonstrate that this ordering exists upstream too. NVIDIA’s batch size here is also distinct from our catch-up setting.

Source: [NVIDIA inference-speed evaluation](https://huggingface.co/nvidia/nemotron-3-diarization#inference-speed-evaluation).

Our [existing emulator measurements](../docs/qa/diarization-recovery.md#speed-measurements) also recorded:

- **Low latency, catch-up 8, four threads:** 7 native forwards; **92.177 s** total processing.
- **Ultra-low latency, catch-up 8, four threads:** 15 native forwards; **134.434 s** total processing.

Those were individual runs, not a controlled statistical comparison. Longer low-versus-very-low runs were close and noisy, so they do not establish a universal speed ranking between those two.

### Conclusion and recommendation

**Your observation is technically expected; it does not, by itself, indicate an implementation bug.** The source and existing measurements provide a concrete explanation. Establishing whether your device has *additional* avoidable overhead would require a matched benchmark.

Keeping **Low latency** as the default is sensible for the processing-speed behavior you observed. Two useful follow-ups would be:

1. **Clarify the setting’s help text:** “Lower input buffering can increase CPU work and total processing time.”
2. **Benchmark before changing scheduling:** replay identical audio with fixed threads and batching, measuring native-forward count, diarization time, first-label delay, and post-Stop completion time across repeated runs.

**When you say “slower,” is it mainly a longer wait after stopping/opening a file, or are the labels also appearing later while you are speaking?** That distinction determines whether the next investigation should prioritize throughput or live-result scheduling.
