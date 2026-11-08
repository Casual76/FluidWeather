"""Regression checks for incomplete performance captures (stdlib only)."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import weather_metrics


HEADER = 'Flags,IntendedVsync,FrameCompleted,\n'
COMPLETE = '---PROFILEDATA---\n' + HEADER + '0,1000000,3000000,\n---PROFILEDATA---\n'


class WeatherMetricsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def write(self, name, content):
        path = self.directory / name
        path.write_text(content, encoding='utf-8')
        return path

    def test_complete_capture_preserves_sample(self):
        path = self.write('capture.txt', COMPLETE)
        self.assertEqual(weather_metrics.frames(path), [2.0])

    def test_truncated_block_is_not_a_smaller_valid_sample(self):
        path = self.write('capture.txt', COMPLETE.rsplit('---PROFILEDATA---', 1)[0])
        with self.assertRaises(ValueError):
            weather_metrics.frames(path)

    def test_missing_profile_block_is_rejected(self):
        with self.assertRaises(ValueError):
            weather_metrics.frames(self.write('capture.txt', 'capture interrupted\n'))

    def test_truncated_row_is_rejected(self):
        text = '---PROFILEDATA---\n' + HEADER + '0,1000000\n---PROFILEDATA---\n'
        with self.assertRaises(ValueError):
            weather_metrics.frames(self.write('capture.txt', text))

    def test_unterminated_second_block_rejects_entire_capture(self):
        text = COMPLETE + '---PROFILEDATA---\n' + HEADER + '0,1000000,5000000,\n'
        with self.assertRaises(ValueError):
            weather_metrics.frames(self.write('capture.txt', text))

    def test_invalid_timestamp_is_not_a_measured_frame(self):
        text = COMPLETE.replace('3000000', str(2**63 - 1))
        with self.assertRaises(ValueError):
            weather_metrics.frames(self.write('capture.txt', text))

    def test_slow_outlier_remains_in_percentile(self):
        text = COMPLETE.replace('0,1000000,3000000,',
                                '0,1000000,3000000,\n0,1000000,901000000,')
        samples = weather_metrics.frames(self.write('capture.txt', text))
        self.assertEqual(samples, [2.0, 900.0])
        self.assertEqual(weather_metrics.percentile95(samples), 900.0)

    def test_missing_required_column_is_rejected(self):
        text = '---PROFILEDATA---\nFlags,IntendedVsync\n0,1000000\n---PROFILEDATA---\n'
        with self.assertRaises(ValueError):
            weather_metrics.frames(self.write('capture.txt', text))

    def test_row_truncated_after_required_columns_is_rejected(self):
        text = ('---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,Other,\n'
                '0,1000000,3000000\n---PROFILEDATA---\n')
        with self.assertRaises(ValueError):
            weather_metrics.frames(self.write('capture.txt', text))

    def test_capture_without_measurable_frames_is_rejected(self):
        with self.assertRaises(ValueError):
            weather_metrics.frames(self.write('capture.txt', COMPLETE.replace('0,1000000', '1,1000000')))

    def test_nonexistent_directory_is_rejected(self):
        with self.assertRaises(ValueError):
            weather_metrics.summarize(self.directory / 'missing')

    def test_empty_directory_is_rejected(self):
        with self.assertRaises(ValueError):
            weather_metrics.summarize(self.directory)

    def test_missing_memory_pair_is_rejected(self):
        self.write('phone-gfxinfo-run1.txt', COMPLETE)
        with self.assertRaises((ValueError, OSError)):
            weather_metrics.summarize(self.directory)

    def test_memory_without_pss_is_rejected(self):
        self.write('phone-gfxinfo-run1.txt', COMPLETE)
        self.write('phone-meminfo-run1.txt', 'No process found\n')
        with self.assertRaises(ValueError):
            weather_metrics.summarize(self.directory)

    def test_orphan_memory_run_rejects_entire_collection(self):
        self.write('phone-gfxinfo-run1.txt', COMPLETE)
        self.write('phone-meminfo-run1.txt', 'TOTAL PSS: 1234\n')
        self.write('phone-meminfo-run2.txt', 'TOTAL PSS: 1234\n')
        with self.assertRaises(ValueError):
            weather_metrics.summarize(self.directory)

    def test_complete_paired_run(self):
        self.write('phone-gfxinfo-run1.txt', COMPLETE)
        self.write('phone-meminfo-run1.txt', 'TOTAL PSS: 1234\n')
        run = weather_metrics.summarize(self.directory)['runs'][0]
        self.assertEqual((run['frames'], run['frame_p95_ms'], run['total_pss_kib']), (1, 2.0, 1234))

    def test_cli_rejects_missing_directory_without_json_success(self):
        result = subprocess.run([sys.executable, '-B', str(Path(weather_metrics.__file__)),
                                 str(self.directory / 'missing')], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '')


if __name__ == '__main__':
    unittest.main()
