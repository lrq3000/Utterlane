"""The source-pinned adaptation fails closed if upstream contracts drift."""
import unittest
from tools.native_stream_options import adapt_header, adapt_source, adapt_failure_state


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

    def test_legacy_header_is_recognizable_without_the_new_failure_getter(self):
        original = "int nemotron3_diar_n_speakers(struct nemotron3_diar_context* ctx);\n"
        self.assertNotIn("utterlane_n3d_stream_failed", adapt_header(original, failure_state=False))
        self.assertIn("utterlane_n3d_stream_failed", adapt_header(original))

    def test_both_failed_drain_paths_set_a_separate_error_flag(self):
        original = """struct nemotron3_diar_stream {
};
    *out_rows = 0;
    if (!n3d_stream_drain(st, false, lg))
        return nullptr;
    const bool ok = n3d_stream_drain(st, true, lg);
extern "C" void nemotron3_diar_stream_free(nemotron3_diar_stream* st) {}
"""
        adapted = adapt_failure_state(original)
        self.assertIn("bool failed = false;", adapted)
        self.assertIn("st->failed = true;", adapted)
        self.assertIn("st->failed = st->failed || !ok;", adapted)
        self.assertIn("*out_rows = 0;", adapted)
        self.assertIn("return !st || st->failed;", adapted)
        with self.assertRaises(RuntimeError):
            adapt_failure_state(original.replace("n3d_stream_drain(st, true, lg)", "changed()"))


if __name__ == "__main__":
    unittest.main()
