"""Local, timestamp-free transcript/diarization regression scoring (not DER)."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import sys
import unicodedata


@dataclass(frozen=True)
class Word:
    text: str
    speaker: str | None


@dataclass(frozen=True)
class Transcript:
    words: list[Word]
    block_speakers: list[str | None]
    warnings: list[str]

    @property
    def tokens(self):
        return [word.text for word in self.words]

    def repeated(self, count):
        if type(count) is not int or not 1 <= count <= 100:
            raise ValueError("repeat-reference must be an integer between 1 and 100")
        # Keep IDs stable across copies. Scoring the combined words once makes
        # returning-speaker swaps visible and merges same-speaker seams naturally.
        return Transcript(self.words * count, self.block_speakers * count, list(self.warnings))


class TranscriptParser:
    """Labels start lines; unlabeled lines continue the preceding block."""

    LABEL = re.compile(r"^(?:Speaker\s+([0-9]+)|Unknown\s+speaker)\s*:\s*(.*)$", re.IGNORECASE)
    MALFORMED = re.compile(r"^(?:Speaker\s+(?:[^:]*:|[0-9-]+\b)|Unknown\s+speaker\b)", re.IGNORECASE)
    APOSTROPHES = str.maketrans({char: "'" for char in "’‘ʼ＇"})

    def tokenize(self, text):
        # Unlike regex \w, Unicode categories retain combining marks in scripts
        # where NFC cannot compose them, without treating underscores as words.
        # Canonicalize before folding: Greek ypogegrammeni becomes a letter,
        # after which normalization can no longer reorder its neighboring marks.
        text = unicodedata.normalize("NFC", text.translate(self.APOSTROPHES))
        text = unicodedata.normalize("NFC", text.casefold())
        tokens, word = [], []
        for index, char in enumerate(text):
            category = unicodedata.category(char)[0]
            internal_apostrophe = (char == "'" and word and index + 1 < len(text)
                                   and unicodedata.category(text[index + 1])[0] in "LN")
            if category in "LN" or (category == "M" and word) or internal_apostrophe:
                word.append(char)
            elif word:
                tokens.append("".join(word))
                word = []
        if word:
            tokens.append("".join(word))
        return tokens

    def parse(self, text):
        blocks, warnings = [], []
        for number, line in enumerate(text.lstrip("\ufeff").splitlines(), 1):
            line = line.strip()
            if not line:
                continue
            match = self.LABEL.match(line)
            if match:
                speaker = str(int(match[1])) if match[1] is not None else None
                blocks.append((speaker, [match[2]], number))
            elif self.MALFORMED.match(line):
                raise ValueError(f"Line {number}: malformed speaker label; use 'Speaker N:' or 'Unknown speaker:'")
            elif blocks:
                blocks[-1][1].append(line)
            else:
                blocks.append((None, [line], number))
                warnings.append("Unlabeled initial text is scored as unknown speaker.")
        words = []
        for speaker, lines, number in blocks:
            content = " ".join(lines).strip()
            if not content:
                raise ValueError(f"Line {number}: speaker label has no content")
            words.extend(Word(token, speaker) for token in self.tokenize(content))
        return Transcript(words, [speaker for speaker, _, _ in blocks], warnings)

    def read(self, path):
        try:
            return self.parse(path.read_text(encoding="utf-8-sig"))
        except ValueError as error:
            raise ValueError(f"{path}: {error}") from error


class WordAligner:
    """Exact unit-cost Levenshtein alignment using linear-space Hirschberg.

    Worst-case time O(N*M); auxiliary memory O(N+M), including the alignment.
    Equal prefixes are consumed in O(N), particularly useful for truncations.
    Ties choose the earliest split/match, never consulting speaker labels.
    """

    def align(self, reference, candidate):
        result = []
        self._align(reference, candidate, 0, len(reference), 0, len(candidate), result)
        return result

    def compare(self, reference, candidate):
        return self.summary(reference, candidate, self.align(reference, candidate))

    def _row(self, reference, candidate, reference_indices, candidate_indices):
        previous = list(range(len(candidate_indices) + 1))
        for depth, i in enumerate(reference_indices, 1):
            current = [depth]
            for column, j in enumerate(candidate_indices, 1):
                current.append(min(previous[column] + 1, current[-1] + 1,
                                   previous[column - 1] + (reference[i] != candidate[j])))
            previous = current
        return previous

    def _align(self, reference, candidate, lo, hi, start, end, result):
        while lo < hi and start < end and reference[lo] == candidate[start]:
            result.append((lo, start))
            lo, start = lo + 1, start + 1
        if lo == hi:
            result.extend((None, j) for j in range(start, end))
        elif start == end:
            result.extend((i, None) for i in range(lo, hi))
        elif hi - lo == 1:
            match = next((j for j in range(start, end) if reference[lo] == candidate[j]), start)
            result.extend((None, j) for j in range(start, match))
            result.append((lo, match))
            result.extend((None, j) for j in range(match + 1, end))
        elif end - start == 1:
            match = next((i for i in range(lo, hi) if reference[i] == candidate[start]), lo)
            result.extend((i, None) for i in range(lo, match))
            result.append((match, start))
            result.extend((i, None) for i in range(match + 1, hi))
        else:
            middle = (lo + hi) // 2
            forward = self._row(reference, candidate, range(lo, middle), range(start, end))
            backward = self._row(reference, candidate, range(hi - 1, middle - 1, -1), range(end - 1, start - 1, -1))
            width = end - start
            split = start + min(range(width + 1), key=lambda j: forward[j] + backward[width - j])
            # Release rows before recursion: retaining them would inflate the
            # space bound for very unbalanced candidate splits.
            del forward, backward
            self._align(reference, candidate, lo, middle, start, split, result)
            self._align(reference, candidate, middle, hi, split, end, result)

    def summary(self, reference, candidate, alignment):
        counts = Counter()
        last_aligned_reference = -1
        for i, j in alignment:
            if i is None:
                counts["insertions"] += 1
            elif j is None:
                counts["deletions"] += 1
            else:
                counts["matches" if reference[i] == candidate[j] else "substitutions"] += 1
                last_aligned_reference = i
        size = len(reference)
        if not size:
            raise ValueError("Reference transcript must contain words")
        return {
            "reference_words": size, "candidate_words": len(candidate),
            **{key: counts[key] for key in ("matches", "substitutions", "deletions", "insertions")},
            "wer": (counts["substitutions"] + counts["deletions"] + counts["insertions"]) / size,
            "matched_reference_coverage": counts["matches"] / size,
            "aligned_reference_coverage": (size - counts["deletions"]) / size,
            "trailing_reference_deletions": size - last_aligned_reference - 1,
        }


class SpeakerMapper:
    """Maximum-weight one-to-one assignment, once per recording, O(K^3).

    Hungarian assignment avoids factorial permutation search and greedy errors.
    Unknown speakers are excluded; zero-evidence/dummy assignments stay unmapped.
    """

    def map(self, reference, candidate, matches):
        gold = sorted({word.speaker for word in reference.words}, key=int)
        predicted = sorted({word.speaker for word in candidate.words if word.speaker is not None}, key=int)
        if not predicted:
            return {}
        weights = Counter((candidate.words[j].speaker, reference.words[i].speaker) for i, j in matches)
        # There must be at least as many columns as rows; dummy columns allow
        # extra predicted identities without mapping several IDs to one person.
        columns = max(len(predicted), len(gold))
        costs = [[-weights[speaker, gold[j]] if j < len(gold) else 0
                  for j in range(columns)] for speaker in predicted]
        assignment = self._assignment(costs)
        return {speaker: gold[j] if j < len(gold) and costs[i][j] < 0 else None
                for i, (speaker, j) in enumerate(zip(predicted, assignment))}

    def _assignment(self, costs):
        rows, columns = len(costs), len(costs[0])
        row_potential, column_potential = [0] * (rows + 1), [0] * (columns + 1)
        owners, predecessors = [0] * (columns + 1), [0] * (columns + 1)
        for row in range(1, rows + 1):
            owners[0], column = row, 0
            distances, visited = [math.inf] * (columns + 1), [False] * (columns + 1)
            while True:
                visited[column] = True
                active_row = owners[column]
                delta, next_column = math.inf, 0
                for j in range(1, columns + 1):
                    if not visited[j]:
                        cost = costs[active_row - 1][j - 1] - row_potential[active_row] - column_potential[j]
                        if cost < distances[j]:
                            distances[j], predecessors[j] = cost, column
                        if distances[j] < delta:
                            delta, next_column = distances[j], j
                for j in range(columns + 1):
                    if visited[j]:
                        row_potential[owners[j]] += delta
                        column_potential[j] -= delta
                    else:
                        distances[j] -= delta
                column = next_column
                if owners[column] == 0:
                    break
            while column:
                previous = predecessors[column]
                owners[column] = owners[previous]
                column = previous
        assignment = [0] * rows
        for column in range(1, columns + 1):
            if owners[column]:
                assignment[owners[column] - 1] = column - 1
        return assignment


class DiarizationScorer:
    def __init__(self):
        self.aligner = WordAligner()
        self.mapper = SpeakerMapper()

    def score(self, reference, candidate):
        if not reference.words or any(word.speaker is None for word in reference.words):
            raise ValueError("Gold transcript must contain words with known speaker labels")
        reference_tokens, candidate_tokens = reference.tokens, candidate.tokens
        alignment = self.aligner.align(reference_tokens, candidate_tokens)
        text = self.aligner.summary(reference_tokens, candidate_tokens, alignment)
        matches = [(i, j) for i, j in alignment if i is not None and j is not None
                   and reference_tokens[i] == candidate_tokens[j]]
        mapping = self.mapper.map(reference, candidate, matches)
        correct = sum(candidate.words[j].speaker is not None
                      and mapping.get(candidate.words[j].speaker) == reference.words[i].speaker for i, j in matches)
        unknown = sum(word.speaker is None for word in candidate.words)
        warnings = list(candidate.warnings)
        if text["trailing_reference_deletions"]:
            warnings.append("Trailing reference words are absent in the alignment; possible truncation (no timestamps).")
        return {
            "text": text,
            "speakers": {
                "mapping": mapping, "reference_speakers": len({word.speaker for word in reference.words}),
                "candidate_speakers": len(mapping), "candidate_blocks": len(candidate.block_speakers),
                "unknown_blocks": candidate.block_speakers.count(None), "unknown_words": unknown,
                "unknown_word_rate": unknown / len(candidate.words) if candidate.words else None,
                "correct_words": correct,
                "matched_word_accuracy": correct / len(matches) if matches else None,
                # This full-reference denominator deliberately penalizes missing
                # and mistranscribed words; conditional accuracy alone can hide a stall.
                "correct_reference_word_rate": correct / len(reference.words),
            },
            "turns": self._turns(reference, candidate, matches, mapping),
            "warnings": warnings,
        }

    def _sequence(self, transcript):
        sequence = []
        for word in transcript.words:
            if not sequence or word.speaker != sequence[-1]:
                sequence.append(word.speaker)
        return sequence

    def _turns(self, reference, candidate, matches, mapping):
        gold = self._sequence(reference)
        predicted = self._sequence(candidate)
        # Preserve each unmapped identity; collapsing all of them to Unknown
        # would erase false turns introduced by spurious speaker clusters.
        mapped = [mapping.get(speaker) or (f"unmapped:{speaker}" if speaker is not None else "unknown")
                  for speaker in predicted]
        alignment = self.aligner.align(gold, mapped)
        distance = sum(i is None or j is None or gold[i] != mapped[j] for i, j in alignment)
        false, missed, correct, evaluated = 0, 0, 0, 0
        for (left_i, left_j), (right_i, right_j) in zip(matches, matches[1:]):
            # Edit gaps do not define an unambiguous word boundary. Do not
            # manufacture a fragmentation measurement across missing speech.
            if right_i != left_i + 1 or right_j != left_j + 1:
                continue
            evaluated += 1
            gold_change = reference.words[left_i].speaker != reference.words[right_i].speaker
            predicted_change = candidate.words[left_j].speaker != candidate.words[right_j].speaker
            false += predicted_change and not gold_change
            missed += gold_change and not predicted_change
            correct += gold_change and predicted_change
        return {
            "reference_turn_count": len(gold), "candidate_turn_count": len(predicted),
            "reference_sequence": gold, "mapped_candidate_sequence": mapped,
            "sequence_edit_distance": distance, "sequence_error_rate": distance / len(gold),
            "extra_candidate_turns": max(0, len(predicted) - len(gold)),
            "false_fragmentations": false, "missed_boundaries": missed,
            "correct_boundaries": correct, "evaluated_adjacent_word_pairs": evaluated,
        }


class FixtureRunner:
    GOLD_SUFFIX = "_transcript_true-diarization.txt"

    def __init__(self):
        self.parser = TranscriptParser()
        self.scorer = DiarizationScorer()

    def run(self, fixture_dir, candidates, recording=None, repeat_reference=1, plain_baseline=None):
        gold_paths = {path.name.removesuffix(self.GOLD_SUFFIX): path
                      for path in sorted(fixture_dir.glob(f"*{self.GOLD_SUFFIX}"))}
        all_gold_paths = gold_paths
        if recording:
            gold_paths = {name: path for name, path in gold_paths.items() if name == recording}
        if not gold_paths:
            raise ValueError(f"No matching *{self.GOLD_SUFFIX} references in {fixture_dir}")
        inputs = []
        if not candidates:
            inputs = [(name, fixture_dir / f"{name}_transcript_current-diarization.txt") for name in gold_paths]
        for source in candidates:
            if source.is_dir():
                paths = sorted(path for path in source.glob("*.txt") if not path.name.endswith(
                    (self.GOLD_SUFFIX, "_transcript_no-diarization.txt")))
                # Resolve names before filtering: selecting one reference must
                # not relabel the other recordings as that remaining reference.
                pairs = [(self._recording_for(path, all_gold_paths), path) for path in paths]
                pairs = [pair for pair in pairs if pair[0] in gold_paths]
                missing = gold_paths.keys() - {name for name, _ in pairs}
                if missing:
                    raise ValueError(f"{source}: missing candidate recordings: {', '.join(sorted(missing))}")
                inputs.extend(pairs)
            else:
                fallback = next(iter(gold_paths)) if len(gold_paths) == 1 else None
                name = self._recording_for(source, all_gold_paths, fallback)
                if name not in gold_paths:
                    raise ValueError(f"{source} identifies unselected recording {name}")
                inputs.append((name, source))
        plain_baselines = self._plain_baselines(plain_baseline, {name for name, _ in inputs}, all_gold_paths)
        reports, references, baselines = [], {}, {}
        for name, path in inputs:
            if name not in references:
                references[name] = self.parser.read(gold_paths[name]).repeated(repeat_reference)
                baseline = fixture_dir / f"{name}_transcript_no-diarization.txt"
                if baseline.exists():
                    tokens = self.parser.read(baseline).repeated(repeat_reference).tokens
                    gold = references[name].tokens
                    baselines[name] = self.scorer.aligner.compare(gold, tokens)
            candidate = self.parser.read(path)
            report = self.scorer.score(references[name], candidate)
            report.update(recording=name, candidate=str(path), reference_repetitions=repeat_reference,
                          no_diarization_text=baselines.get(name), plain_baseline=None)
            if name in plain_baselines:
                baseline_path, baseline_tokens = plain_baselines[name]
                candidate_tokens = candidate.tokens
                report["plain_baseline"] = {
                    "path": str(baseline_path),
                    "text": self.scorer.aligner.compare(baseline_tokens, candidate_tokens),
                    "identical_normalized_words": baseline_tokens == candidate_tokens,
                }
            if baselines.get(name, {}).get("wer", 0):
                report["warnings"].append("No-diarization text differs from gold; gold remains the scoring reference.")
            reports.append(report)
        return reports

    def _plain_baselines(self, source, recordings, gold_paths):
        if source is None:
            return {}
        paths = {}
        if source.is_dir():
            for name in sorted(recordings):
                matches = [path for path in (source / f"{name}_transcript_no-diarization.txt", source / f"{name}.txt")
                           if path.is_file()]
                if not matches:
                    raise ValueError(f"Missing plain baseline for {name} in {source}")
                if len(matches) != 1:
                    raise ValueError(f"Ambiguous plain baseline for {name} in {source}")
                paths[name] = matches[0]
        else:
            if len(recordings) != 1:
                raise ValueError("A plain-baseline file requires one recording; use a directory or --recording")
            name = next(iter(recordings))
            identified = self._recording_for(source, gold_paths, name)
            if identified != name:
                raise ValueError(f"Plain baseline {source} identifies recording {identified}, not {name}")
            paths[name] = source
        baselines = {}
        for name, path in paths.items():
            # A same-build Off run covers the actual complete audio. Never
            # synthesize copies: continuous ASR windows can differ at the seam.
            tokens = self.parser.read(path).tokens
            if not tokens:
                raise ValueError(f"Plain baseline {path} must contain words")
            baselines[name] = (path, tokens)
        return baselines

    def _recording_for(self, path, gold_paths, fallback=None):
        name = path.name.split("_transcript_", 1)[0] if "_transcript_" in path.name else path.stem
        if name in gold_paths:
            return name
        if fallback is not None:
            return fallback
        raise ValueError(f"Cannot associate {path} with a reference; use '<recording>_transcript_<tag>.txt' or an explicit file with --recording")


class PerformanceSummary:
    """Summarize explicit instrumentation without inferring timing from text.

    Phases, runs and stages stay separate: adding overlapping stage durations
    or pooling cold model loads with warm chunks creates misleading RTFs.
    """

    DIMENSIONS = ("recording", "run_id", "phase", "stage")

    def read(self, paths):
        return self._summarize(self._read_events(paths))

    def _read_events(self, paths):
        for path in paths:
            with path.open(encoding="utf-8-sig") as source:
                for number, line in enumerate(source, 1):
                    if not line.strip():
                        continue
                    try:
                        event = self._event(json.loads(line))
                    except ValueError as error:
                        raise ValueError(f"{path}:{number}: {error}") from error
                    event["source"] = f"{path}:{number}"
                    yield event

    def summarize(self, records):
        return self._summarize(self._event(record) for record in records)

    def _event(self, record):
        if not isinstance(record, dict):
            raise ValueError("Performance record must be a JSON object")
        event = {}
        for name in self.DIMENSIONS:
            value = record.get(name, "chunk" if name == "stage" else "unspecified")
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"{name} must be a nonempty string")
            event[name] = value.strip()
        if event["phase"] not in ("cold", "warm", "unspecified"):
            raise ValueError("phase must be cold, warm or unspecified")
        for name in ("elapsed_ms", "audio_ms", "backlog_ms"):
            value = record.get(name)
            if value is None and name != "elapsed_ms":
                event[name] = None
                continue
            if (isinstance(value, bool) or not isinstance(value, (int, float))
                    or not math.isfinite(value) or value < 0 or (name == "audio_ms" and value == 0)):
                raise ValueError(f"{name} must be a finite {'positive' if name == 'audio_ms' else 'nonnegative'} number")
            event[name] = value
        chunk = record.get("chunk_id")
        if chunk is not None and not ((type(chunk) is int and chunk >= 0) or (isinstance(chunk, str) and chunk.strip())):
            raise ValueError("chunk_id must be a nonnegative integer or nonempty string")
        event["chunk_id"] = chunk
        return event

    def _summarize(self, events):
        groups, seen = defaultdict(list), set()
        for event in events:
            key = tuple(event[name] for name in self.DIMENSIONS)
            if event["chunk_id"] is not None:
                identity = (*key, event["chunk_id"])
                if identity in seen:
                    raise ValueError(f"{event.get('source', 'Performance data')}: duplicate chunk/stage event {identity}")
                seen.add(identity)
            groups[key].append(event)
        if not groups:
            raise ValueError("Performance input contains no events")
        summaries = []
        for key, records in sorted(groups.items()):
            elapsed = sorted(record["elapsed_ms"] for record in records)
            paired = [record for record in records if record["audio_ms"] is not None]
            audio_ms = sum(record["audio_ms"] for record in paired)
            paired_elapsed = sum(record["elapsed_ms"] for record in paired)
            backlog = [record["backlog_ms"] for record in records if record["backlog_ms"] is not None]
            summaries.append({
                **dict(zip(self.DIMENSIONS, key)), "event_count": len(records),
                "elapsed_ms": {"total": sum(elapsed), "mean": sum(elapsed) / len(elapsed),
                               "p50": elapsed[math.ceil(len(elapsed) * 0.5) - 1],
                               "p95": elapsed[math.ceil(len(elapsed) * 0.95) - 1], "max": elapsed[-1]},
                # Use only paired durations in both numerator and denominator.
                # Missing audio duration must not inflate or dilute the RTF.
                "rtf": paired_elapsed / audio_ms if paired else None,
                "rtf_event_count": len(paired), "rtf_elapsed_ms": paired_elapsed, "rtf_audio_ms": audio_ms,
                "backlog_ms": {"count": len(backlog), "mean": sum(backlog) / len(backlog),
                               "max": max(backlog), "last": backlog[-1]} if backlog else None,
                "chunks": [{"chunk_id": record["chunk_id"], "elapsed_ms": record["elapsed_ms"],
                            "audio_ms": record["audio_ms"], "backlog_ms": record["backlog_ms"],
                            "rtf": record["elapsed_ms"] / record["audio_ms"] if record["audio_ms"] is not None else None}
                           for record in records if record["chunk_id"] is not None],
            })
        return {"event_count": sum(len(records) for records in groups.values()), "groups": summaries}


class ScorerCli:
    # Strict defaults give --check a useful meaning without extra switches.
    # Thresholds apply to every candidate independently, never to an average.
    LIMITS = (
        ("max_wer", "text", "wer", 0.0, False),
        ("min_coverage", "text", "matched_reference_coverage", 1.0, True),
        ("min_speaker_coverage", "speakers", "correct_reference_word_rate", 1.0, True),
        ("max_unknown_rate", "speakers", "unknown_word_rate", 0.0, False),
        ("max_false_fragmentations", "turns", "false_fragmentations", 0, False),
        ("max_turn_error_rate", "turns", "sequence_error_rate", 0.0, False),
        ("max_tail_deletions", "text", "trailing_reference_deletions", 0, False),
    )

    @staticmethod
    def nonnegative(value):
        number = float(value)
        if not math.isfinite(number) or number < 0:
            raise argparse.ArgumentTypeError("must be finite and nonnegative")
        return number

    def parser(self):
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("fixture_dir", type=Path)
        parser.add_argument("candidates", type=Path, nargs="*", help="Text files or directories; default: fixture current-diarization files")
        parser.add_argument("--recording", help="Select one reference recording (also permits arbitrary candidate filenames)")
        parser.add_argument("--repeat-reference", type=int, default=1, metavar="N",
                            help="Repeat fixture references in memory for concatenated audio (1..100; default 1)")
        parser.add_argument("--plain-baseline", type=Path, metavar="PATH",
                            help="Same-build Off text file/directory for word parity only; never repeated or used to relax gold checks")
        parser.add_argument("--format", choices=("text", "json"), default="text")
        parser.add_argument("--performance-jsonl", type=Path, action="append", default=[],
                            help="Optional timing records; repeat for multiple files (schema in docs/qa/diarization-scoring.md)")
        parser.add_argument("--check", action="store_true", help="Exit 1 if any acceptance threshold fails (strict defaults)")
        for name, _, _, default, _ in self.LIMITS:
            parser.add_argument("--" + name.replace("_", "-"), type=self.nonnegative, default=default)
        return parser

    def run(self, argv=None):
        parser = self.parser()
        args = parser.parse_args(argv)
        for name in ("min_coverage", "min_speaker_coverage", "max_unknown_rate"):
            if getattr(args, name) > 1:
                parser.error(f"--{name.replace('_', '-')} must be between 0 and 1")
        for name in ("max_false_fragmentations", "max_tail_deletions"):
            if not float(getattr(args, name)).is_integer():
                parser.error(f"--{name.replace('_', '-')} must be an integer")
        try:
            reports = FixtureRunner().run(args.fixture_dir, args.candidates, args.recording,
                                          args.repeat_reference, args.plain_baseline)
            failures = []
            for report in reports:
                for name, section, metric, _, minimum in self.LIMITS:
                    actual, threshold = report[section][metric], getattr(args, name)
                    if actual is None or (actual < threshold if minimum else actual > threshold):
                        failures.append({"recording": report["recording"], "candidate": report["candidate"],
                                         "metric": metric, "actual": actual, "threshold": threshold,
                                         "comparison": ">=" if minimum else "<="})
            output = {
                "schema_version": 1, "metric_kind": "timestamp-free word and speaker evaluation; not DER",
                "recordings": reports,
                "performance": PerformanceSummary().read(args.performance_jsonl) if args.performance_jsonl else None,
                "acceptance": {"passed": not failures, "checked": args.check,
                               "thresholds": {name: getattr(args, name) for name, *_ in self.LIMITS}, "failures": failures},
            }
            if args.format == "json":
                print(json.dumps(output, ensure_ascii=True, indent=2, allow_nan=False))
            else:
                self.print_text(output)
            return int(args.check and bool(failures))
        except (OSError, ValueError) as error:
            if args.format == "json":
                print(json.dumps({"error": str(error)}, ensure_ascii=True))
            else:
                print(f"Error: {error}", file=sys.stderr)
            return 2

    def print_text(self, output):
        print(output["metric_kind"])
        for report in output["recordings"]:
            text, speakers, turns = report["text"], report["speakers"], report["turns"]
            print(f"\n{report['recording']} [{Path(report['candidate']).name}]")
            if report["reference_repetitions"] != 1:
                print(f"  Reference repetitions: {report['reference_repetitions']}")
            print(f"  Words: {text['candidate_words']}/{text['reference_words']}; WER {text['wer']:.2%} "
                  f"(S={text['substitutions']}, D={text['deletions']}, I={text['insertions']})")
            print(f"  Matched reference coverage: {text['matched_reference_coverage']:.2%}; "
                  f"trailing deletions: {text['trailing_reference_deletions']}")
            conditional = speakers["matched_word_accuracy"]
            print(f"  Correct-speaker reference coverage: {speakers['correct_reference_word_rate']:.2%}; "
                  f"speaker accuracy on matched words: {f'{conditional:.2%}' if conditional is not None else 'n/a'}")
            unknown_rate = speakers["unknown_word_rate"]
            print(f"  Blocks: {speakers['candidate_blocks']}, unknown blocks: {speakers['unknown_blocks']}; "
                  f"unknown words: {speakers['unknown_words']} ({f'{unknown_rate:.2%}' if unknown_rate is not None else 'n/a'})")
            print(f"  Turns: {turns['candidate_turn_count']}/{turns['reference_turn_count']}; "
                  f"false fragmentation: {turns['false_fragmentations']}; missed boundaries: {turns['missed_boundaries']}; "
                  f"turn sequence edits: {turns['sequence_edit_distance']}")
            print(f"  Recording-wide speaker mapping: {json.dumps(speakers['mapping'], sort_keys=True)}")
            paired = report["plain_baseline"]
            if paired is not None:
                words = paired["text"]
                print(f"  Plain-baseline word parity: {'identical' if paired['identical_normalized_words'] else 'different'}; "
                      f"WER {words['wer']:.2%}; matched baseline coverage {words['matched_reference_coverage']:.2%}; "
                      f"words {words['candidate_words']}/{words['reference_words']} [{paired['path']}]")
            for warning in report["warnings"]:
                print(f"  Note: {warning}")
        acceptance = output["acceptance"]
        print(f"\nAcceptance: {'PASS' if acceptance['passed'] else 'FAIL'} ({'enforced' if acceptance['checked'] else 'report only'})")
        for failure in acceptance["failures"]:
            print(f"  {Path(failure['candidate']).name}: {failure['metric']}={failure['actual']} "
                  f"requires {failure['comparison']} {failure['threshold']}")
        if output["performance"]:
            print("\nPerformance (separate recording/run/phase/stage groups):")
            for group in output["performance"]["groups"]:
                elapsed, rtf, backlog = group["elapsed_ms"], group["rtf"], group["backlog_ms"]
                print(f"  {group['recording']}/{group['run_id']}/{group['phase']}/{group['stage']}: "
                      f"{group['event_count']} events; total {elapsed['total']:g} ms; "
                      f"p95 {elapsed['p95']:g} ms; RTF {f'{rtf:.3f}' if rtf is not None else 'n/a'}; "
                      f"backlog max {str(backlog['max']) + ' ms' if backlog else 'n/a'}")


if __name__ == "__main__":
    sys.exit(ScorerCli().run())
