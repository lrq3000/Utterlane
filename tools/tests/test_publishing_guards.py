"""Publication must fail closed without credentials or a real release revision."""
import base64
import os
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from fdroid_metadata import FdroidMetadata
from sign_release import ReleaseSigner


class PublishingGuardsTest(unittest.TestCase):
    def test_release_tag_preserves_main_prerelease_support(self):
        for tag in ("v2.0.0", "v2.0.0-beta.1", "v2.0.0-alpha", "v2.0.0-rc.2"):
            self.assertEqual(tag[1:], ReleaseSigner.release_version(tag))
        for tag in ("main", "v2.0.0-", "v2.0.0-../escape", "v2.0.0;command"):
            with self.assertRaises(ValueError):
                ReleaseSigner.release_version(tag)

    def test_missing_signing_credentials_cannot_publish(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "signing secrets"):
                ReleaseSigner(pathlib.Path("missing-input"), pathlib.Path("unused-output")).sign()

    def test_nonrelease_tag_is_rejected_before_opening_keystore(self):
        env = dict.fromkeys(("UTTERLANE_KEYSTORE_BASE64", "UTTERLANE_STORE_PASSWORD",
                             "UTTERLANE_KEY_ALIAS", "UTTERLANE_KEY_PASSWORD"), "not-a-real-key")
        env["RELEASE_TAG"] = "main"
        with patch.dict(os.environ, env, clear=True):
            with self.assertRaisesRegex(ValueError, "stable"):
                ReleaseSigner(pathlib.Path("missing-input"), pathlib.Path("unused-output")).sign()

    def test_signing_uses_only_build_tools_installed_on_clean_ci_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source, output = root / "input", root / "output"
            for name in ("apk/release/app-release-unsigned.apk", "bundle/release/app-release.aab"):
                artifact = source / name
                artifact.parent.mkdir(parents=True, exist_ok=True)
                artifact.write_bytes(b"unsigned fixture")
            tools = root / "sdk/build-tools/36.0.0"
            tools.mkdir(parents=True)
            for name in ("aapt", "zipalign", "apksigner"):
                (tools / name).touch()
            env = {
                "ANDROID_HOME": str(root / "sdk"),
                "UTTERLANE_KEYSTORE_BASE64": base64.b64encode(b"dummy keystore").decode("ascii"),
                "UTTERLANE_STORE_PASSWORD": "dummy-store-password",
                "UTTERLANE_KEY_ALIAS": "dummy-alias",
                "UTTERLANE_KEY_PASSWORD": "dummy-key-password",
                "RELEASE_TAG": "v2.1.0",
            }
            used_tools = []

            def check_tool(command):
                executable = pathlib.Path(command[0])
                self.assertTrue(executable.is_file(), f"Uninstalled build tool: {executable}")
                used_tools.append(executable.name)

            def inspect_apk(command, **kwargs):
                check_tool(command)
                if command[2] == "badging":
                    return "package: name='io.github.lrq3000.utterlane' versionName='2.1.0'"
                return "E: manifest\n  E: application\n"

            def sign_artifact(command, **kwargs):
                if command[0] != "jarsigner":
                    check_tool(command)
                if "--out" in command:
                    pathlib.Path(command[command.index("--out") + 1]).write_bytes(b"signed fixture")

            # Exercise the real signer flow; only archive validation and external
            # executables are mocked. No real SDK, keystore or credentials are used.
            with patch.dict(os.environ, env, clear=True), \
                    patch("sign_release.NativeArchive.verify"), \
                    patch("sign_release.subprocess.check_output", side_effect=inspect_apk), \
                    patch("sign_release.subprocess.run", side_effect=sign_artifact):
                ReleaseSigner(source, output).sign()

            self.assertEqual(["aapt", "aapt", "zipalign", "apksigner", "apksigner"], used_tools)
            self.assertEqual(b"signed fixture", (output / "Utterlane-2.1.0-arm64-v8a.apk").read_bytes())
            self.assertEqual(b"unsigned fixture", (output / "Utterlane-2.1.0.aab").read_bytes())
            self.assertTrue((output / "SHA256SUMS").is_file())

    def test_fdroid_recipe_rejects_tags_short_hashes_and_tokens(self):
        for value in ("v2.0.0", "cad85a1", "@RELEASE_COMMIT@", "g" * 40):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "40-character"):
                FdroidMetadata().render(value)

    def test_fdroid_recipe_rejects_old_version_and_partial_version_match(self):
        for gradle in ('versionName = "1.2.4"\nversionCode = 10',
                       'versionName = "2.0.0"\nversionCode = 110'):
            with patch("fdroid_metadata.subprocess.check_output", return_value=gradle):
                with self.assertRaisesRegex(ValueError, "2.0.0 / 11"):
                    FdroidMetadata().render("a" * 40)


if __name__ == "__main__":
    unittest.main()
