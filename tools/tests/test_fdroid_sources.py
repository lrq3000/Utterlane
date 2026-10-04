"""Generic CrispASR embeds library sources under its examples directory."""
import pathlib
import tempfile
import unittest

from tools.prepare_fdroid import FdroidSources


class FdroidSourcesTest(unittest.TestCase):
    def test_pruning_preserves_generic_runtime_and_removes_unused_package_manifests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            sources = root / ".native-cache/crispasr"
            required = ("examples/CMakeLists.txt", "examples/common.cpp", "examples/talk-llama/llama.cpp")
            unused = ("examples/addon.node/package.json", "examples/crispasr.android.java/build.gradle",
                      "examples/wasm-tts/package.json", "examples/wchess/wchess.wasm/chessboardjs-1.0.0/js/chessboard-1.0.0/package.json")
            for name in required + unused:
                path = sources / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name, encoding="utf-8")
            FdroidSources(root).prepare()
            for name in required:
                self.assertTrue((sources / name).is_file(), f"Runtime source removed: {name}")
            for name in unused:
                self.assertFalse((sources / name).exists(), f"Unused package retained: {name}")


if __name__ == "__main__":
    unittest.main()
