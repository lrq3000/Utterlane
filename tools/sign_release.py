"""Sign CI release outputs with maintainer credentials, never an ephemeral key.

The release APK uses the developer distribution key. The AAB is signed for Play
upload; Play App Signing controls the key on APKs delivered to Play users.
"""
import argparse
import base64
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import tempfile

from release_artifacts import NativeArchive


class ReleaseSigner:
    def __init__(self, source, output):
        self.source = source
        self.output = output

    @staticmethod
    def release_version(tag):
        # The existing tag workflow also publishes alpha/beta releases. Keep
        # those safe filename components while rejecting branches/path syntax.
        if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?", tag):
            raise ValueError("Expected a stable or prerelease vMAJOR.MINOR.PATCH tag")
        return tag[1:]

    def sign(self):
        names = ("UTTERLANE_KEYSTORE_BASE64", "UTTERLANE_STORE_PASSWORD", "UTTERLANE_KEY_ALIAS", "UTTERLANE_KEY_PASSWORD", "RELEASE_TAG")
        if any(not os.environ.get(name) for name in names):
            raise ValueError("Configure the release environment signing secrets and RELEASE_TAG before publishing")
        tag = os.environ["RELEASE_TAG"]
        version = self.release_version(tag)
        apk = self.source / "apk/release/app-release-unsigned.apk"
        bundle = self.source / "bundle/release/app-release.aab"
        for artifact in (apk, bundle):
            NativeArchive(artifact).verify()
        tools = pathlib.Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
        badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
        if f"versionName='{version}'" not in badging or "name='io.github.lrq3000.utterlane'" not in badging:
            raise ValueError("Release tag and APK identity/version do not agree")
        manifest = subprocess.check_output([str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
        if re.search(r"android:(debuggable|testOnly)\([^\n]*(?:0xffffffff|=\"true\")", manifest):
            raise ValueError("Refusing to publish a debug/test APK")
        self.output.mkdir(parents=True, exist_ok=True)
        signed_apk = self.output / f"Utterlane-{version}-arm64-v8a.apk"
        signed_bundle = self.output / f"Utterlane-{version}.aab"
        # The temporary keystore is removed even if a signing command fails.
        # Passwords are referenced by environment name, never command-line value.
        with tempfile.TemporaryDirectory(prefix="utterlane-sign-") as temporary:
            keystore = pathlib.Path(temporary) / "release.jks"
            keystore.write_bytes(base64.b64decode(os.environ["UTTERLANE_KEYSTORE_BASE64"], validate=True))
            keystore.chmod(0o600)
            subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
            subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(keystore),
                            "--ks-key-alias", os.environ["UTTERLANE_KEY_ALIAS"],
                            "--ks-pass", "env:UTTERLANE_STORE_PASSWORD", "--key-pass", "env:UTTERLANE_KEY_PASSWORD",
                            "--out", str(signed_apk), str(apk)], check=True)
            subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(signed_apk)], check=True)
            shutil.copy2(bundle, signed_bundle)
            subprocess.run(["jarsigner", "-keystore", str(keystore), "-storepass:env", "UTTERLANE_STORE_PASSWORD",
                            "-keypass:env", "UTTERLANE_KEY_PASSWORD", str(signed_bundle),
                            os.environ["UTTERLANE_KEY_ALIAS"]], check=True)
            subprocess.run(["jarsigner", "-verify", str(signed_bundle)], check=True)
        checksums = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}" for path in (signed_apk, signed_bundle)]
        (self.output / "SHA256SUMS").write_text("\n".join(checksums) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    ReleaseSigner(args.input, args.output).sign()
