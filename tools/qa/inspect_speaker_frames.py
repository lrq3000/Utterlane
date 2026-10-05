"""Inspect bounded intervals of local eight-channel float32 posterior evidence."""
import argparse
import json
import math
import pathlib
import struct


class SpeakerFrames:
    def __init__(self, path):
        self.path = pathlib.Path(path)
        if self.path.stat().st_size % 32:
            raise ValueError("Expected eight little-endian float32 values per 10 ms frame")

    def summarize(self, start, end, step_ms=50):
        if not (math.isfinite(start) and math.isfinite(end) and 0 <= start < end <= start + 30):
            raise ValueError("Choose a finite interval of at most 30 seconds")
        if step_ms < 10 or step_ms % 10:
            raise ValueError("Summary bins must be positive multiples of 10 ms")
        # Decimal-looking CLI inputs such as 4.1 may multiply to 409.999999…;
        # do not report the previous 10 ms frame because of floating rounding.
        first, stop = math.floor(start * 100 + 1e-9), math.ceil(end * 100 - 1e-9)
        with self.path.open("rb") as source:
            source.seek(first * 32)
            for frame in range(first, stop, step_ms // 10):
                raw = source.read(min(step_ms // 10, stop - frame) * 32)
                if not raw:
                    break
                rows = list(struct.iter_unpack("<8f", raw))
                if any(not math.isfinite(p) or not 0 <= p <= 1 for row in rows for p in row):
                    raise ValueError("Invalid probability")
                means = [sum(row[i] for row in rows) / len(rows) for i in range(8)]
                yield {"start_s": frame / 100, "mean": [round(p, 4) for p in means],
                       "winner": max(range(8), key=means.__getitem__),
                       "voiced_ms": sum(max(row) > .5 for row in rows) * 10}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=pathlib.Path)
    parser.add_argument("start", type=float)
    parser.add_argument("end", type=float)
    parser.add_argument("--step-ms", type=int, default=50)
    args = parser.parse_args()
    for record in SpeakerFrames(args.path).summarize(args.start, args.end, args.step_ms):
        print(json.dumps(record))
