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
reference, candidate files may have arbitrary names; `--recording` selects
one reference in a multi-recording fixture directory.

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
