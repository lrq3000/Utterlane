"""Remove unused upstream examples/fixtures from an isolated F-Droid source tree.

These paths caused concrete scanner findings (unlocked example package managers,
Wear OS Play Services examples, test models). None participates in our selected
CMake targets or Kotlin source set. Delete them before scanning, rather than
exempting a complete native checkout from the scanner. Never run on a cache whose
upstream examples you want to retain; ordinary developer builds do not need this.
"""
import pathlib
import shutil


class FdroidSources:
    UNUSED = {
        # The generic runtime needs examples/CMakeLists.txt, common helpers and
        # talk-llama's vendored core even with CRISPASR_BUILD_EXAMPLES=OFF. Remove
        # only the unused example subtrees responsible for scanner findings.
        "crispasr": ("bindings", "examples/addon.node", "examples/crispasr.android.java",
                     "examples/wasm-tts", "examples/wchess", "crispasr", "crispasr-sys",
                     "flutter", "models", "samples", "tests", "tools"),
        "sherpa-onnx": (".github", "android", "dart-api-examples", "flutter", "flutter-examples",
                        "nodejs-addon-examples", "nodejs-examples", "scripts/node-addon-api", "scripts/nodejs", "mfc-examples"),
    }

    def __init__(self, root):
        self.cache = root / ".native-cache"

    def prepare(self):
        for project, paths in self.UNUSED.items():
            for name in paths:
                path = self.cache / project / name
                if path.is_symlink():
                    path.unlink()
                elif path.is_dir():
                    shutil.rmtree(path)
                elif path.exists():
                    path.unlink()
        print("Removed unused upstream examples and model fixtures before F-Droid scanning")


if __name__ == "__main__":
    FdroidSources(pathlib.Path(__file__).resolve().parents[1]).prepare()
