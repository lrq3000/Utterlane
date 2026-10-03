"""Audit tracked branding, documentation links, original art and packaged APKs.

Usage: python tools/qa/check_rebrand.py --aapt /path/to/aapt [--apk path ...]
This checks the actual distribution artifact, including JNI symbols, rather
than assuming that a successful Kotlin compilation proves the rename complete.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile


class RebrandAudit:
    PACKAGE = "io.github.lrq3000.utterlane"
    LEGACY = re.compile(r"translander|voice[ _-]*keyboard|voice[ _-]*transcribe", re.I)
    # These are authentic copyright, lineage or historical evidence. Keep this
    # list explicit: new application code must never inherit a blanket exemption.
    HISTORICAL = {
        "LICENSE", "README.md", "CHANGELOG.md",
        "fastlane/metadata/android/en-US/full_description.txt",
        "docs/design/utterlane-branding.md",
        "docs/qa/audio-history-streaming.md",
        "docs/qa/utterlane-rebrand.md",
        "docs/superpowers/plans/2026-10-02-audio-history-streaming.md",
        "docs/superpowers/plans/2026-10-04-utterlane-rebrand.md",
        "tools/qa/check_rebrand.py",
    }

    def __init__(self, root):
        self.root = root

    def source(self):
        paths = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=self.root).decode("utf-8").split("\0")
        checked = 0
        for name in sorted(set(paths) - {""}):
            path = self.root / name
            if not path.is_file():
                continue
            assert not self.LEGACY.search(name), f"Legacy filename: {name}"
            data = path.read_bytes()
            if b"\0" in data or path.suffix.lower() in {".png", ".jar", ".jpg", ".webp"}:
                continue
            text = data.decode("utf-8")
            checked += 1
            if name not in self.HISTORICAL:
                assert not self.LEGACY.search(text), f"Unexpected legacy branding: {name}"
        print(f"PASS: {checked} tracked/new text files audited; historical exceptions listed explicitly")

        readme = (self.root / "README.md").read_text(encoding="utf-8")
        links = re.findall(r'(?:src="([^"]+)"|\]\(([^)]+)\))', readme)
        for html, markdown in links:
            url = html or markdown
            if ":" not in url and not url.startswith("#"):
                assert (self.root / url.split("#")[0]).exists(), f"Broken README path: {url}"
        print("PASS: README local image and document links resolve")

        original = self.root / "docs/design/utterlane-logo-and-icon.png"
        assert hashlib.sha256(original.read_bytes()).hexdigest() == "411769b4143a1b7f35251d94a245081accd466604b3de2d524cd77b27cb48061"
        print("PASS: archived original artwork is byte-identical to the supplied PNG")

    def apk(self, path, aapt):
        badging = subprocess.check_output([str(aapt), "dump", "badging", str(path)], encoding="utf-8")
        assert f"package: name='{self.PACKAGE}'" in badging, "Wrong APK application ID"
        labels = re.findall(r"^application-label[^:]*:'([^']*)'", badging, re.M)
        assert labels and set(labels) == {"Utterlane"}, f"Wrong localized application labels: {set(labels)}"
        manifest = subprocess.check_output([str(aapt), "dump", "xmltree", str(path), "AndroidManifest.xml"], encoding="utf-8")
        for component in ("UtterlaneApp", "settings.SettingsActivity", "ime.VoiceInputMethodService",
                          "service.TextInjectionService", "transcribe.TranscribeActivity", "asr.RecognitionWorkerService"):
            assert f"{self.PACKAGE}.{component}" in manifest, f"Missing component: {component}"
        assert f"{self.PACKAGE}.fileprovider" in manifest
        assert f"{self.PACKAGE}.action.TRANSCRIBE" in manifest
        with zipfile.ZipFile(path) as archive:
            assert not any("translander" in n.lower() for n in archive.namelist()), "Legacy APK entry"
            native = archive.read("lib/arm64-v8a/libutterlane_crisp.so")
            for method in ("openNative", "decodeNative", "closeNative"):
                assert f"Java_io_github_lrq3000_utterlane_asr_CrispParakeetBackend_{method}".encode() in native, f"Missing JNI symbol: {method}"
            assert b"Java_com_translander_" not in native
            for name in archive.namelist():
                if re.fullmatch(r"classes\d*\.dex", name):
                    dex = archive.read(name)
                    assert b"Lcom/translander/" not in dex, f"Legacy class namespace in {name}"
                    assert b"at.webformat.translander" not in dex, f"Legacy application ID in {name}"
        print(f"PASS: {path.name}: package, localized labels, components, provider, action, DEX and JNI symbols")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt", type=Path)
    parser.add_argument("--apk", type=Path, action="append", default=[])
    args = parser.parse_args()
    if args.apk and args.aapt is None:
        parser.error("--apk requires --aapt from Android SDK build-tools")
    audit = RebrandAudit(Path(__file__).resolve().parents[2])
    audit.source()
    for apk in args.apk:
        audit.apk(apk.resolve(), args.aapt.resolve())
