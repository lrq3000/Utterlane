"""The source-pinned adaptation fails closed if upstream contracts drift."""
import unittest
from tools.native_stream_options import adapt_header, adapt_source


class NativeStreamOptionsTests(unittest.TestCase):
    def test_header_adds_only_explicit_versioned_controls(self):
        original = "int nemotron3_diar_n_speakers(struct nemotron3_diar_context* ctx);\n"
        actual = adapt_header(original)
        self.assertIn(original, actual)
        self.assertIn("utterlane_n3d_options_v1", actual)
        self.assertIn("utterlane_n3d_work_callback", actual)

    def test_unknown_source_is_never_silently_patched(self):
        with self.assertRaisesRegex(RuntimeError, "anchor"):
            adapt_source("upstream changed")
        with self.assertRaisesRegex(RuntimeError, "anchor"):
            adapt_header("upstream changed")

    def test_repeated_anchor_is_rejected(self):
        anchor = "int nemotron3_diar_n_speakers(struct nemotron3_diar_context* ctx);\n"
        with self.assertRaises(RuntimeError):
            adapt_header(anchor * 2)


if __name__ == "__main__":
    unittest.main()
