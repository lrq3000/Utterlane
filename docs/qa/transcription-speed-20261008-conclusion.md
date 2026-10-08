# Transcription speed study — concluding message

> Archived from the concluding discussion on 2026-10-08. Statements about Git
> publication describe that point in the discussion. Documentation links have
> been adjusted for this report's location.

**I tested both objectives. The result is that no tested setting change reliably improved first-text delay *and* post-Stop catch-up while preserving transcription accuracy.** I have therefore kept the production defaults unchanged.

I completed **29 recordings on LDPlayer**, each **25.45 seconds long**. Capture ran independently of transcription through the real recording pipeline, so slow inference could accumulate a genuine backlog without stretching the recording.

## Strongest controlled comparison

A balanced baseline–candidate–candidate–baseline comparison tested an **8-second ASR window** against the current **10-second window**, retaining four threads and one-second left/right context:

| Metric | Current 10-second window | 8-second window |
|---|---:|---:|
| Mean first-text delay | 35.185 s | **27.101 s** |
| Mean catch-up after Stop | **45.012 s** | 45.038 s |
| Word error rate in those runs | 0–2.86% | 7.14% |

The smaller window delivered text about **23% earlier**, but **did not reduce catch-up** and worsened accuracy. I did not count that as a successful joint optimization.

The reason is visible in the work measurements: it started inference about two seconds earlier, but processed more overlapping audio and spent approximately two additional seconds doing ASR.

## Other settings tested

- **Threads:** four remained the best supported choice on this four-vCPU emulator. Two and eight were substantially slower; three showed no established improvement.
- **Shorter windows:** delivered text earlier, but often increased repeated context processing, word errors, or remaining work.
- **Reduced context:** some individual runs improved both timing numbers, but lost reference words. For example, halving both contexts produced **29.3 s first text / 33.7 s catch-up**, but deleted **five reference words**.
- **Earlier silence-triggered cuts:** could produce earlier text, but did not establish an accuracy-preserving catch-up improvement.
- **With diarization enabled:** the same trade-off persisted:

| Window | First text | Catch-up after Stop |
|---|---:|---:|
| Current 10 s, two baseline runs | 34.0–36.3 s | **61.3–69.1 s** |
| 8 s | 29.1 s | 70.8 s |
| 6 s | **25.7 s** | 82.8 s |

All audio was captured and processed. The word errors came from recognition/segmentation, not dropped audio.

## The actual bottleneck

Native timing probes found that **encoder execution consumed 97.5% of ASR time**:

- Encoder execution: **49.721 s**
- Decoder: **1.162 s**
- Graph construction and allocation: **0.0078 s**

I also tested the alternate native decoder/projection path; it did not help. A weight-repacking trial did not confirm activation of an optimized layout on this target.

**The next meaningful optimization should target encoder execution.** A separate, shorter initial window followed by larger steady-state windows is another plausible scheduling experiment, but it needs implementation and measurement before claiming that it improves both objectives.

## Saved results

- [Full report](transcription-speed-20261008.md)
- [All 29 observations](transcription-speed-20261008.csv)

The harness records first-text delay, completed-window audio backlog at Stop, actual post-Stop catch-up, and per-second backlog traces.

**Validation:** 22 focused JVM tests and 50 Python tests passed; independent review found no blocking measurement issues. The QA emulator was stopped and released.

All changes are committed locally on `perf/transcription-speed-study`: `3e6b730`, `cf3705c`, `4bb5c2d`, `6f09710`, and `f96f826`.
