"""Fail if a generated compile database has unoptimized ggml CPU/base hot paths."""
import argparse
import json
import pathlib
import re


def check(database):
    rows = json.loads(database.read_text(encoding="utf-8"))
    checked = 0
    bad = []
    for row in rows:
        name = pathlib.Path(row["file"]).name
        if name not in ("ggml-quants.c", "ggml-cpu.cpp", "ggml.c"):
            continue
        checked += 1
        command = row.get("command", " ".join(row.get("arguments", [])))
        flags = re.findall(r"(?:^|\s)(-O(?:0|1|2|3|s|z|g|fast))(?=\s|$)", command)
        if not flags or flags[-1] not in ("-O2", "-O3", "-Ofast"):
            bad.append(name)
    if checked < 3 or bad:
        raise SystemExit(f"FAIL: inspected {checked} kernels; unoptimized: {', '.join(bad) or 'missing entries'}")
    print(f"PASS: {checked} ggml hot-path translation units optimized")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=pathlib.Path)
    check(parser.parse_args().database)
