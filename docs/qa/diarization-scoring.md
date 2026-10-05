# Local diarization regression scoring

Run from the repository/worktree root with Python 3.11+; only the standard
library is needed. The scorer reads UTF-8 text. It does not open audio files,
modify fixtures, require Android tools, or upload data.

```console
python -m unittest tools.qa.test_diarization_regression
python tools/qa/diarization_regression.py /path/to/fixtures
python tools/qa/diarization_regression.py /path/to/fixtures --format json --check
python tools/qa/diarization_regression.py /path/to/fixtures /path/to/candidates
python tools/qa/diarization_regression.py /path/to/fixtures /path/to/run1.txt /path/to/run2.txt --recording recording-id
```

## Inputs and matching

Each fixture is named `<recording>_transcript_true-diarization.txt`. This is the
gold text **and** speaker reference. An optional
`<recording>_transcript_no-diarization.txt` is independently compared against
the gold words and reported as `no_diarization_text`; it never silently replaces
gold. A discrepancy produces a warning.

With no candidate arguments, every recording uses its supplied
`<recording>_transcript_current-diarization.txt`. Candidate directories contain
`<recording>_transcript_<tag>.txt` or `<recording>.txt`; all matching candidates
are scored, excluding gold and no-diarization files. A directory/default run
must cover every selected reference: missing candidates are errors. Explicit
file arguments can score a subset or several runs. For a single selected
reference, **explicit candidate file arguments** may have arbitrary names;
directory entries must always identify a known recording. `--recording`
selects one reference in a multi-recording fixture directory and filters out
directory entries for other known recordings. An explicit file identifying
a different known recording is an error, never reassigned to the selection.

Labels are line-leading `Speaker N:` or `Unknown speaker:` (case-insensitive).
Speaker numbers are identifiers, not their order of appearance. Wrapped lines
continue the preceding block. Unlabeled initial text counts as unknown, with a
warning. Malformed labels and empty labeled blocks are errors; a wholly empty
candidate is a valid **total omission**, not a perfect result. Gold must contain
words, each with a known speaker. Punctuation-only blocks are counted in raw
block totals but have no words or word-derived turns.

## Metrics and denominators

JSON has `schema_version: 1`, a `recordings` array, and an `acceptance` object.
Each candidate gets its own result, with these sections:

| Section / metric | Meaning |
| --- | --- |
| `text.wer` | Exact word edit distance `(substitutions + deletions + insertions) / gold words`; can exceed 1. |
| `text.matched_reference_coverage` | Exactly matched words / **all gold words**. Deletions and substitutions reduce coverage. |
| `text.aligned_reference_coverage` | `(matches + substitutions) / gold words`; presence-only companion to lexical coverage. |
| `text.trailing_reference_deletions` | Gold words after the last gold word paired with a candidate word. Possible truncation, not proof of its runtime cause. |
| `speakers.mapping` | One optimal, one-to-one candidate-to-gold identity mapping for the **entire recording**; null means unmapped. |
| `speakers.matched_word_accuracy` | Correct mapped speakers / exactly matched words. Conditional diagnostic only; null if no words match. |
| `speakers.correct_reference_word_rate` | Exactly matched words with correct speakers / **all gold words**. Joint text/speaker coverage; missing and mistranscribed words count against it. |
| `speakers.unknown_word_rate` | Unknown-labeled words / **candidate words**, including insertions; null for an empty candidate. Missing words are not counted as unknown words. |
| `speakers.candidate_blocks`, `unknown_blocks` | Raw parsed blocks, including punctuation-only blocks and consecutive repeated labels. |
| `turns.reference_sequence`, `mapped_candidate_sequence` | Runs of consecutive word-speaker identities. Repeated same-speaker block labels merge; unknown and unmapped identities remain explicit. |
| `turns.sequence_edit_distance`, `sequence_error_rate` | Edit distance between those sequences, after the same recording-wide mapping; rate divides by gold turns and can exceed 1. |
| `turns.false_fragmentations` | Predicted speaker changes inside a gold speaker run, measured only between words that are adjacent exact matches in **both** texts. |
| `turns.missed_boundaries`, `correct_boundaries` | Absent/present predicted changes at gold changes on those same evaluable adjacent pairs. Boundary presence does not imply correct speaker identity. |
| `turns.evaluated_adjacent_word_pairs` | Scope of boundary scoring; edit gaps are not silently treated as correctly diarized boundaries. |

