"""Reproduce the small public-domain audio asset bundled with onboarding."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import urllib.request

from release_artifacts import verify_digest


class OnboardingSampleAsset:
    ROOT = Path(__file__).resolve().parents[1]
    URL = "https://upload.wikimedia.org/wikipedia/commons/7/7a/Alice%27s_Adventures_in_Wonderland%2C_chapter_1.ogg"
    SOURCE_SHA256 = "f9d4bd3f1f892e66338b6294d4331aa657d10d2591b6a663ab7a394d4a1d1719"
    SHA256 = "7382d35e88949640179e233a32157d9fe85907e0342f6a22bd08ccef26e293f5"
    TARGET = ROOT / "app/src/main/assets/onboarding/alice_wonderland_excerpt.wav"

    def prepare(self, check_only=False, source=None):
        if self.TARGET.exists():
            data = self.TARGET.read_bytes()
        elif check_only:
            raise FileNotFoundError(self.TARGET)
        else:
            if source:
                original = Path(source).read_bytes()
            else:
                request = urllib.request.Request(self.URL, headers={
                    "User-Agent": "Utterlane/2.0 (https://github.com/lrq3000/Utterlane; onboarding sample)"
                })
                with urllib.request.urlopen(request, timeout=60) as response:
                    original = response.read(10_000_001)
            verify_digest(original, self.SOURCE_SHA256)
            cache = self.ROOT / ".native-cache"
            cache.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(prefix="onboarding-audio-", dir=cache) as temporary:
                original_path = Path(temporary) / "source.ogg"
                excerpt_path = Path(temporary) / "excerpt.wav"
                original_path.write_bytes(original)
                # A clear complete sentence, with a short lead-in pause. PCM WAV
                # avoids another lossy encode and works with Android's decoder.
                subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", "48", "-i", str(original_path),
                                "-t", "9", "-map", "0:a:0", "-ac", "1", "-ar", "16000",
                                "-c:a", "pcm_s16le", "-bitexact", str(excerpt_path)], check=True)
                data = excerpt_path.read_bytes()
        verify_digest(data, self.SHA256)
        if not self.TARGET.exists():
            self.TARGET.parent.mkdir(parents=True, exist_ok=True)
            self.TARGET.write_bytes(data)
        legacy = self.TARGET.parent / "apollo11_eagle_has_landed.mp3"
        if not check_only and legacy.exists():
            # Retire only our known earlier prototype asset, never an unknown file.
            verify_digest(legacy.read_bytes(), "c2cf9c2754ec3c2f7315acd4fcdd7d892fb2027919c1a80adf90a49f63c0316c")
            legacy.unlink()
        print(f"Onboarding sample verified: {len(data)} bytes, SHA-256 {self.SHA256}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check bundled bytes without network access")
    parser.add_argument("--source", type=Path, help="Use an already-downloaded original Commons Ogg file")
    args = parser.parse_args()
    OnboardingSampleAsset().prepare(args.check, args.source)
