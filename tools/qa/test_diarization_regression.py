"""Synthetic, redistributable regression cases; never load private recordings."""
import importlib
import itertools
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("diarization_regression.py")
SIX_TURNS = "\n".join([
    "Speaker 1: alpha apple", "Speaker 2: bravo berry",
    "Speaker 1: charlie cherry", "Speaker 2: delta date",
    "Speaker 1: echo elderberry", "Speaker 2: foxtrot fig",
])


class ScorerTestCase(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "The diarization scorer has not been implemented")
        self.api = importlib.import_module("tools.qa.diarization_regression")

    def score(self, gold, candidate):
        parser = self.api.TranscriptParser()
        return self.api.DiarizationScorer().score(parser.parse(gold), parser.parse(candidate))


class TranscriptScoringTests(ScorerTestCase):
    def test_exact_one_speaker_and_six_returning_turns(self):
        for gold, turns in [("Speaker 1: Hello lovely world", 1), (SIX_TURNS, 6)]:
            with self.subTest(turns=turns):
                report = self.score(gold, gold)
                self.assertEqual(report["text"]["wer"], 0)
                self.assertEqual(report["text"]["matched_reference_coverage"], 1)
                self.assertEqual(report["speakers"]["correct_reference_word_rate"], 1)
                self.assertEqual(report["turns"]["candidate_turn_count"], turns)
                self.assertEqual(report["turns"]["sequence_error_rate"], 0)

    def test_unknown_fragmentation_does_not_change_words(self):
        report = self.score("Speaker 1: alpha beta gamma", "Speaker 8: alpha\nUnknown speaker: beta\nSpeaker 8: gamma")
        self.assertEqual(report["text"]["wer"], 0)
        self.assertEqual(report["speakers"]["candidate_blocks"], 3)
        self.assertEqual(report["speakers"]["unknown_blocks"], 1)
        self.assertAlmostEqual(report["speakers"]["unknown_word_rate"], 1 / 3)
        self.assertAlmostEqual(report["speakers"]["correct_reference_word_rate"], 2 / 3)
        self.assertEqual(report["turns"]["false_fragmentations"], 2)
        self.assertEqual(report["turns"]["sequence_edit_distance"], 2)

    def test_one_global_permutation_is_allowed(self):
        candidate = SIX_TURNS.replace("Speaker 1:", "Speaker 8:").replace("Speaker 2:", "Speaker 9:")
        report = self.score(SIX_TURNS, candidate)
        self.assertEqual(report["speakers"]["mapping"], {"8": "1", "9": "2"})
        self.assertEqual(report["speakers"]["correct_reference_word_rate"], 1)
        self.assertEqual(report["turns"]["sequence_error_rate"], 0)

    def test_returning_identity_swap_cannot_be_relabelled_per_turn(self):
        ids = [8, 9, 9, 8, 9, 8]
        candidate = "\n".join(f"Speaker {speaker}: {line.split(': ', 1)[1]}" for speaker, line in zip(ids, SIX_TURNS.splitlines()))
        report = self.score(SIX_TURNS, candidate)
        self.assertAlmostEqual(report["speakers"]["matched_word_accuracy"], 2 / 3)
        self.assertGreater(report["turns"]["sequence_edit_distance"], 0)
        self.assertEqual(report["turns"]["missed_boundaries"], 1)

    def test_mapping_is_optimal_not_greedy_or_many_to_one(self):
        # Contingency matrix [[9, 8], [8, 0]]: greedy gets 9, assignment gets 16.
        words = [f"word{i}" for i in range(25)]
        gold = f"Speaker 1: {' '.join(words[:9])}\nSpeaker 2: {' '.join(words[9:17])}\nSpeaker 1: {' '.join(words[17:])}"
        candidate = f"Speaker 8: {' '.join(words[:17])}\nSpeaker 9: {' '.join(words[17:])}"
        report = self.score(gold, candidate)
        self.assertEqual(report["speakers"]["correct_words"], 16)
        self.assertEqual(report["speakers"]["mapping"], {"8": "2", "9": "1"})
        split = self.score("Speaker 1: alpha beta gamma", "Speaker 1: alpha\nSpeaker 2: beta\nSpeaker 3: gamma")
        self.assertEqual(split["speakers"]["correct_words"], 1)
        self.assertEqual(sum(value is None for value in split["speakers"]["mapping"].values()), 2)

    def test_many_speakers_and_missing_reference_speakers(self):
        gold = "\n".join(f"Speaker {i}: token{i}" for i in range(12))
        candidate = "\n".join(f"Speaker {i + 20}: token{i}" for i in range(12))
        self.assertEqual(self.score(gold, candidate)["speakers"]["correct_words"], 12)
        short = self.score(gold, "Speaker 20: token0")
        self.assertEqual(short["speakers"]["mapping"], {"20": "0"})
        self.assertAlmostEqual(short["speakers"]["correct_reference_word_rate"], 1 / 12)

    def test_missing_tail_penalizes_full_reference_even_with_perfect_prefix(self):
        report = self.score("Speaker 1: alpha beta gamma delta", "Speaker 1: alpha beta")
        self.assertEqual(report["text"]["wer"], 0.5)
        self.assertEqual(report["text"]["matched_reference_coverage"], 0.5)
        self.assertEqual(report["text"]["trailing_reference_deletions"], 2)
        self.assertEqual(report["speakers"]["matched_word_accuracy"], 1)
        self.assertEqual(report["speakers"]["correct_reference_word_rate"], 0.5)

    def test_insert_delete_and_substitute_are_separate(self):
        report = self.score("Speaker 1: alpha beta gamma delta epsilon", "Speaker 1: extra alpha beta wrong epsilon bonus")
        text = report["text"]
        self.assertEqual(text["matches"], 3)
        self.assertEqual((text["substitutions"], text["deletions"], text["insertions"]), (1, 1, 2))
        self.assertEqual(text["wer"], 0.8)
        self.assertEqual(text["aligned_reference_coverage"], 0.8)
        self.assertEqual(report["speakers"]["correct_reference_word_rate"], 0.6)

    def test_punctuation_nfc_apostrophes_and_unicode_marks(self):
        gold = "Speaker 1: ÉTÉ, l’été! t'as dit: Prométhée — हिन्दी, Ελληνικά, 中文 8h15."
        candidate = "\ufeffSpeaker 7: e\u0301te\u0301 l'e\u0301te\u0301 t’as dit Prométhée हिन्दी Ελληνικά 中文 8h15"
        report = self.score(gold, candidate)
        self.assertEqual(report["text"]["wer"], 0)
        self.assertEqual(report["text"]["reference_words"], 9)
        self.assertEqual(self.score("Speaker 1: été", "Speaker 1: ete")["text"]["wer"], 1)

    def test_canonically_equivalent_greek_marks_match_before_casefold(self):
        # Case folding turns ypogegrammeni into a letter; canonical mark
        # reordering must happen first so the acute stays on the alpha.
        for decomposed in ("\u03b1\u0345\u0301", "\u03b1\u0301\u0345"):
            with self.subTest(decomposed=ascii(decomposed)):
                report = self.score("Speaker 1: \u1fb4", f"Speaker 7: {decomposed}")
                self.assertEqual(report["text"]["wer"], 0)
                self.assertEqual(report["text"]["matched_reference_coverage"], 1)
                self.assertEqual(report["speakers"]["correct_reference_word_rate"], 1)

    def test_wrapped_lines_and_punctuation_only_blocks(self):
        report = self.score("Speaker 1: alpha beta", "Speaker 01: alpha\nbeta\nUnknown speaker: ?")
        self.assertEqual(report["text"]["wer"], 0)
        self.assertEqual(report["speakers"]["candidate_blocks"], 2)
        self.assertEqual(report["speakers"]["unknown_blocks"], 1)
        self.assertEqual(report["speakers"]["unknown_words"], 0)
        self.assertEqual(report["turns"]["candidate_turn_count"], 1)

    def test_empty_candidate_is_a_total_omission_not_perfect_accuracy(self):
        report = self.score("Speaker 1: alpha beta", "")
        self.assertEqual(report["text"]["wer"], 1)
        self.assertEqual(report["text"]["trailing_reference_deletions"], 2)
        self.assertEqual(report["speakers"]["correct_reference_word_rate"], 0)
        self.assertIsNone(report["speakers"]["matched_word_accuracy"])
        self.assertIsNone(report["speakers"]["unknown_word_rate"])

    def test_unlabelled_candidate_is_unknown_and_never_a_known_speaker(self):
        report = self.score("Speaker 1: alpha beta", "alpha beta")
        self.assertEqual(report["text"]["wer"], 0)
        self.assertEqual(report["speakers"]["unknown_word_rate"], 1)
        self.assertEqual(report["speakers"]["correct_reference_word_rate"], 0)
        self.assertTrue(report["warnings"])

    def test_empty_unlabelled_or_unknown_gold_is_invalid(self):
        for gold in ["", "alpha", "Unknown speaker: alpha", "Speaker 1: ?"]:
            with self.subTest(gold=gold), self.assertRaises(ValueError):
                self.score(gold, "Speaker 1: alpha")

    def test_malformed_labels_fail_instead_of_becoming_transcript_words(self):
        for candidate in ["Speaker x: alpha", "Speaker -1: alpha", "Speaker 1 alpha", "Unknown speaker alpha", "Speaker 1:"]:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                self.score("Speaker 1: alpha", candidate)

    def test_alignment_is_label_blind_and_repeated_prefix_stays_a_prefix(self):
        report = self.score("Speaker 1: yes yes\nSpeaker 2: yes yes", "Speaker 2: yes yes")
        self.assertEqual(report["text"]["trailing_reference_deletions"], 2)
        self.assertEqual(report["speakers"]["mapping"], {"2": "1"})

    def test_exact_alignment_matches_independent_short_sequence_oracle(self):
        # Exhaustive small edit graphs catch traceback errors that WER-only examples miss.
        sequences = [list(seq) for size in range(4) for seq in itertools.product("ab", repeat=size)]
        for reference in sequences:
            for candidate in sequences:
                with self.subTest(reference=reference, candidate=candidate):
                    alignment = self.api.WordAligner().align(reference, candidate)
                    matrix = [[0] * (len(candidate) + 1) for _ in range(len(reference) + 1)]
                    for i in range(len(reference) + 1):
                        matrix[i][0] = i
                    for j in range(len(candidate) + 1):
                        matrix[0][j] = j
                    for i, left in enumerate(reference, 1):
                        for j, right in enumerate(candidate, 1):
                            matrix[i][j] = min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1, matrix[i - 1][j - 1] + (left != right))
                    self.assertEqual([i for i, _ in alignment if i is not None], list(range(len(reference))))
                    self.assertEqual([j for _, j in alignment if j is not None], list(range(len(candidate))))
                    cost = sum(i is None or j is None or reference[i] != candidate[j] for i, j in alignment)
                    self.assertEqual(cost, matrix[-1][-1])


