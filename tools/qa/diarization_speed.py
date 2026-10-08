"""Run one explicitly targeted acoustic benchmark and retain its local evidence.

Each invocation starts a fresh instrumentation process, important for native
environment options cached on first use. Run configurations serially, with unique
tags. Outputs can contain private speech and belong in ignored qa-artifacts.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import subprocess

from diarization_regression import FixtureRunner


class DiarizationSpeedRun:
    def __init__(self, args):
        self.args = args
        self.directory = args.output / args.tag
        self.adb = [args.adb, "-s", args.serial]

    def execute(self):
        # Refuse to overwrite an earlier observation, even after a failed run.
        self.directory.mkdir(parents=True, exist_ok=False)
        arguments = {
            "class": "io.github.lrq3000.utterlane.DiarizationFixtureAndroidTest#replay",
            "fixture": self.args.fixture, "tag": self.args.tag,
            "repeats": str(self.args.repeats), "speakers": "0",
            "native_attention": self.args.attention,
            "diarization": str(self.args.diarization == "on").lower(),
            "realtime": str(self.args.realtime).lower(),
            "asr_decoder": self.args.asr_decoder,
            "asr_projection": self.args.asr_projection,
            "native_probe": str(self.args.native_probe).lower(),
        }
        if self.args.asr_source_tag:
            arguments["asr_source_tag"] = self.args.asr_source_tag
        for option in self.args.option:
            key, separator, value = option.partition("=")
            if not separator or not re.fullmatch(r"[a-z_]+", key) or not re.fullmatch(r"[a-zA-Z0-9_.-]+", value):
                raise ValueError("Options must be runtime_key=value")
            arguments["option_" + key] = value
        command = self.adb + ["shell", "am", "instrument", "-w"]
        for key, value in arguments.items():
            command += ["-e", key, value]
        command += [self.args.package + ".test/androidx.test.runner.AndroidJUnitRunner"]
        self.save("invocation.json", {
            "started_utc": datetime.now(timezone.utc).isoformat(), "command": command,
            "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        })
        print(f"Running {self.args.tag} on {self.args.serial}", flush=True)
        result = subprocess.run(command, capture_output=True, text=True, timeout=self.args.timeout)
        log = result.stdout + result.stderr
        (self.directory / "instrumentation.txt").write_text(log, encoding="utf-8")
        if result.returncode or not re.search(r"OK \(1 test\)", log):
            raise RuntimeError(f"Instrumentation failed; see {self.directory / 'instrumentation.txt'}")
        remote = f"/sdcard/Android/data/{self.args.package}/files/diarization-runs/{self.args.tag}"
        subprocess.run(self.adb + ["pull", remote, str(self.args.output)], check=True, capture_output=True)
        report = self.summarize()
        self.save("analysis.json", report)
        # Full evidence is always saved. The optional compact view keeps long
        # parameter sweeps readable without dumping every unchanged preference.
        displayed = report
        if self.args.compact:
            displayed = {key: report[key] for key in (
                "tag", "diarization", "asr_decoder", "elapsed_ms", "first_text_ms", "asr_audio_seconds",
                "stage_totals_ms", "chunk_count", "chunk_p95_ms")}
            displayed["word_errors"] = report["text"]
            if report["capture"]:
                displayed["capture"] = {key: value for key, value in report["capture"].items() if key != "backlog_trace"}
            if report["diarization"]:
                displayed["speakers"] = report["speakers"]
        print(json.dumps(displayed, ensure_ascii=True, allow_nan=False), flush=True)

    def save(self, name, value):
        (self.directory / name).write_text(json.dumps(value, indent=2, ensure_ascii=True, allow_nan=False) + "\n", encoding="utf-8")

    def summarize(self):
        name = self.args.fixture
        summary = json.loads((self.directory / f"{name}.summary.json").read_text(encoding="utf-8"))
        events = [json.loads(line) for line in (self.directory / f"{name}.performance.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        totals = {}
        for event in events:
            totals[event["stage"]] = totals.get(event["stage"], 0) + event["elapsed_ms"]
        chunks = [event for event in events if event["stage"] == "chunk"]
        # Compare identical audio ranges across cache configurations, rather than
        # the runner's option-dependent warm flag. A final-window EOF drain is
        # included in its chunk time; this is not a drain-exclusive RTF.
        warm = [event for event in chunks if event["audio_end_ms"] - event["audio_ms"] >= 60000]
        quality = FixtureRunner().run(
            self.args.references,
            [self.directory / f"{name}_transcript_{self.args.tag}.txt"], name,
            self.args.repeats, self.args.plain_baseline,
        )[0]
        elapsed = sorted(event["elapsed_ms"] for event in chunks)
        return {
            "tag": self.args.tag, "fixture": name, "repeats": self.args.repeats,
            "audio_seconds": summary["samples"] / 16000,
            "options": summary["options"], "native_attention": summary.get("native_attention", "default"),
            "asr_source_tag": summary.get("asr_source_tag"),
            "diarization": summary["diarization"],
            "asr_decoder": summary.get("asr_decoder", "default"),
            "asr_projection": summary.get("asr_projection", "default"),
            "realtime": summary.get("realtime", False), "capture": summary.get("capture"),
            "first_text_ms": summary.get("first_text_ms"),
            "first_text_with_setup_ms": summary.get("first_text_with_setup_ms"),
            "last_text_ms": summary.get("last_text_ms"), "model_setup_ms": summary.get("model_setup_ms"),
            "asr_audio_seconds": sum(event.get("audio_ms", 0) for event in events if event["stage"] == "asr") / 1000,
            "elapsed_ms": summary["elapsed_ms"], "stage_totals_ms": totals,
            "native_forwards": summary["native_forwards"], "native_stage_ms": summary.get("native_stage_ms"),
            "chunk_count": len(chunks), "chunk_p50_ms": elapsed[math.ceil(len(elapsed) * .5) - 1],
            "chunk_p95_ms": elapsed[math.ceil(len(elapsed) * .95) - 1],
            "warm_chunk_count": len(warm),
            "warm_rtf": sum(event["elapsed_ms"] for event in warm) / sum(event["audio_ms"] for event in warm) if warm else None,
            "text": quality["text"], "speakers": quality["speakers"], "turns": quality["turns"],
            "plain_baseline": quality["plain_baseline"],
        }


class DiarizationSpeedReport:
    """Publish numeric evidence without copying transcripts or posterior arrays."""
    def __init__(self, source, destination):
        self.source = source
        self.destination = destination

    def execute(self):
        observations = []
        for path in self.source.glob("*/analysis.json"):
            report = json.loads(path.read_text(encoding="utf-8"))
            invocation = json.loads((path.parent / "invocation.json").read_text(encoding="utf-8"))
            observations.append((invocation["started_utc"], report))
        if not observations:
            raise ValueError("No completed benchmark observations found")
        rows = []
        for _, report in sorted(observations, key=lambda item: item[0]):
            options = report["options"]
            diarization = report.get("diarization", True)
            capture = report.get("capture") or {}
            rows.append({
                "tag": report["tag"], "fixture": report["fixture"], "repeats": report["repeats"],
                "diarization": diarization,
                "asr_decoder": report.get("asr_decoder", "default"),
                "asr_projection": report.get("asr_projection", "default"),
                "realtime": report.get("realtime", False),
                "capture_ms": capture.get("capture_elapsed_ms"),
                "backlog_at_stop_audio_ms": capture.get("backlog_at_stop_audio_ms"),
                "catchup_after_stop_ms": capture.get("catchup_after_stop_ms"),
                "max_backlog_audio_ms": capture.get("max_backlog_audio_ms"),
                "max_capture_lateness_ms": capture.get("max_capture_lateness_ms"),
                "asr_source_tag": report.get("asr_source_tag"),
                "asr_threads": options["asr_threads"], "window_s": options["asr_window_seconds"],
                "minimum_s": options["asr_min_seconds"], "left_s": options["asr_left_context_seconds"],
                "right_s": options["asr_right_context_seconds"], "silence_ms": options["silence_duration_ms"],
                "asr_audio_s": report.get("asr_audio_seconds"),
                "first_text_ms": report.get("first_text_ms"),
                "first_text_with_setup_ms": report.get("first_text_with_setup_ms"),
                "batch": options["diarization_batch"], "threads": options["diarization_threads"],
                "cache": options["native_cache_frames"], "fifo": options["native_fifo_frames"],
                "update": options["native_update_frames"], "attention": report["native_attention"],
                "audio_s": report["audio_seconds"], "elapsed_s": report["elapsed_ms"] / 1000,
                "speaker_s": report["stage_totals_ms"].get("speaker", 0) / 1000,
                "asr_s": report["stage_totals_ms"].get("asr", 0) / 1000,
                "native_forwards": report["native_forwards"],
                "chunk_p50_s": report["chunk_p50_ms"] / 1000,
                "chunk_p95_s": report["chunk_p95_ms"] / 1000,
                "warm_rtf": report["warm_rtf"],
                # Plain ASR intentionally has no speaker labels; reporting its
                # unlabelled words as diarization failures would be misleading.
                "turns": report["turns"]["candidate_turn_count"] if diarization else None,
                "unknown_words": report["speakers"]["unknown_words"] if diarization else None,
                "matched_speaker_accuracy": report["speakers"]["matched_word_accuracy"] if diarization else None,
                "wer": report["text"]["wer"],
                "tail_deletions": report["text"]["trailing_reference_deletions"],
            })
        # The caller chooses the tracked destination explicitly; private run
        # artifacts remain in the ignored source directory.
        with self.destination.open("w", encoding="utf-8", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        print(f"Exported {len(rows)} observations to {self.destination}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="io.github.lrq3000.utterlane.diarspeed")
    parser.add_argument("--tag")
    parser.add_argument("--fixture", choices=("test-1-speaker-french", "test-2-speakers-french-3-turns"), default="test-2-speakers-french-3-turns")
    parser.add_argument("--repeats", type=int, choices=range(1, 9), default=1)
    parser.add_argument("--attention", choices=("default", "flash", "manual"), default="default")
    parser.add_argument("--diarization", choices=("on", "off"), default="on")
    parser.add_argument("--asr-decoder", choices=("default", "ggml", "scalar"), default="default", help="QA-only native decoder override; not a current app setting")
    parser.add_argument("--asr-projection", choices=("default", "backend"), default="default", help="QA-only encoder projection override, requiring the ggml decoder")
    parser.add_argument("--native-probe", action="store_true", help="Capture native stage counters in the local, private run artifacts")
    parser.add_argument("--realtime", action="store_true", help="Pace independent capture through RecordingPipeline; one fixture, at most 30 seconds")
    parser.add_argument("--compact", action="store_true", help="Print headline metrics; retain the full local JSON report")
    parser.add_argument("--asr-source-tag", help="Reuse PCM-checked ASR evidence; elapsed time then excludes neural ASR")
    parser.add_argument("--option", action="append", default=[])
    parser.add_argument("--output", type=Path, default=Path("qa-artifacts/diarization-speed"))
    parser.add_argument("--report", type=Path, help="Export completed local observations as a content-free CSV; no ADB action")
    parser.add_argument("--references", type=Path, default=Path("test_material/streaming_diarization_accuracy"))
    parser.add_argument("--plain-baseline", type=Path)
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()
    if args.report:
        DiarizationSpeedReport(args.output, args.report).execute()
        return
    if not args.serial or not args.tag:
        parser.error("--serial and --tag are required for a benchmark run")
    if args.diarization == "off" and args.asr_source_tag:
        parser.error("ASR-only benchmarks must run neural ASR rather than replay its evidence")
    if args.realtime and (args.repeats != 1 or args.asr_source_tag):
        parser.error("Real-time trials require one fresh recording, without ASR evidence replay")
    if args.asr_projection == "backend" and args.asr_decoder != "ggml":
        parser.error("Backend projection requires --asr-decoder ggml")
    for tag in (args.tag, args.asr_source_tag):
        if tag is not None and not re.fullmatch(r"[a-zA-Z0-9_-]+", tag):
            parser.error("Run/source tags must contain only letters, digits, underscores or hyphens")
    for value in (args.tag, args.package, args.serial):
        if not re.fullmatch(r"[a-zA-Z0-9_.:-]+", value):
            parser.error("Tag, package, and serial must be safe identifiers")
    if args.timeout <= 0:
        parser.error("Timeout must be positive")
    DiarizationSpeedRun(args).execute()


if __name__ == "__main__":
    main()
