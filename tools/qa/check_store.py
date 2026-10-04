"""Validate store metadata limits, PNG geometry and the release APK manifest."""
import argparse
import pathlib
import re
import struct
import subprocess
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]


class StoreCheck:
    def metadata(self):
        locales = ROOT / "fastlane/metadata/android"
        count = 0
        for locale in locales.iterdir():
            if not locale.is_dir():
                continue
            for name, limit in (("title.txt", 30), ("short_description.txt", 80), ("full_description.txt", 4000)):
                path = locale / name
                if path.exists():
                    text = path.read_text(encoding="utf-8").strip()
                    if not text or len(text) > limit:
                        raise ValueError(f"{path}: {len(text)} characters (limit {limit})")
            for path in (locale / "changelogs").glob("*.txt"):
                if len(path.read_text(encoding="utf-8").strip()) > 500:
                    raise ValueError(f"Changelog exceeds 500 characters: {path}")
            count += 1
        images = locales / "en-US/images"
        expected = {"icon.png": (512, 512), "featureGraphic.png": (1024, 500),
                    "phoneScreenshots/1.png": (1080, 1920), "phoneScreenshots/2.png": (1080, 1920)}
        for name, size in expected.items():
            with (images / name).open("rb") as image:
                header = image.read(24)
            if header[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack_from(">II", header, 16) != size:
                raise ValueError(f"{name}: expected a {size} PNG")
        resource_locales = 0
        for directory in (ROOT / "app/src/main/res").glob("values*"):
            if not (directory / "strings.xml").exists():
                continue
            strings = ET.parse(directory / "store.xml").getroot()
            if {node.get("name") for node in strings} != {"privacy_policy", "accessibility_data_use"}:
                raise ValueError(f"Missing release disclosure translation: {directory}")
            resource_locales += 1
        print(f"Store metadata: {count} locales within limits, 4 PNGs valid, disclosure in {resource_locales} resource locales")

    def apk(self, aapt, apk):
        def dump(*args):
            return subprocess.check_output([str(aapt), "dump", *args], text=True, encoding="utf-8")
        badging = dump("badging", str(apk))
        for expected in ("name='io.github.lrq3000.utterlane'", "versionCode='11'", "versionName='2.0.0'",
                         "sdkVersion:'26'", "targetSdkVersion:'36'", "native-code: 'arm64-v8a'"):
            if expected not in badging:
                raise ValueError(f"APK is missing {expected}")
        manifest = dump("xmltree", str(apk), "AndroidManifest.xml")
        if re.search(r"android:(debuggable|testOnly)\([^\n]*(?:0xffffffff|=\"true\")", manifest):
            raise ValueError("Release APK is debuggable or test-only")
        print(f"{apk.name}: Utterlane 2.0.0 / 11, minSdk 26, targetSdk 36, ARM64, non-debug/non-test")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt", type=pathlib.Path)
    parser.add_argument("--apk", type=pathlib.Path)
    args = parser.parse_args()
    checker = StoreCheck()
    checker.metadata()
    if args.apk:
        if not args.aapt:
            parser.error("--apk requires --aapt")
        checker.apk(args.aapt, args.apk)