The full-reference denominator matters: a perfectly attributed half-recording
has 100% conditional speaker accuracy but only 50% correct-speaker coverage.
Check WER, coverage, unknowns, and turns separately rather than interpreting
one percentage as overall success.

### Normalization and algorithms

- Label markup is removed before word scoring; spoken words remain in order.
- Case folding and NFC normalization preserve diacritics. Common straight,
  curly and modifier apostrophes normalize to `'` inside words. Unicode
  letters, numbers and combining marks form tokens. Other punctuation
  (including hyphens and underscores) separates tokens.
- No dictionary, transliteration, accent removal, or language-specific word
  segmentation is applied. Contiguous unspaced CJK text is one token; this is
  a reproducible Unicode word-token score, not a language-specific segmenter.
- Word alignment is exact unit-cost Levenshtein, implemented with Hirschberg
  traceback: worst-case O(N*M) time and O(N+M) memory including the alignment.
  Identical prefixes are consumed linearly. Ties choose the earliest split or
  match, independently of speaker labels. Repeated words can make alignments
  ambiguous; speaker identities must never be used to choose a flattering tie.
- The recording-wide mapping maximizes correctly attributed **exact matches**
  with Hungarian assignment, O(K³) time for K speaker identities. Unknowns
  cannot be mapped; extra/spurious identities cannot all map to one person.
  Substitutions and insertions do not supply evidence for speaker mapping.

These are **timestamp-free text and speaker-attribution metrics, not DER**.
They cannot measure time-based diarization error, overlap, speech activity,
latency, or the acoustic reason for missing text. Turn sequence scoring is
not time-aligned and must be interpreted alongside text coverage.

## Acceptance and exit status

`--check` returns **1** if any candidate fails any configured limit; without
it, failures are reported but exit status is **0**. Invalid inputs return
**2**, including missing references/candidates. Input errors have a JSON
`error` field in JSON mode; argparse syntax errors use stderr.

Defaults are strict and each can be overridden independently:

| Flag | Default | Metric |
| --- | --- | --- |
| `--max-wer` | 0 | `text.wer` |
| `--min-coverage` | 1 | `text.matched_reference_coverage` |
| `--min-speaker-coverage` | 1 | `speakers.correct_reference_word_rate` |
| `--max-unknown-rate` | 0 | `speakers.unknown_word_rate` |
| `--max-false-fragmentations` | 0 | `turns.false_fragmentations` |
| `--max-turn-error-rate` | 0 | `turns.sequence_error_rate` |
| `--max-tail-deletions` | 0 | `text.trailing_reference_deletions` |

All rates use fractions, not percentages. Limits must be finite/nonnegative;
coverage and unknown-rate limits are at most 1; count limits are integers.
An undefined metric fails its check. JSON includes thresholds and individual
failures with the actual value and comparison, even in report-only mode.
Results are never averaged across recordings to hide a failed recording.

## Optional performance JSONL

Pass `--performance-jsonl /path/to/timing.jsonl` (repeatable) to include a
`performance` summary alongside the quality results. Without timing input,
`performance` is null. Timing summaries are observational and do not affect
the transcript acceptance thresholds. No timing is inferred from audio or
text length.

Emit one JSON object per completed chunk or stage; blank lines are allowed.
Example instrumentation records:

```jsonl
{"recording":"sample","run_id":"run1","phase":"cold","stage":"model_load","elapsed_ms":4200}
{"recording":"sample","run_id":"run1","phase":"warm","stage":"chunk","chunk_id":1,"elapsed_ms":240,"audio_ms":1000,"backlog_ms":0}
{"recording":"sample","run_id":"run1","phase":"warm","stage":"speaker_embedding","chunk_id":1,"elapsed_ms":90,"audio_ms":1000,"backlog_ms":0}
```

