"""Offline matched-capture arithmetic, never C6 acceptance or execution attestation."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import sys

try:
    from . import weather_metrics
except ImportError:
    import weather_metrics


CONDITION_TEXT = (
    "device_identity", "os_fingerprint", "build_variant", "scenario_id",
    "locale", "timezone", "orientation", "animation_policy", "quality_policy",
    "cache_policy", "clock_policy", "start_policy", "network_policy",
    "power_policy", "thermal_policy", "background_load_policy", "theme",
    "capture_tool", "memory_sampling_point",
)
CONDITION_NUMBERS = ("font_scale", "refresh_hz", "viewport_width_px",
                     "viewport_height_px", "density_dpi", "idle_seconds")
LIMITATIONS = (
    "Hashes bind current bytes to metadata, not device execution, capture completeness, "
    "APK installation, revision provenance or truthful controlled-condition declarations. "
    "No statistical repeatability claim; synthetic data proves arithmetic only. "
    "C6 acceptance requires independent evidence review."
)


def _keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        raise ValueError(f"{label}: expected exactly {sorted(keys)}")


def _text(value, label):
    if (not isinstance(value, str) or not value.strip() or value != value.strip()
            or re.search(r"\b(unknown|tbd|todo|unspecified|placeholder)\b|[<>]", value, re.I)
            or value.lower() in {"?", "n/a", "null", "none"}):
        raise ValueError(f"{label}: explicit known value required")


def _integer(value, minimum, label):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{label}: integer >= {minimum} required")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _file(root, spec, label):
    _keys(spec, ("path", "sha256"), label)
    _text(spec["path"], label + ".path")
    digest = spec["sha256"]
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
        raise ValueError(f"{label}: SHA-256 required")
    relative = Path(spec["path"])
    path = (root / relative).resolve()
    if relative.is_absolute() or not path.is_relative_to(root) or not path.is_file():
        raise ValueError(f"{label}: existing file beneath metadata directory required")
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != digest.lower():
        raise ValueError(f"{label}: SHA-256 mismatch for {path}")
    return path, actual


def load_capture_set(metadata_path):
    """Validate a manifest and all listed bytes; return strict raw metrics + identity."""
    metadata_path = Path(metadata_path).resolve()
    root = metadata_path.parent
    meta = json.loads(metadata_path.read_text(encoding="utf-8-sig"),
                      object_pairs_hook=_unique_object)
    _keys(meta, ("schema_version", "evidence", "revision", "apk", "inputs", "gestures",
                 "conditions", "warmups", "repeats", "runs"), "metadata")
    if type(meta["schema_version"]) is not int or meta["schema_version"] != 1:
        raise ValueError("schema_version must be 1")
    if meta["evidence"] not in ("CONTROLLED_CAPTURE", "SYNTHETIC"):
        raise ValueError("evidence must be CONTROLLED_CAPTURE or SYNTHETIC")
    if not isinstance(meta["revision"], str) or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", meta["revision"]):
        raise ValueError("revision must be full commit or frozen source-manifest digest")
    _integer(meta["warmups"], 3, "warmups")
    _integer(meta["repeats"], 3, "repeats")
    conditions = meta["conditions"]
    _keys(conditions, CONDITION_TEXT + CONDITION_NUMBERS, "conditions")
    for key in CONDITION_TEXT:
        _text(conditions[key], key)
    for key in CONDITION_NUMBERS:
        value = conditions[key]
        if (type(value) not in (int, float) or not math.isfinite(value) or value <= 0):
            raise ValueError(f"{key}: finite positive number required")
    for key in ("viewport_width_px", "viewport_height_px", "density_dpi"):
        _integer(conditions[key], 1, key)
    if conditions["orientation"] not in ("portrait", "landscape"):
        raise ValueError("orientation must be portrait or landscape")
    identities = {}
    for key in ("apk", "inputs", "gestures"):
        _, identities[key] = _file(root, meta[key], key)
    if not isinstance(meta["runs"], list) or len(meta["runs"]) != meta["repeats"]:
        raise ValueError("runs must contain exactly repeats entries")
    paths, ids, directories = set(), set(), set()
    raw_runs = []
    for run in meta["runs"]:
        _keys(run, ("id", "gfxinfo", "meminfo"), "run")
        _text(run["id"], "run.id")
        if run["id"] in ids:
            raise ValueError("duplicate run id")
        ids.add(run["id"])
        gfx, _ = _file(root, run["gfxinfo"], "gfxinfo")
        mem, _ = _file(root, run["meminfo"], "meminfo")
        if not re.fullmatch(r".+-gfxinfo-run.+\.txt", gfx.name):
            raise ValueError("gfxinfo filename must follow *-gfxinfo-run*.txt")
        if mem != gfx.with_name(gfx.name.replace("-gfxinfo-", "-meminfo-")):
            raise ValueError("run raw filename pair mismatch")
        if gfx in paths or mem in paths:
            raise ValueError("capture path reused across repeats")
        paths.update((gfx, mem))
        directories.add(gfx.parent)
        raw_runs.append((run, gfx))
    if len(directories) != 1:
        raise ValueError("all raw pairs must share one dedicated capture directory")
    directory = next(iter(directories))
    actual = {p.resolve() for pattern in ("*-gfxinfo-run*.txt", "*-meminfo-run*.txt")
              for p in directory.glob(pattern)}
    if actual != paths:
        raise ValueError("unlisted or missing raw capture files; no run selection allowed")
    # Keep the existing strict pairing, frame/truncation and PSS parser authoritative.
    parsed = {run["run"]: run for run in weather_metrics.summarize(directory)["runs"]}
    runs = {}
    pooled = []
    for declared, gfx in raw_runs:
        samples = weather_metrics.frames(gfx)
        metric = dict(parsed[gfx.stem])
        if metric["frame_p95_ms"] <= 0:
            raise ValueError("zero frame p95 is not a valid comparison denominator")
        metric.update(id=declared["id"], frame_min_ms=min(samples), frame_max_ms=max(samples),
                      raw={key: declared[key] for key in ("gfxinfo", "meminfo")})
        runs[declared["id"]] = metric
        pooled.extend(samples)
    return {"metadata": meta, "verified_sha256": identities, "runs": runs,
            "pooled_frames": pooled, "metadata_path": str(metadata_path),
            "metadata_sha256": hashlib.sha256(metadata_path.read_bytes()).hexdigest()}


def _change(baseline, candidate):
    if baseline <= 0:
        raise ValueError("baseline must be positive")
    return 100 * (candidate - baseline) / baseline


def _summary(capture):
    runs = list(capture["runs"].values())
    return {"repeats": len(runs), "frames": len(capture["pooled_frames"]),
            "pooled_frame_p95_ms": weather_metrics.percentile95(capture["pooled_frames"]),
            "frame_p95_ms": weather_metrics.percentile95([r["frame_p95_ms"] for r in runs]),
            "total_pss_kib": weather_metrics.percentile95([r["total_pss_kib"] for r in runs]),
            "frame_p95_range_ms": [min(r["frame_p95_ms"] for r in runs), max(r["frame_p95_ms"] for r in runs)],
            "total_pss_range_kib": [min(r["total_pss_kib"] for r in runs), max(r["total_pss_kib"] for r in runs)]}


def compare(baseline_metadata, candidate_metadata):
    """Return arithmetic only after validation; raises on invalid/incomparable inputs."""
    baseline = load_capture_set(baseline_metadata)
    candidate = load_capture_set(candidate_metadata)
    before, after = baseline["metadata"], candidate["metadata"]
    for key in ("evidence", "conditions", "warmups", "repeats"):
        if before[key] != after[key]:
            raise ValueError(f"incomparable {key}")
    for key in ("inputs", "gestures"):
        if baseline["verified_sha256"][key] != candidate["verified_sha256"][key]:
            raise ValueError(f"incomparable {key} digest")
    if before["revision"].lower() == after["revision"].lower():
        raise ValueError("baseline and candidate revisions must differ")
    if baseline["verified_sha256"]["apk"] == candidate["verified_sha256"]["apk"]:
        raise ValueError("baseline and candidate APK digests must differ")
    if set(baseline["runs"]) != set(candidate["runs"]):
        raise ValueError("unmatched run ids")
    comparisons = []
    for run_id in sorted(baseline["runs"]):
        b, c = baseline["runs"][run_id], candidate["runs"][run_id]
        changes = {key: _change(b[key], c[key]) for key in ("frame_p95_ms", "total_pss_kib")}
        comparisons.append({"id": run_id, "baseline": b, "candidate": c,
                            "percent_change": changes,
                            "review_required": any(value > 10 for value in changes.values())})
    bs, cs = _summary(baseline), _summary(candidate)
    summary_changes = {key: _change(bs[key], cs[key])
                       for key in ("frame_p95_ms", "total_pss_kib", "pooled_frame_p95_ms")}
    review = any(r["review_required"] for r in comparisons) or any(v > 10 for v in summary_changes.values())
    identity = lambda capture: {"metadata_path": capture["metadata_path"],
                                "metadata_sha256": capture["metadata_sha256"],
                                "revision": capture["metadata"]["revision"],
                                "verified_sha256": capture["verified_sha256"]}
    return {"schema_version": 1, "evidence": before["evidence"], "c6_acceptance": "NV",
            "screening": "REVIEW_REQUIRED" if review else "WITHIN_10_PERCENT_SCREEN_ONLY",
            "limitations": LIMITATIONS, "conditions": before["conditions"],
            "warmups": before["warmups"], "baseline_identity": identity(baseline),
            "candidate_identity": identity(candidate), "runs": comparisons,
            "summary": {"method": "nearest-rank p95 of per-run p95 and PSS; pooled frames separately; no averages",
                        "baseline": bs, "candidate": cs, "percent_change": summary_changes,
                        "worst_repeat_percent_change": {key: max(r["percent_change"][key] for r in comparisons)
                                                        for key in ("frame_p95_ms", "total_pss_kib")}}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args(argv)
    try:
        report = compare(args.baseline, args.candidate)
    except (OSError, ValueError, csv.Error) as error:
        print(f"INVALID_OR_INCOMPARABLE; C6 NV: {error}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, allow_nan=False))
    return 1 if report["screening"] == "REVIEW_REQUIRED" else 0


if __name__ == "__main__":
    sys.exit(main())
