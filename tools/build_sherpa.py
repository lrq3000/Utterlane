"""Build sherpa bindings/JNI from pinned source using official Maven ONNX Runtime.

Run --sources-only BEFORE the F-Droid scanner, then --build-only AFTER it. All
transitive CMake sources are materialized for scanning; compilation disables
FetchContent downloads. The sole prebuilt runtime is MIT-licensed Maven Central
content, independently SHA-256 checked. No GitHub-release binaries are used.
"""
import argparse
import io
import os
import pathlib
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

from release_artifacts import ElfLibrary, NativeArchive, verify_digest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SHERPA_REVISION = "7e227a529be6c383134a358c5744d0eb1cb5ae1f"
SHERPA_VERSION = "1.12.23"
ORT_VERSION = "1.23.2"
ORT_SHA256 = "82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827"
ORT_NOTICES = {
    "LICENSE": "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
    "ThirdPartyNotices.txt": "e9e90971a8e75a9a8ac0c6412e29c1202d079998389915aa485f46c816c3b4cc",
}
NDK_VERSION = "28.2.13676358"
# Names match upstream FetchContent declarations (including hyphens).
SOURCES = {
    "eigen": ("https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz", "3.4.0", "8586084f71f9bde545ee7fa6d00288b264a2b7ac3607b974e54d13e7162c1c72"),
    "kaldi_native_fbank": ("csukuangfj/kaldi-native-fbank", "v1.22.3", "9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61"),
    "kissfft": ("mborgerding/kissfft", "febd4caeed32e33ad8b2e0bb5ea77542c40f18ec", "497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd"),
    "kaldi_decoder": ("k2-fsa/kaldi-decoder", "v0.2.10", "a3d602edc1f422acfe663153faf3f0a716305ec1f95b8fcf9d28d301d6827309"),
    "kaldifst": ("k2-fsa/kaldifst", "v1.7.17", "c4b701a23a400bda8032586b02c7e0d5e813a765832df60c23e6df9e62b010f4"),
    "openfst": ("csukuangfj/openfst", "sherpa-onnx-2024-06-19", "5c98e82cc509c5618502dde4860b8ea04d843850ed57e6d6b590b644b268853d"),
    "simple-sentencepiece": ("pkufool/simple-sentencepiece", "v0.7", "1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af"),
}


