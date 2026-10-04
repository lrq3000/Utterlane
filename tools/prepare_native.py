"""Fetch exact source revisions needed by the Android Parakeet JNI runtime.

Run before Gradle builds. No binary downloads. Apply the minimal source-pinned
CMake embedding adaptation; any other dirty checkout is never overwritten.
"""
import argparse
import pathlib
import subprocess

CRISP_REVISION = "966561aa596cfc653aa0e9885d44117fad9cca35"
GGML_REVISION = "2f5a80d258c46e6ac8eee95f1328c0f58376d7ee"


class NativeSources:
    def __init__(self, root):
        self.source = root / ".native-cache" / "crispasr"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args], text=True).strip()

    def adaptations(self):
        cmake = self.git("show", "HEAD:src/CMakeLists.txt")
        cache = self.git("show", "HEAD:src/crispasr_cache.cpp")
        entry = "static bool fetch_download(const std::string& url, const std::string& dest, bool quiet) {"
        if cache.count(entry) != 1:
            raise RuntimeError("Pinned native download entry point changed; review the Android offline adaptation")
        return {
            "src/CMakeLists.txt": cmake.replace("${CMAKE_SOURCE_DIR}", "${PROJECT_SOURCE_DIR}"),
            # Missing companion weights must be imported by the user, not fetched
            # by a hidden native curl/wget subprocess on rooted Android devices.
            "src/crispasr_cache.cpp": cache.replace(entry, entry + "\n#ifdef __ANDROID__\n"
                '    fprintf(stderr, "Utterlane: import the missing companion model; native downloads are disabled.\\n");\n'
                "    return false;\n#endif"),
        }

    def prepare(self):
        self.source.parent.mkdir(parents=True, exist_ok=True)
        if not self.source.exists():
            subprocess.run(["git", "clone", "--quiet", "--depth", "1", "https://github.com/CrispStrobe/CrispASR.git", str(self.source)], check=True)
        dirty = self.git("status", "--porcelain", "--untracked-files=no")
        if dirty:
            known = self.adaptations()
            changed = self.git("diff", "--name-only", "HEAD").splitlines()
            if not changed or any(name not in known or (self.source / name).read_text(encoding="utf-8").strip() != known[name] for name in changed):
                raise RuntimeError("Native source checkout has changes; refusing to overwrite it")
        if self.git("rev-parse", "HEAD") != CRISP_REVISION:
            self.git("fetch", "--quiet", "--depth", "1", "origin", CRISP_REVISION)
            self.git("checkout", "--quiet", CRISP_REVISION)
        if not (self.source / "ggml" / "CMakeLists.txt").exists():
            self.git("submodule", "update", "--init", "--depth", "1", "ggml")
        actual = subprocess.check_output(["git", "-C", str(self.source / "ggml"), "rev-parse", "HEAD"], text=True).strip()
        if actual != GGML_REVISION:
            raise RuntimeError(f"ggml revision mismatch: {actual}")
        # Upstream's library CMake refers to the embedding app root for bundled
        # llama, WebRTC and codec sources. PROJECT_SOURCE_DIR is CrispASR's root.
        for name, adapted in self.adaptations().items():
            path = self.source / name
            if path.read_text(encoding="utf-8").strip() != adapted:
                path.write_text(adapted + "\n", encoding="utf-8")
        print(f"Native sources ready: CrispASR {CRISP_REVISION[:12]}, ggml {actual[:12]}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    NativeSources(parser.parse_args().root).prepare()
