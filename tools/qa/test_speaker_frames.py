import pathlib
import struct
import tempfile
import unittest
from tools.qa.inspect_speaker_frames import SpeakerFrames


class SpeakerFrameTests(unittest.TestCase):
    def test_decimal_boundary_and_voice_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "posterior.f32"
            path.write_bytes(struct.pack("<8f", .9, .01, 0, 0, 0, 0, 0, 0) * 500)
            rows = list(SpeakerFrames(path).summarize(4.1, 4.15))
            self.assertEqual(1, len(rows))
            self.assertEqual(4.1, rows[0]["start_s"])
            self.assertEqual(50, rows[0]["voiced_ms"])
            self.assertEqual(0, rows[0]["winner"])

    def test_invalid_storage_or_unbounded_request(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "posterior.f32"
            path.write_bytes(b"bad")
            with self.assertRaises(ValueError):
                SpeakerFrames(path)
            path.write_bytes(struct.pack("<8f", *([float("nan")] * 8)))
            with self.assertRaises(ValueError):
                list(SpeakerFrames(path).summarize(0, .01))
            with self.assertRaises(ValueError):
                list(SpeakerFrames(path).summarize(0, 31))


if __name__ == "__main__":
    unittest.main()
