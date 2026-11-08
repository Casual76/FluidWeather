"""SYNTHETIC fixtures test offline arithmetic, never real app performance."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import weather_performance_compare as comparison


class PerformanceComparisonTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.baseline = self.capture("baseline", [10, 10, 10], [100, 100, 100])
        self.candidate = self.capture("candidate", [11, 11, 11], [105, 105, 105])

    def capture(self, role, times, memory):
        root = self.root / role
        root.mkdir()
        raw = root / "raw"
        raw.mkdir()

        def artifact(path, content):
            path.write_text(content, encoding="utf-8")
            return {"path": path.relative_to(root).as_posix(),
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

        conditions = {key: "SYNTHETIC fixed " + key for key in comparison.CONDITION_TEXT}
        conditions.update({key: 1 for key in comparison.CONDITION_NUMBERS})
        conditions.update(orientation="portrait", locale="en-US", timezone="Europe/Rome",
                          font_scale=1.3, refresh_hz=60, viewport_width_px=1080,
                          viewport_height_px=2400, density_dpi=420, idle_seconds=5)
        meta = {"schema_version": 1, "evidence": "SYNTHETIC",
                "revision": ("a" if role == "baseline" else "b") * 40,
                "apk": artifact(root / "SYNTHETIC.apk", "SYNTHETIC bytes " + role),
                "inputs": artifact(root / "SYNTHETIC-inputs.txt", "SYNTHETIC frozen inputs"),
                "gestures": artifact(root / "SYNTHETIC-gestures.txt", "SYNTHETIC six swipes"),
                "conditions": conditions, "warmups": 3, "repeats": len(times), "runs": []}
        for index, (duration, pss) in enumerate(zip(times, memory), 1):
            durations = duration if isinstance(duration, list) else [duration]
            rows = "".join(f"0,1000000,{1000000 + int(value * 1000000)},\n" for value in durations)
            gfx = artifact(raw / f"synthetic-gfxinfo-run{index}.txt",
                           "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n" + rows + "---PROFILEDATA---\n")
            mem = artifact(raw / f"synthetic-meminfo-run{index}.txt", f"TOTAL PSS: {pss}\n")
            meta["runs"].append({"id": f"repeat-{index}", "gfxinfo": gfx, "meminfo": mem})
        path = root / "metadata.json"
        self.save(path, meta)
        return path

    def read(self, path):
        return json.loads(path.read_text(encoding="utf-8"))

    def save(self, path, meta):
        path.write_text(json.dumps(meta), encoding="utf-8")

    def mutate_raw(self, path, key, content, rehash=True):
        meta = self.read(path)
        spec = meta["runs"][0][key]
        raw = path.parent / spec["path"]
        raw.write_text(content, encoding="utf-8")
        if rehash:
            spec["sha256"] = hashlib.sha256(raw.read_bytes()).hexdigest()
            self.save(path, meta)

    def compare(self):
        return comparison.compare(self.baseline, self.candidate)

    def test_matching_math_and_exact_ten_percent_boundary(self):
        report = self.compare()
        self.assertEqual(report["screening"], "WITHIN_10_PERCENT_SCREEN_ONLY")
        self.assertEqual(report["c6_acceptance"], "NV")
        self.assertEqual(report["evidence"], "SYNTHETIC")
        self.assertEqual(report["summary"]["percent_change"]["frame_p95_ms"], 10)
        self.assertEqual(report["summary"]["percent_change"]["total_pss_kib"], 5)
        self.assertEqual(len(report["runs"]), 3)
        self.assertEqual(report["summary"]["baseline"]["frames"], 3)

    def test_each_condition_must_match(self):
        original = self.read(self.candidate)
        for key in comparison.CONDITION_TEXT + comparison.CONDITION_NUMBERS:
            with self.subTest(key=key):
                meta = json.loads(json.dumps(original))
                value = meta["conditions"][key]
                meta["conditions"][key] = value + 1 if type(value) in (int, float) else value + " changed"
                self.save(self.candidate, meta)
                with self.assertRaises(ValueError):
                    self.compare()

    def test_missing_unknown_and_invalid_metadata(self):
        original = self.read(self.candidate)
        for field, value in (("font_scale", 0), ("font_scale", True),
                             ("font_scale", float("nan")), ("device_identity", "unknown"),
                             ("clock_policy", "TODO fixed clock"), ("orientation", "up")):
            with self.subTest(field=field, value=value):
                meta = json.loads(json.dumps(original))
                meta["conditions"][field] = value
                self.save(self.candidate, meta)
                with self.assertRaises(ValueError):
                    self.compare()
        for key in original:
            with self.subTest(missing=key):
                meta = json.loads(json.dumps(original))
                del meta[key]
                self.save(self.candidate, meta)
                with self.assertRaises(ValueError):
                    self.compare()

    def test_duplicate_json_key_rejected(self):
        text = self.candidate.read_text(encoding="utf-8")
        self.candidate.write_text(text.replace('"schema_version": 1', '"schema_version": 1, "schema_version": 1'), encoding="utf-8")
        with self.assertRaises(ValueError):
            self.compare()

    def test_at_least_three_warmups_and_repeats(self):
        original = self.read(self.candidate)
        for key in ("warmups", "repeats"):
            meta = json.loads(json.dumps(original))
            meta[key] = 2
            self.save(self.candidate, meta)
            with self.assertRaises(ValueError):
                self.compare()

    def test_unmatched_runs_and_duplicate_ids_rejected(self):
        meta = self.read(self.candidate)
        meta["runs"][0]["id"] = "unmatched"
        self.save(self.candidate, meta)
        with self.assertRaisesRegex(ValueError, "unmatched"):
            self.compare()
        meta["runs"][0]["id"] = meta["runs"][1]["id"]
        self.save(self.candidate, meta)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.compare()

    def test_missing_pair_and_unlisted_run_rejected(self):
        meta = self.read(self.candidate)
        mem = self.candidate.parent / meta["runs"][0]["meminfo"]["path"]
        mem.unlink()
        with self.assertRaises(ValueError):
            self.compare()
        mem.write_text("TOTAL PSS: 105\n", encoding="utf-8")
        (mem.parent / "synthetic-meminfo-run4.txt").write_text("TOTAL PSS: 100\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unlisted"):
            self.compare()

    def test_digests_bind_raw_apk_inputs_and_gestures(self):
        meta = self.read(self.candidate)
        for spec in (meta["apk"], meta["inputs"], meta["gestures"],
                     meta["runs"][0]["gfxinfo"], meta["runs"][0]["meminfo"]):
            path = self.candidate.parent / spec["path"]
            original = path.read_bytes()
            path.write_bytes(original + b"tamper")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                self.compare()
            path.write_bytes(original)

    def test_matching_declared_digest_still_requires_matching_input_bytes(self):
        meta = self.read(self.candidate)
        path = self.candidate.parent / meta["inputs"]["path"]
        path.write_text("SYNTHETIC different input", encoding="utf-8")
        meta["inputs"]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.save(self.candidate, meta)
        with self.assertRaisesRegex(ValueError, "incomparable inputs"):
            self.compare()

    def test_invalid_and_truncated_raw_even_with_correct_hash(self):
        raw = (self.candidate.parent / self.read(self.candidate)["runs"][0]["gfxinfo"]["path"]).read_text()
        for text in ("capture interrupted", raw.rsplit("---PROFILEDATA---", 1)[0],
                     raw.replace("0,1000000,12000000,", "0,1000000")):
            self.mutate_raw(self.candidate, "gfxinfo", text)
            with self.assertRaises(ValueError):
                self.compare()

    def test_missing_zero_and_negative_pss_rejected(self):
        for text in ("No process found", "TOTAL PSS: 0\n", "TOTAL PSS: -5\n"):
            self.mutate_raw(self.candidate, "meminfo", text)
            with self.assertRaises(ValueError):
                self.compare()

    def test_zero_baseline_frame_denominator_rejected(self):
        self.mutate_raw(self.baseline, "gfxinfo", "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n0,1000000,1000000,\n---PROFILEDATA---\n")
        with self.assertRaisesRegex(ValueError, "zero frame"):
            self.compare()

    def test_outlier_and_bad_repeat_not_hidden_by_summary(self):
        self.candidate = self.capture("outlier", [[1] * 20 + [900], 1, 1], [1000, 1, 1])
        report = self.compare()
        first = report["runs"][0]
        self.assertEqual(first["candidate"]["frames"], 21)
        self.assertEqual(first["candidate"]["frame_max_ms"], 900)
        # Nearest rank for 21 values is 20: max is retained even outside p95.
        self.assertEqual(first["candidate"]["frame_p95_ms"], 1)
        self.assertEqual(first["percent_change"]["total_pss_kib"], 900)
        self.assertTrue(first["review_required"])
        self.assertEqual(report["screening"], "REVIEW_REQUIRED")
        self.assertEqual(report["summary"]["candidate"]["frames"], 23)

    def test_frame_threshold_crossing_requires_review(self):
        self.candidate = self.capture("slow", [12, 1, 1], [100, 100, 100])
        report = self.compare()
        self.assertTrue(report["runs"][0]["review_required"])
        self.assertEqual(report["summary"]["worst_repeat_percent_change"]["frame_p95_ms"], 20)

    def test_path_escape_rejected(self):
        meta = self.read(self.candidate)
        meta["apk"]["path"] = "../baseline/SYNTHETIC.apk"
        self.save(self.candidate, meta)
        with self.assertRaises(ValueError):
            self.compare()

    def test_reused_raw_path_rejected(self):
        meta = self.read(self.candidate)
        meta["runs"][1]["gfxinfo"] = meta["runs"][0]["gfxinfo"]
        meta["runs"][1]["meminfo"] = meta["runs"][0]["meminfo"]
        self.save(self.candidate, meta)
        with self.assertRaisesRegex(ValueError, "reused"):
            self.compare()

    def test_revision_apk_and_evidence_identity_checks(self):
        original = self.read(self.candidate)
        for key, value in (("revision", "a" * 40), ("revision", "short"),
                           ("evidence", "CONTROLLED_CAPTURE")):
            with self.subTest(key=key, value=value):
                meta = json.loads(json.dumps(original))
                meta[key] = value
                self.save(self.candidate, meta)
                with self.assertRaises(ValueError):
                    self.compare()
        self.save(self.candidate, original)
        apk = self.candidate.parent / original["apk"]["path"]
        apk.write_bytes((self.baseline.parent / self.read(self.baseline)["apk"]["path"]).read_bytes())
        original["apk"]["sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        self.save(self.candidate, original)
        with self.assertRaisesRegex(ValueError, "APK digests must differ"):
            self.compare()

    def test_cli_exit_codes_and_no_numeric_report_on_invalid(self):
        for expected, candidate in ((0, self.candidate), (2, self.root / "missing.json")):
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = comparison.main([str(self.baseline), str(candidate)])
            self.assertEqual(code, expected)
            if expected == 2:
                self.assertEqual(out.getvalue(), "")
                self.assertIn("C6 NV", err.getvalue())
        slow = self.capture("cli-slow", [12, 12, 12], [100, 100, 100])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(comparison.main([str(self.baseline), str(slow)]), 1)


if __name__ == "__main__":
    unittest.main()
