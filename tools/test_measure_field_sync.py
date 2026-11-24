import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from measure_field_sync import bounds, measure, verdict


class FieldSyncTest(unittest.TestCase):
    def test_verdict_requires_the_whole_interval(self):
        self.assertEqual(verdict(-0.100, 0.100), "PASS")
        self.assertEqual(verdict(-0.105, 0.050), "INCONCLUSIVE")
        self.assertEqual(verdict(0.095, 0.140), "INCONCLUSIVE")
        self.assertEqual(verdict(0.101, 0.140), "FAIL")
        self.assertEqual(verdict(-0.140, -0.101), "FAIL")

    def test_bounds_apply_both_frame_interval_and_padding(self):
        low, high = bounds((0.98, 1.02), (1.09, 1.10), 0.01)
        self.assertAlmostEqual(low, 0.06)
        self.assertAlmostEqual(high, 0.13)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg tools required")
    def test_decoded_aligned_and_delayed_cues_and_window_edge(self):
        with tempfile.TemporaryDirectory() as tmp:
            media = Path(tmp) / "two-cues.mp4"
            subprocess.run([
                "ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i",
                "color=black:s=64x64:r=25:d=3,drawbox=c=white:t=fill:enable='eq(n,20)+eq(n,50)'",
                "-f", "lavfi", "-i",
                "aevalsrc='if(between(t,0.8,0.82)+between(t,2.16,2.18),0.7*sin(2*PI*900*t),0)':s=48000:d=3",
                "-c:v", "mpeg4", "-c:a", "aac", str(media),
            ], check=True)
            cue = {"threshold": 0.2, "padding_ms": 5}
            result = measure(media, {"cues": [
                dict(cue, label="early", first_contact_frame=20, audio_window_s=[0.72, 0.9]),
                dict(cue, label="late", first_contact_frame=50, audio_window_s=[2.1, 2.2]),
                dict(cue, label="no cue", first_contact_frame=50, audio_window_s=[1.8, 2.0]),
                dict(cue, label="window edge", first_contact_frame=20, audio_window_s=[0.80025, 0.9]),
            ], "drift_pairs": [["early", "late"]]})
            self.assertEqual([row["verdict"] for row in result["cues"]],
                             ["PASS", "FAIL", "INCONCLUSIVE", "INCONCLUSIVE"])
            low, high = result["drift"][0]["offset_change_bounds_ms"]
            self.assertLess(low, 160)
            self.assertGreater(high, 160)


if __name__ == "__main__":
    unittest.main()