class SherpaBuilder:
    def __init__(self, root=ROOT):
        self.root = root
        self.cache = root / ".native-cache"
        self.source = self.cache / "sherpa-onnx"
        self.deps = self.cache / "sherpa-deps"
        self.cache.mkdir(exist_ok=True)

    def run(self, command, name, env=None):
        # Native builds are noisy. Preserve the complete log while keeping the
        # interactive output bounded; an error includes its diagnostic tail.
        log = self.cache / f"{name}.log"
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run([str(item) for item in command], cwd=self.root,
                                    env=env, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"{name} failed; see {log}\n{log.read_text(encoding='utf-8', errors='replace')[-5000:]}")
        print(f"{name}: OK (log: {log.relative_to(self.root)})", flush=True)

    @staticmethod
    def download(url, digest):
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        verify_digest(data, digest)
        return data

    def prepare(self):
        if not self.source.exists():
            self.run(["git", "clone", "--quiet", "--depth", "1", "--branch", f"v{SHERPA_VERSION}",
                      "https://github.com/k2-fsa/sherpa-onnx.git", self.source], "sherpa-source")
        revision = subprocess.check_output(["git", "-C", str(self.source), "rev-parse", "HEAD"], text=True).strip()
        if revision != SHERPA_REVISION:
            raise ValueError(f"Unexpected sherpa revision: {revision}")
        if subprocess.check_output(["git", "-C", str(self.source), "diff", "HEAD", "--", "."], text=True):
            raise ValueError("Refusing to build modified sherpa sources")
        self.deps.mkdir(exist_ok=True)
        notices = self.cache / "onnxruntime-notices"
        notices.mkdir(exist_ok=True)
        for name, digest in ORT_NOTICES.items():
            path = notices / name
            if not path.exists():
                path.write_bytes(self.download(f"https://raw.githubusercontent.com/microsoft/onnxruntime/v{ORT_VERSION}/{name}", digest))
            verify_digest(path.read_bytes(), digest)
        for name, (repo, revision, digest) in SOURCES.items():
            destination = self.deps / name
            if destination.exists():
                continue
            archive_type = "zip" if name == "kissfft" else "tar.gz"
            url = repo if repo.startswith("https://") else f"https://codeload.github.com/{repo}/{archive_type}/{revision}"
            data = self.download(url, digest)
            staging = self.deps / f"{name}-extract"
            staging.mkdir(exist_ok=True)
            if archive_type == "zip":
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    archive.extractall(staging)
            else:
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                    archive.extractall(staging, filter="data")
            entries = list(staging.iterdir())
            if len(entries) != 1 or not entries[0].is_dir():
                raise ValueError(f"Unexpected source archive layout: {name}")
            entries[0].rename(destination)
            staging.rmdir()
            print(f"Source verified: {name}", flush=True)

    def build(self, jobs, gradle=None):
        if not all((self.deps / name / "CMakeLists.txt").exists() for name in SOURCES):
            raise ValueError("Run --sources-only first; all dependencies must exist before scanning")
        sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk_value:
            raise ValueError("Set ANDROID_HOME to the Android SDK directory")
        sdk = pathlib.Path(sdk_value)
        ndk = sdk / "ndk" / NDK_VERSION
        suffix = ".exe" if os.name == "nt" else ""
        cmake = sdk / "cmake/3.22.1/bin" / f"cmake{suffix}"
        ninja = cmake.with_name(f"ninja{suffix}")
        for tool in (cmake, ninja, ndk / "build/cmake/android.toolchain.cmake"):
            if not tool.exists():
                raise ValueError(f"Missing build prerequisite: {tool}")
        runtime = self.cache / f"onnxruntime-{ORT_VERSION}"
        runtime.mkdir(exist_ok=True)
        archive_path = runtime / "runtime.aar"
        if not archive_path.exists():
            url = f"https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/{ORT_VERSION}/onnxruntime-android-{ORT_VERSION}.aar"
            archive_path.write_bytes(self.download(url, ORT_SHA256))
        data = archive_path.read_bytes()
        verify_digest(data, ORT_SHA256)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for name in archive.namelist():
                if name.startswith("headers/") or name == "jni/arm64-v8a/libonnxruntime.so":
                    archive.extract(name, runtime)
        ElfLibrary((runtime / "jni/arm64-v8a/libonnxruntime.so").read_bytes()).verify()
        env = dict(os.environ, SHERPA_ONNXRUNTIME_LIB_DIR=str(runtime / "jni/arm64-v8a"),
                   SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(runtime / "headers"))
        build = self.cache / "sherpa-build"
        command = [cmake, "-S", self.source, "-B", build, "-G", "Ninja",
                   f"-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}",
                   f"-DCMAKE_TOOLCHAIN_FILE={(ndk / 'build/cmake/android.toolchain.cmake').as_posix()}",
                   "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
                   "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=ON",
                   "-DSHERPA_ONNX_ENABLE_JNI=ON", "-DFETCHCONTENT_FULLY_DISCONNECTED=ON"]
        for feature in ("TTS", "SPEAKER_DIARIZATION", "BINARY", "C_API", "PYTHON", "TESTS", "CHECK", "PORTAUDIO", "WEBSOCKET"):
            command.append(f"-DSHERPA_ONNX_ENABLE_{feature}=OFF")
        command.append("-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF")
        for name in SOURCES:
            command.append(f"-DFETCHCONTENT_SOURCE_DIR_{name.upper()}={(self.deps / name).as_posix()}")
        self.run(command, "sherpa-configure", env)
        self.run([cmake, "--build", build, "--target", "sherpa-onnx-jni", "--parallel", str(jobs)], "sherpa-compile", env)
        package = self.cache / "sherpa-package/jni/arm64-v8a"
        package.mkdir(parents=True, exist_ok=True)
        libraries = list((build / "lib").glob("*.so")) + [runtime / "jni/arm64-v8a/libonnxruntime.so"]
        # Do not retain a removed native dependency across incremental rebuilds.
        for old in package.glob("*.so"):
            old.unlink()
        for library in libraries:
            ElfLibrary(library.read_bytes()).verify()
            shutil.copy2(library, package / library.name)
        # Include upstream license/notice texts with the binaries they cover.
        # Keep the original wording, including broader upstream third-party lists.
        assets = self.cache / "sherpa-package/assets/native-licenses"
        for name, source in {"sherpa-onnx": self.source, "onnxruntime": self.cache / "onnxruntime-notices",
                             **{name: self.deps / name for name in SOURCES}}.items():
            destination = assets / name
            destination.mkdir(parents=True, exist_ok=True)
            for path in source.iterdir():
                if path.is_file() and path.name.lower().startswith(("license", "copying", "copyright", "notice", "thirdpartynotices")):
                    shutil.copy2(path, destination / path.name)
        wrapper = self.root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        invocation = [wrapper] if os.name == "nt" else ["sh", wrapper]
        if gradle:
            invocation = [gradle]  # F-Droid removes project wrappers before building.
        self.run(invocation + ["-p", "tools/sherpa-android", "--console=plain", "--quiet", "assembleRelease"], "sherpa-aar")
        output = self.root / "app/libs" / f"sherpa-onnx-{SHERPA_VERSION}.aar"
        output.parent.mkdir(exist_ok=True)
        artifact = self.root / "tools/sherpa-android/build/outputs/aar/utterlane-sherpa-release.aar"
        NativeArchive(artifact).verify()
        shutil.copy2(artifact, output)
        print(f"AAR ready: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--sources-only", action="store_true")
    modes.add_argument("--build-only", action="store_true")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--gradle", help="Gradle executable supplied by F-Droid instead of the project wrapper")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    builder = SherpaBuilder()
    if not args.build_only:
        builder.prepare()
    if not args.sources_only:
        builder.build(args.jobs, args.gradle)