class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "The diarization scorer has not been implemented")
        # Keep even disposable test artifacts inside this worktree.
        self.temp = tempfile.TemporaryDirectory(dir=SCRIPT.parent)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.set_reference(SIX_TURNS)
        self.write("sample_transcript_current-diarization.txt", SIX_TURNS)

    def set_reference(self, text):
        self.write("sample_transcript_true-diarization.txt", text)
        self.write("sample_transcript_no-diarization.txt", " ".join(line.split(": ", 1)[1] for line in text.splitlines()))

    def write(self, name, text):
        path = self.directory / name
        path.write_text(text, encoding="utf-8")
        return path

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.directory), *map(str, args)], capture_output=True, text=True, encoding="utf-8", check=False)

    def test_default_json_and_text_reports(self):
        result = self.run_cli("--format", "json", "--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report["acceptance"]["passed"])
        self.assertEqual(report["recordings"][0]["no_diarization_text"]["wer"], 0)
        self.assertNotIn("DER", report)
        text = self.run_cli()
        self.assertEqual(text.returncode, 0, text.stderr)
        self.assertIn("WER", text.stdout)
        self.assertIn("coverage", text.stdout)

    def test_repeat_reference_keeps_six_turns_per_copy_and_fixture_files_unchanged(self):
        self.write("sample_transcript_current-diarization.txt", "\n".join([SIX_TURNS] * 2))
        result = self.run_cli("--repeat-reference", "2", "--format", "json", "--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)["recordings"][0]
        self.assertEqual(report["reference_repetitions"], 2)
        self.assertEqual(report["text"]["reference_words"], 24)
        self.assertEqual(report["text"]["wer"], 0)
        self.assertEqual(report["turns"]["reference_turn_count"], 12)
        self.assertEqual(report["turns"]["candidate_turn_count"], 12)
        self.assertEqual(report["speakers"]["mapping"], {"1": "1", "2": "2"})
        self.assertEqual(report["no_diarization_text"]["reference_words"], 24)
        self.assertEqual(report["no_diarization_text"]["candidate_words"], 24)
        self.assertEqual(report["no_diarization_text"]["wer"], 0)
        self.assertEqual((self.directory / "sample_transcript_true-diarization.txt").read_text(encoding="utf-8"), SIX_TURNS)
        text = self.run_cli("--repeat-reference", "2")
        self.assertEqual(text.returncode, 0, text.stderr)
        self.assertIn("Reference repetitions: 2", text.stdout)

    def test_repeat_reference_collapses_same_speaker_seams(self):
        for gold, speakers, turns in [("Speaker 7: alpha beta", 1, 1),
                                      ("Speaker 7: alpha\nSpeaker 9: beta\nSpeaker 7: gamma", 2, 5)]:
            with self.subTest(speakers=speakers):
                self.set_reference(gold)
                self.write("sample_transcript_current-diarization.txt", f"{gold}\n{gold}")
                result = self.run_cli("--repeat-reference", "2", "--format", "json", "--check")
                self.assertEqual(result.returncode, 0, result.stderr)
                report = json.loads(result.stdout)["recordings"][0]
                self.assertEqual(report["speakers"]["reference_speakers"], speakers)
                self.assertEqual(report["speakers"]["correct_reference_word_rate"], 1)
                self.assertEqual(report["turns"]["reference_turn_count"], turns)
                self.assertEqual(report["turns"]["candidate_turn_count"], turns)

    def test_repeat_reference_does_not_forgive_identity_swaps_between_copies(self):
        swapped = SIX_TURNS.replace("Speaker 1:", "Speaker 9:").replace("Speaker 2:", "Speaker 1:").replace("Speaker 9:", "Speaker 2:")
        self.write("sample_transcript_current-diarization.txt", f"{SIX_TURNS}\n{swapped}")
        result = self.run_cli("--repeat-reference", "2", "--format", "json", "--check")
        self.assertEqual(result.returncode, 1, result.stderr)
        report = json.loads(result.stdout)["recordings"][0]
        self.assertEqual(report["text"]["wer"], 0)
        self.assertEqual(report["speakers"]["matched_word_accuracy"], 0.5)
        self.assertEqual(report["speakers"]["correct_reference_word_rate"], 0.5)

    def test_repeat_reference_accepts_only_integer_counts_one_through_one_hundred(self):
        self.set_reference("Speaker 1: alpha")
        for count in (1, 100):
            with self.subTest(count=count):
                self.write("sample_transcript_current-diarization.txt", "\n".join(["Speaker 1: alpha"] * count))
                result = self.run_cli("--repeat-reference", count, "--format", "json", "--check")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout)["recordings"][0]["text"]["reference_words"], count)
        for count in (0, -1, 101, "1.5", "nan", "invalid"):
            with self.subTest(count=count):
                result = self.run_cli("--repeat-reference", count, "--format", "json")
                self.assertEqual(result.returncode, 2)
                self.assertIn("repeat", result.stdout + result.stderr)

    def test_plain_baseline_shared_asr_error_has_parity_but_still_fails_gold_check(self):
        self.set_reference("Speaker 1: alpha beta gamma")
        self.write("sample_transcript_current-diarization.txt", "Speaker 7: alpha wrong gamma")
        baseline = self.write("off.txt", "ALPHA, wrong gamma!")
        result = self.run_cli("--plain-baseline", baseline, "--format", "json", "--check")
        self.assertEqual(result.returncode, 1, result.stderr)
        output = json.loads(result.stdout)
        report = output["recordings"][0]
        self.assertAlmostEqual(report["text"]["wer"], 1 / 3)
        self.assertEqual(report["speakers"]["matched_word_accuracy"], 1)
        paired = report["plain_baseline"]
        self.assertEqual(paired["path"], str(baseline))
        self.assertTrue(paired["identical_normalized_words"])
        self.assertEqual(paired["text"]["wer"], 0)
        self.assertEqual(paired["text"]["matched_reference_coverage"], 1)
        self.assertNotIn("speakers", paired)
        self.assertFalse(output["acceptance"]["passed"])
        self.assertTrue(any(failure["metric"] == "wer" for failure in output["acceptance"]["failures"]))
        text = self.run_cli("--plain-baseline", baseline)
        self.assertEqual(text.returncode, 0, text.stderr)
        self.assertIn("Plain-baseline word parity: identical", text.stdout)
        self.assertIn("WER 0.00%", text.stdout)

    def test_plain_baseline_detects_candidate_word_regression(self):
        self.set_reference("Speaker 1: alpha beta gamma")
        self.write("sample_transcript_current-diarization.txt", "Speaker 7: alpha beta")
        baseline = self.write("off.txt", "alpha beta gamma")
        result = self.run_cli("--plain-baseline", baseline, "--format", "json")
        self.assertEqual(result.returncode, 0, result.stderr)
        paired = json.loads(result.stdout)["recordings"][0]["plain_baseline"]
        self.assertFalse(paired["identical_normalized_words"])
        self.assertAlmostEqual(paired["text"]["wer"], 1 / 3)
        self.assertAlmostEqual(paired["text"]["matched_reference_coverage"], 2 / 3)
        self.assertEqual(paired["text"]["trailing_reference_deletions"], 1)

    def test_plain_baseline_differences_do_not_replace_gold_acceptance(self):
        baseline = self.write("off.txt", "different words")
        result = self.run_cli("--plain-baseline", baseline, "--format", "json", "--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        output = json.loads(result.stdout)
        self.assertTrue(output["acceptance"]["passed"])
        self.assertFalse(output["recordings"][0]["plain_baseline"]["identical_normalized_words"])

    def test_plain_baseline_is_never_auto_repeated(self):
        self.set_reference("Speaker 1: alpha beta")
        # A continuous ASR run may produce different words in its second copy.
        self.write("sample_transcript_current-diarization.txt", "Speaker 1: alpha beta alpha delta")
        baseline = self.write("off.txt", "alpha beta alpha delta")
        result = self.run_cli("--repeat-reference", "2", "--plain-baseline", baseline, "--format", "json")
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)["recordings"][0]
        self.assertEqual(report["text"]["wer"], 0.25)
        self.assertEqual(report["plain_baseline"]["text"]["reference_words"], 4)
        self.assertEqual(report["plain_baseline"]["text"]["wer"], 0)
        self.assertTrue(report["plain_baseline"]["identical_normalized_words"])
        self.write("off.txt", "alpha beta")
        result = self.run_cli("--repeat-reference", "2", "--plain-baseline", baseline, "--format", "json")
        self.assertEqual(result.returncode, 0, result.stderr)
        paired = json.loads(result.stdout)["recordings"][0]["plain_baseline"]
        self.assertEqual(paired["text"]["reference_words"], 2)
        self.assertEqual(paired["text"]["insertions"], 2)
        self.assertFalse(paired["identical_normalized_words"])

    def test_plain_baseline_directory_pairs_each_recording(self):
        self.write("other_transcript_true-diarization.txt", "Speaker 1: hello")
        self.write("other_transcript_current-diarization.txt", "Speaker 1: hello")
        directory = self.directory / "off"
        directory.mkdir()
        self.write("off/sample_transcript_no-diarization.txt", " ".join(line.split(": ", 1)[1] for line in SIX_TURNS.splitlines()))
        self.write("off/other.txt", "hello")
        result = self.run_cli("--plain-baseline", directory, "--format", "json", "--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        reports = json.loads(result.stdout)["recordings"]
        self.assertEqual(len(reports), 2)
        self.assertTrue(all(report["plain_baseline"]["identical_normalized_words"] for report in reports))
        self.assertEqual([report["plain_baseline"]["text"]["reference_words"] for report in reports], [1, 12])

    def test_plain_baseline_missing_empty_and_ambiguous_inputs_are_errors(self):
        missing = self.directory / "missing.txt"
        result = self.run_cli("--plain-baseline", missing, "--format", "json")
        self.assertEqual(result.returncode, 2)
        self.assertIn("missing.txt", result.stdout)
        empty = self.write("off.txt", "")
        result = self.run_cli("--plain-baseline", empty, "--format", "json")
        self.assertEqual(result.returncode, 2)
        self.assertIn("contain words", result.stdout)
        directory = self.directory / "off"
        directory.mkdir()
        result = self.run_cli("--plain-baseline", directory, "--format", "json")
        self.assertEqual(result.returncode, 2)
        self.assertIn("Missing plain baseline", result.stdout)
        self.write("off/sample.txt", "alpha")
        self.write("off/sample_transcript_no-diarization.txt", "beta")
        result = self.run_cli("--plain-baseline", directory, "--format", "json")
        self.assertEqual(result.returncode, 2)
        self.assertIn("Ambiguous plain baseline", result.stdout)

    def test_plain_baseline_file_cannot_be_shared_across_different_recordings(self):
        self.write("other_transcript_true-diarization.txt", "Speaker 1: hello")
        other = self.write("other_transcript_current-diarization.txt", "Speaker 1: hello")
        result = self.run_cli("--plain-baseline", other, "--format", "json")
        self.assertEqual(result.returncode, 2)
        self.assertIn("one recording", result.stdout)
        result = self.run_cli("--recording", "sample", "--plain-baseline", other, "--format", "json")
        self.assertEqual(result.returncode, 2)
        self.assertIn("other", result.stdout)

    def test_check_rejects_truncated_prefix_and_can_be_configured(self):
        self.write("sample_transcript_current-diarization.txt", SIX_TURNS.splitlines()[0])
        report_only = self.run_cli("--format", "json")
        self.assertEqual(report_only.returncode, 0, report_only.stderr)
        checked = self.run_cli("--format", "json", "--check")
        self.assertEqual(checked.returncode, 1, checked.stderr)
        self.assertTrue(json.loads(checked.stdout)["acceptance"]["failures"])
        tolerant = self.run_cli("--check", "--max-wer", "1", "--min-coverage", "0", "--min-speaker-coverage", "0", "--max-tail-deletions", "20", "--max-turn-error-rate", "1")
        self.assertEqual(tolerant.returncode, 0, tolerant.stderr)

    def test_explicit_candidate_files_and_directory(self):
        good = self.write("sample_transcript_good.txt", SIX_TURNS)
        bad = self.write("sample_transcript_bad.txt", "")
        result = self.run_cli(good, bad, "--format", "json", "--check")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(json.loads(result.stdout)["recordings"]), 2)
        result = self.run_cli(self.directory, "--format", "json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(json.loads(result.stdout)["recordings"]), 3)

    def test_invalid_inputs_fail_with_actionable_json_error(self):
        for content in ["Speaker nope: alpha", "Speaker 1:"]:
            self.write("sample_transcript_current-diarization.txt", content)
            result = self.run_cli("--format", "json")
            self.assertEqual(result.returncode, 2)
            self.assertIn("error", json.loads(result.stdout))
        invalid = self.run_cli("--max-wer", "nan")
        self.assertEqual(invalid.returncode, 2)

    def test_missing_candidate_never_silently_skips_a_recording(self):
        self.write("other_transcript_true-diarization.txt", "Speaker 1: hello")
        result = self.run_cli("--format", "json")
        self.assertEqual(result.returncode, 2)
        self.assertIn("other", json.loads(result.stdout)["error"])
        result = self.run_cli(self.directory, "--format", "json")
        self.assertEqual(result.returncode, 2)

    def test_recording_selection_filters_directory_without_reassigning_other_recordings(self):
        self.write("other_transcript_true-diarization.txt", "Speaker 1: hello")
        self.write("other_transcript_current-diarization.txt", "Speaker 1: hello")
        result = self.run_cli(self.directory, "--recording", "sample", "--format", "json", "--check")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(json.loads(result.stdout)["recordings"]), 1)

    def test_explicit_known_recording_cannot_be_reassigned_by_selection(self):
        self.write("other_transcript_true-diarization.txt", "Speaker 1: hello")
        candidate = self.write("other_transcript_current-diarization.txt", "Speaker 1: hello")
        result = self.run_cli(candidate, "--recording", "sample", "--format", "json")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("other", json.loads(result.stdout)["error"])

    def test_directory_requires_identifiable_names_but_explicit_files_can_be_arbitrary(self):
        arbitrary = self.write("run.txt", SIX_TURNS)
        result = self.run_cli(self.directory, "--format", "json")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("run.txt", json.loads(result.stdout)["error"])
        result = self.run_cli(arbitrary, "--format", "json", "--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.write("other_transcript_true-diarization.txt", "Speaker 1: hello")
        result = self.run_cli(arbitrary, "--recording", "sample", "--format", "json", "--check")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_performance_jsonl_files_are_optional_and_composable(self):
        cold = self.write("cold.jsonl", json.dumps({"stage": "load", "phase": "cold", "elapsed_ms": 3000}) + "\n")
        warm = self.write("warm.jsonl", "\n" + json.dumps({"stage": "chunk", "phase": "warm", "chunk_id": 1,
                                                       "elapsed_ms": 200, "audio_ms": 1000, "extra": "ignored"}) + "\n")
        result = self.run_cli("--performance-jsonl", cold, "--performance-jsonl", warm, "--format", "json")
        self.assertEqual(result.returncode, 0, result.stderr)
        performance = json.loads(result.stdout)["performance"]
        self.assertEqual(performance["event_count"], 2)
        self.assertEqual(len(performance["groups"]), 2)
        result = self.run_cli("--performance-jsonl", warm)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("RTF", result.stdout)
        self.assertIn("warm", result.stdout)

    def test_performance_jsonl_errors_include_file_and_line(self):
        path = self.write("invalid.jsonl", '{"elapsed_ms": 1}\n{"elapsed_ms": -1}\n')
        result = self.run_cli("--performance-jsonl", path, "--format", "json")
        self.assertEqual(result.returncode, 2)
        # Missing option support must fail this test too, not just return 2.
        self.assertTrue(result.stdout, result.stderr)
        self.assertIn("invalid.jsonl:2", json.loads(result.stdout)["error"])
        for content in ["{broken", "[]", "\n"]:
            self.write("invalid.jsonl", content)
            result = self.run_cli("--performance-jsonl", path, "--format", "json")
            self.assertEqual(result.returncode, 2)
            self.assertIn("error", json.loads(result.stdout))


class PerformanceTests(ScorerTestCase):
    def summary(self, records):
        self.assertTrue(hasattr(self.api, "PerformanceSummary"), "Performance summaries are not implemented")
        return self.api.PerformanceSummary().summarize(records)

    def test_cold_and_warm_are_separate_with_duration_weighted_rtf(self):
        records = [
            {"phase": "cold", "chunk_id": 0, "elapsed_ms": 4000, "audio_ms": 1000, "backlog_ms": 3000},
            {"phase": "warm", "chunk_id": 1, "elapsed_ms": 100, "audio_ms": 1000, "backlog_ms": 100},
            {"phase": "warm", "chunk_id": 2, "elapsed_ms": 600, "audio_ms": 3000, "backlog_ms": 0},
        ]
        report = self.summary(records)
        groups = {group["phase"]: group for group in report["groups"]}
        self.assertEqual(report["event_count"], 3)
        self.assertEqual(groups["cold"]["rtf"], 4)
        warm = groups["warm"]
        self.assertAlmostEqual(warm["rtf"], 0.175)
        self.assertEqual(warm["elapsed_ms"], {"total": 700, "mean": 350, "p50": 100, "p95": 600, "max": 600})
        self.assertEqual(warm["backlog_ms"], {"count": 2, "mean": 50, "max": 100, "last": 0})
        self.assertEqual([chunk["rtf"] for chunk in warm["chunks"]], [0.1, 0.2])

    def test_partial_audio_durations_do_not_bias_rtf_or_imply_warm(self):
        report = self.summary([{"elapsed_ms": 900}, {"elapsed_ms": 100, "audio_ms": 1000}])
        group = report["groups"][0]
        self.assertEqual(group["phase"], "unspecified")
        self.assertEqual(group["elapsed_ms"]["total"], 1000)
        self.assertEqual(group["rtf"], 0.1)
        self.assertEqual(group["rtf_event_count"], 1)
        self.assertIsNone(group["backlog_ms"])
        self.assertIsNone(self.summary([{"elapsed_ms": 100}])["groups"][0]["rtf"])

    def test_stages_recordings_and_runs_are_not_summed_together(self):
        records = [{"recording": recording, "run_id": run, "stage": stage, "chunk_id": 0, "elapsed_ms": 100}
                   for recording, run, stage in itertools.product(["one", "two"], ["run1", "run2"], ["asr", "speaker"])]
        report = self.summary(records)
        self.assertEqual(len(report["groups"]), 8)
        self.assertTrue(all(group["elapsed_ms"]["total"] == 100 for group in report["groups"]))

    def test_invalid_performance_data_is_not_silently_averaged(self):
        invalid = [[], {}, {"elapsed_ms": -1}, {"elapsed_ms": float("nan")}, {"elapsed_ms": float("inf")},
                   {"elapsed_ms": True}, {"elapsed_ms": "100"}, {"elapsed_ms": 1, "audio_ms": 0},
                   {"elapsed_ms": 1, "backlog_ms": -1}, {"elapsed_ms": 1, "phase": "hot"},
                   {"elapsed_ms": 1, "stage": ""}, {"elapsed_ms": 1, "chunk_id": False}]
        for record in invalid:
            with self.subTest(record=record), self.assertRaises(ValueError):
                self.summary([record])
        with self.assertRaises(ValueError):
            self.summary([])

    def test_duplicate_chunk_stage_is_rejected(self):
        event = {"chunk_id": 0, "elapsed_ms": 100, "audio_ms": 1000}
        with self.assertRaisesRegex(ValueError, "[Dd]uplicate"):
            self.summary([event, event])


if __name__ == "__main__":
    unittest.main()
