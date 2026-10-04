"""Fetch exact source revisions needed by the Android Parakeet JNI runtime.

Run before Gradle builds. No binary downloads or vendor-source modifications.
The cache is disposable; an existing dirty checkout is never overwritten.
"""
import argparse
import pathlib
import subprocess

CRISP_REVISION = "966561aa596cfc653aa0e9885d44117fad9cca35"
GGML_REVISION = "2f5a80d258c46e6ac8eee95f1328c0f58376d7ee"
TRANSCRIBE_REVISION = "ba949120d60f29daaaa13eec65b9c28c2c2112a6"


class NativeSources:
    def __init__(self, root):
        self.source = root / ".native-cache" / "crispasr"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args], text=True).strip()

    def prepare(self):
        self.source.parent.mkdir(parents=True, exist_ok=True)
        if not self.source.exists():
            subprocess.run(["git", "clone", "--quiet", "--depth", "1", "https://github.com/CrispStrobe/CrispASR.git", str(self.source)], check=True)
        if self.git("status", "--porcelain", "--untracked-files=no"):
            raise RuntimeError("Native source checkout has changes; refusing to overwrite it")
        if self.git("rev-parse", "HEAD") != CRISP_REVISION:
            self.git("fetch", "--quiet", "--depth", "1", "origin", CRISP_REVISION)
            self.git("checkout", "--quiet", CRISP_REVISION)
        if not (self.source / "ggml" / "CMakeLists.txt").exists():
            self.git("submodule", "update", "--init", "--depth", "1", "ggml")
        actual = subprocess.check_output(["git", "-C", str(self.source / "ggml"), "rev-parse", "HEAD"], text=True).strip()
        if actual != GGML_REVISION:
            raise RuntimeError(f"ggml revision mismatch: {actual}")
        print(f"Native sources ready: CrispASR {CRISP_REVISION[:12]}, ggml {actual[:12]}")


class TranscribeSources:
    """This upstream vendors its patched ggml in the pinned commit, not a submodule."""
    def __init__(self, root):
        self.source = root / ".native-cache" / "transcribe"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args], text=True).strip()

    def prepare(self):
        self.source.parent.mkdir(parents=True, exist_ok=True)
        if not self.source.exists():
            subprocess.run(["git", "clone", "--quiet", "--depth", "1", "https://github.com/NairoDorian/transcribe.cpp.git", str(self.source)], check=True)
        if self.git("status", "--porcelain", "--untracked-files=no"):
            raise RuntimeError("transcribe.cpp source checkout has changes; refusing to overwrite it")
        if self.git("rev-parse", "HEAD") != TRANSCRIBE_REVISION:
            self.git("fetch", "--quiet", "--depth", "1", "origin", TRANSCRIBE_REVISION)
            self.git("checkout", "--quiet", TRANSCRIBE_REVISION)
        if "GGML_TYPE_TQ1_G128" not in (self.source / "ggml/include/ggml.h").read_text(encoding="utf-8"):
            raise RuntimeError("Pinned transcribe.cpp is missing its ternary ggml type")
        print(f"Native sources ready: transcribe.cpp {TRANSCRIBE_REVISION[:12]} (vendored ternary ggml)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    root = parser.parse_args().root
    NativeSources(root).prepare()
    TranscribeSources(root).prepare()