| Field | Contract |
| --- | --- |
| `elapsed_ms` | Required finite, nonnegative numeric wall duration for this event. |
| `recording`, `run_id` | Optional nonempty strings; default `unspecified`. Include run identity when combining files from different executions. |
| `phase` | `cold`, `warm`, or `unspecified` (default). The producer classifies cold initialization explicitly; the scorer never guesses from chunk numbers. |
| `stage` | Optional nonempty string, default `chunk`. Use distinct stages for end-to-end chunk latency and nested operations. |
| `chunk_id` | Optional nonnegative integer or nonempty string. Unique within a recording/run/phase/stage; duplicates are rejected, including across files. Omit for non-chunk stages such as loading. |
| `audio_ms` | Optional finite **positive** audio duration processed by this event, for RTF. Omit/null for loading or events without an audio-duration denominator. |
| `backlog_ms` | Optional finite nonnegative queued-audio duration sampled at a consistent point (for example, after processing the chunk). Omit/null when unavailable. |

Additional keys are ignored so instrumentation can include device, timestamp,
thread, backend, or memory details. Invalid records fail with file and line
context; an empty performance stream fails instead of presenting empty success.

Groups are keyed by `(recording, run_id, phase, stage)`. Each group contains
event count, total/mean/p50/p95/max elapsed milliseconds, per-chunk observations,
and backlog sample count/mean/max/last. Percentiles use nearest rank, not
interpolation; `last` follows file argument order then line order (no implied
timestamp sorting).

`rtf = sum(elapsed_ms) / sum(audio_ms)` using **only events with audio_ms** in
both sums. `rtf_event_count`, `rtf_elapsed_ms`, and `rtf_audio_ms` expose that
scope. Missing denominators give null RTF, not zero. This is duration-weighted
RTF, not the unweighted average of chunk ratios. An RTF above 1 means the
measured stage takes longer than its supplied audio duration. For overlapping
windows, that duration is processed-window audio, not necessarily new incoming
audio: use end-to-end `chunk` events and unique incoming audio durations to
evaluate streaming throughput. Do not add nested/overlapping stage totals;
the scorer deliberately reports stages and cold/warm phases separately.

## Opt-in production diagnostics (content-free)

Settings → Advanced recognition → Diagnostics and experimental cache → **Runtime
diagnostics** enables local collection for new operations (default: **off**).
The **Local recognition diagnostics** panel offers **Share diagnostics** and
**Clear diagnostics**. Enabling collection or opening the panel never uploads
anything. Sharing intentionally opens Android's chooser with a read-only
FileProvider URI for a ZIP snapshot. The recipient selected by the user receives
the exported metrics and device/build metadata.

### Bounds, ownership and privacy

- `cache/recognition-diagnostics/{previous,current}.jsonl`: two files, each at
  most **1 MiB**, rotated between whole UTF-8 JSONL records. An oversized record
  is rejected; an interrupted final record is removed on the next process start.
- A single I/O coroutine owns append, rotation, clear and snapshot. Producers
  use a nonblocking bounded queue (**64 records**, each encoded to at most
  **8 KiB**); congestion drops records instead of blocking UI or inference.
  Export/clear suspend while waiting for the same queue and see an ordered,
  consistent prefix of successfully accepted records. There is no filesystem I/O
  under the recognition ownership lock or the diagnostics throttle lock.
- Active activity samples are throttled to at most one per second per request;
  a terminal/error record is emitted once. Capture samples are throttled to one
  per second plus phase transitions. Rapid native stages can be absent from logs;
  UI status continues using the worker's live `activity` flow.
- Only the current request's throttle and each live capture's small throttle are
  retained in memory. There is no permanent in-memory session registry. Session
  collectors are cancelled when their microphone/file operation ends.
- Ring files expire after **7 days since their last write**; exports expire after
  **24 hours**. Cleanup occurs on startup and subsequent writes/exports. Android
  may evict cache earlier. At most **two immutable ZIP exports** are retained,
  each containing at most the two ring files (2 MiB uncompressed plus ZIP headers).
  A third share expires the oldest exported file. **Clear diagnostics** removes
  both logs and exports, and resets dropped/error counters; already-enabled
  operations may start logging again afterwards. It does not change the opt-in
  preference or reset inference.
