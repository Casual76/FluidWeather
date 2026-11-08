"""Summarize saved gfxinfo/meminfo runs; never drives a device or filters slow outliers."""
import argparse
import csv
import json
import math
from pathlib import Path
import re


def percentile95(values):
    return sorted(values)[math.ceil(len(values) * 0.95) - 1] if values else None


def frames(path):
    parts = path.read_text(encoding="utf-8-sig").split("---PROFILEDATA---")
    if len(parts) < 3 or len(parts) % 2 == 0:
        raise ValueError(f"{path}: missing or unterminated PROFILEDATA block")
    result = []
    required = ("Flags", "IntendedVsync", "FrameCompleted")
    for block in parts[1::2]:
        reader = csv.DictReader(block.strip().splitlines(), strict=True)
        if not reader.fieldnames or any(reader.fieldnames.count(key) != 1 for key in required):
            raise ValueError(f"{path}: missing or ambiguous frame columns")
        for row in reader:
            if None in row or any(value is None for value in row.values()) or any(row[key] == "" for key in required):
                raise ValueError(f"{path}: incomplete or malformed frame row")
            try:
                flags, start, end = (int(row[key]) for key in required)
            except (TypeError, ValueError) as error:
                raise ValueError(f"{path}: non-integer frame value") from error
            if flags != 0:
                continue
            if 0 < start <= end < 2**63 - 1:
                result.append((end - start) / 1_000_000)
    if not result:
        raise ValueError(f"{path}: no measurable frames")
    return result


def summarize(directory):
    if not directory.is_dir():
        raise ValueError(f"{directory}: capture directory does not exist")
    captures = sorted(directory.glob("*-gfxinfo-run*.txt"))
    if not captures:
        raise ValueError(f"{directory}: no captured runs")
    expected_memory = {gfx.with_name(gfx.name.replace("-gfxinfo-", "-meminfo-")) for gfx in captures}
    if expected_memory != set(directory.glob("*-meminfo-run*.txt")):
        raise ValueError(f"{directory}: incomplete gfxinfo/meminfo pairs")
    runs = []
    for gfx in captures:
        memory = gfx.with_name(gfx.name.replace("-gfxinfo-", "-meminfo-"))
        durations = frames(gfx)
        pss = re.search(r"TOTAL PSS:\s*(\d+)(?=\s|$)", memory.read_text(encoding="utf-8-sig"))
        if pss is None or int(pss[1]) <= 0:
            raise ValueError(f"{memory}: missing or invalid TOTAL PSS")
        runs.append({"run": gfx.stem, "frames": len(durations),
                     "frame_p95_ms": percentile95(durations),
                     "total_pss_kib": int(pss[1])})
    return {"method": "nearest-rank p95 of FrameCompleted-IntendedVsync; Flags=0; raw files retained",
            "limitations": "debug emulator samples; no field accuracy or matched before/after claim",
            "runs": runs}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        report = summarize(args.directory)
    except (OSError, ValueError, csv.Error) as error:
        parser.error(str(error))
    print(json.dumps(report, indent=2))
