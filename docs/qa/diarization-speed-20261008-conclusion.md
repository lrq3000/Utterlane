# Diarization speed study — concluding message

> Archived from the concluding discussion on 2026-10-08, before the subsequent
> adoption of the measured context profile. Statements about defaults and Git
> publication describe that point in the discussion. Documentation links have
> been adjusted for this report's location.

**I found a promising additional optimization: reducing the speaker cache/FIFO/update settings to `192 / 192 / 160` reduced diarization processing time by about 20% in the closest controlled comparison.** It preserved speaker-label quality on the tested recordings.

I completed **16 acoustic runs** on the dedicated LDPlayer QA instance.

## Recommended experimental profile

| Setting | Current default | Experimental profile |
|---|---:|---:|
| Latency preset | Low latency | Low latency |
| Batch size | 16 | 16 |
| Diarization threads | 4 | 4 |
| Speaker cache frames | 264 | **192** |
| FIFO frames | 264 | **192** |
| Update frames | 222 | **160** |

### Strongest comparison

These runs processed the same **93.67 seconds of two-speaker audio**, with identical ASR words, timings, and PCM windows:

| Run order | Diarization time |
|---|---:|
| Current defaults | 146.951 s |
| Experimental profile | **119.029 s** |
| Current defaults again | 148.960 s |

The candidate used approximately **20% less processing time** than the surrounding baseline runs.

All three produced:
- **12 expected speaker turns**
- **Zero unknown words**
- **100% speaker accuracy on matched words**

This is a **diarization-stage improvement**, not a claim that the entire transcription becomes 20% faster.

## Other findings

### Batch 16 was a useful change

Batch 8 required **23 native forwards**, versus **15 with batch 16**, on this workload. Both repeated batch-8 runs were slower than their nearby batch-16 comparisons.

Increasing the cap beyond 16 is unlikely to help much with the current default ten-second ASR windows: roughly fourteen Low-latency steps fit in a typical full window.

### Keep four threads for this emulator

The two-thread screening run took **318 seconds**, compared with **219 seconds** for the initial four-thread baseline. There is no evidence here supporting a reduction to two threads.

### Almost all diarization time is in the transformer stage

In the initial full replay:

| Stage | Time |
|---|---:|
| Transformer, including graph preparation and execution | **211.192 s** |
| Feature extraction and embeddings | 1.061 s |
| Cache maintenance | 0.062 s |

The transformer accounted for **99.46%** of diarization time.

That explains why reducing retained context helps: it reduces the amount of historical data processed by the transformer. Optimizing the cache-maintenance code itself would have negligible impact.

### Switching attention implementations did not establish a gain

The alternative manual-attention run was close to the initial baseline. Given the observed timing variation, there is no convincing reason to replace the existing CPU flash-attention default.

## Accuracy and measurement limits

I confirmed the experimental profile with fresh neural ASR:

- **Two-speaker, 94-second recording:** all 12 turns, no unknown labels, 100% matched-word speaker accuracy.
- **Single-speaker French/English recording:** **70/70 words**, one speaker turn, no unknowns or missing tail words.

The two-speaker ASR still had **3.125% word error**, reported separately from speaker-label accuracy.

There was substantial early emulator timing drift: an unchanged baseline improved from 219 seconds to roughly 148 seconds. That is why the headline uses the closely spaced baseline–candidate–baseline comparison.

**The smaller context is promising, but these one-/two-speaker recordings do not establish that it is suitable for every user.** Four-to-eight speakers, overlapping speech, and speakers returning after long absences need coverage before making it a general default. Production defaults remain unchanged.

## Best next implementation optimization

The native code reconstructs and allocates its computation graph on each forward. The next focused investigation should **separate graph-preparation time from neural-compute time**, then determine whether bounded graph/buffer reuse is worthwhile.

A simple autoregressive-style KV cache would not be a safe shortcut: this model uses full attention over changing historical and current context, so previously computed hidden states are not necessarily reusable unchanged.

## Saved work

- [Full report](diarization-speed-20261008.md)
- [All 16 numeric observations](diarization-speed-20261008.csv)
- Reproducible benchmark runner and PCM-checked ASR replay support.

Committed locally on `perf/diarization-speed-study`:
`97e631b`, `120ee3f`, `d982ec2`, `a661b9c`.

Debug builds and **50 supporting tests passed**. All task changes are committed. The QA emulator was stopped and released after exporting the evidence.