- No audio, transcript, filename/URI, model display name, waveform, signal level,
  per-frame probability, exception message or stack is serialized. Fixed native
  stage scopes are allowlisted; unknown/overlong strings become `unknown`.
  Build/device identifiers are length-bounded, and do not include serials,
  Android IDs, accounts, or network identifiers. Paths and archive entry names
  are generated internally, never from native events or user input.

### JSONL schema version 1

All lines are standalone JSON objects. Options and environment are repeated so
rotation never detaches a retained sample from its configuration.

| Field | Meaning |
| --- | --- |
| `schema_version`, `kind` | `1`; `activity` or `capture`. |
| `run_id`, `monotonic_ms` | Random app-instance identifier and monotonic sample time; no recording name or wall-clock timestamp. |
| `dropped_records` | Cumulative queue/storage/size drops since startup or successful clear, sampled by the writer. |
| `options`, `options_scope` | Immutable runtime options; `worker_configuration` is captured at the manager's safe configure boundary. `capture_preferences` is the microphone/file operation's starting preference snapshot and must **not** be interpreted as another concurrently owned worker's configuration. Changes apply at the existing operation boundaries. |
| `effective_asr_threads`, `effective_diarization_threads` | Activity records only: Auto resolved using the shared runtime-options policy. |
| `environment` | App version/code, debug/release, SDK, manufacturer, device model, first supported ABI, and native source pins. `native_version_source=build_pins_not_runtime_probe` explicitly identifies build metadata; no runtime native-version IPC is available. Pins must be updated with native dependency changes. |
| `operation_id`, `request_id` | Activity-only local identity. Worker request IDs can restart after model reset; operation IDs disambiguate observed transitions within this app instance. |
| `stage`, `state`, `active`, `opaque` | Fixed native scope, normalized UI stage and flags. Pre-callback stage labels with unavailable computational progress are inferred; waiting is **not evidence of a kernel freeze**. `completed` means a worker request finished, not that the whole transcript finalized. |
| `active_elapsed_ms`, `since_progress_ms`, `completed_units` | Worker-provided awake/interactive elapsed time, age of real progress and completed computational units. UI timers do not invent progress; units have no known total and are not percentages. |
| `capture_id`, `phase` | Capture-only app-local operation identity and fixed lifecycle phase. |
| `captured_samples`, `processed_samples`, `sample_rate_hz`, `backlog_ms` | Accepted microphone/decoded-file PCM samples, disjoint processed ownership, 16000 Hz and nonnegative `(captured-processed)/16` milliseconds. Backlog includes accepted audio awaiting finalization, including disk-backed history; it is not a native FIFO measurement. Rejected capture blocks do not increase the count. |

**Do not pass these snapshot logs directly to `--performance-jsonl`.** Its
`elapsed_ms` contract is completed-event wall duration, whereas these samples
contain cumulative **awake-time** request counters and can include nested stages.
No `audio_ms`, RTF or cold/warm classification is fabricated when the worker API
does not provide a defensible event denominator. Use the fixture instrumentation
above for wall-time performance and detailed private acoustic evidence. The UI
reserves 100% for existing successful finalization, regardless of worker stage.

Focused JVM verification (JDK 21; no native compilation or adb needed):

```console
gradlew.bat :app:testDebugUnitTest --tests "*diagnostics.*" --tests "*CaptureBacklogTest" --tests "*RecognitionStatusTest" --tests "*CaptureMetricsTest" --console=plain -q
```

Integration/UI checks for a device-capable session: observe queued/load/warmup,
ASR and speaker stages; compare live/draining backlog; stop/cancel/reset; enable
diagnostics for the next operation; share while recognition is active; inspect
the ZIP for complete JSONL records; clear while active and verify later records
can resume. Also check large-font overlay layout and chooser URI readability in
a receiving app. JVM tests cover bounds, disabled writes, privacy projection,
rotation, interrupted writes, stable snapshots, expiry, clear, and slow-disk
concurrent producer behavior; they do not exercise Android's chooser UI.
