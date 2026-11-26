"""Exercise notice handling without contacting upstream or running the Android toolchain."""
import hashlib
import pathlib
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

# The standalone script imports its sibling release_artifacts module as a CLI.
with patch.object(sys, "path", [str(pathlib.Path(__file__).resolve().parents[1]), *sys.path]):
    from build_sherpa import ORT_VERSION, SHERPA_REVISION, SOURCES, SherpaBuilder


class SherpaNoticesTest(unittest.TestCase):
    def test_prepare_with_pinned_sources_does_not_download_notices(self):
        with tempfile.TemporaryDirectory() as directory:
            builder = SherpaBuilder(pathlib.Path(directory))
            builder.source.mkdir()
            for name in SOURCES:
                (builder.deps / name).mkdir(parents=True)

            # Model already prepared sources and the 404 from the retired notice URLs.
            with patch("subprocess.check_output", side_effect=[SHERPA_REVISION + "\n", ""]), \
                    patch("urllib.request.urlopen", side_effect=HTTPError(
                        "https://raw.githubusercontent.com/microsoft/onnxruntime/", 404, "Not Found", {}, None)):
                try:
                    builder.prepare()
                except HTTPError as error:
                    self.fail(f"Source preparation unexpectedly needed a notice download: {error}")

    def test_packages_the_original_license_and_third_party_notices(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            notices = pathlib.Path(__file__).resolve().parents[1] / "native-licenses" / f"onnxruntime-{ORT_VERSION}"
            shutil.copytree(notices, root / "tools/native-licenses" / notices.name)
            assets = root / "assets/native-licenses"
            SherpaBuilder(root).stage_onnxruntime_notices(assets)

            self.assertEqual(
                {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                 for path in (assets / "onnxruntime").iterdir()},
                {
                    "LICENSE": "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
                    "ThirdPartyNotices.txt": "e9e90971a8e75a9a8ac0c6412e29c1202d079998389915aa485f46c816c3b4cc",
                },
            )

    def test_refuses_modified_notices(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            notices = root / "tools/native-licenses" / f"onnxruntime-{ORT_VERSION}"
            notices.mkdir(parents=True)
            (notices / "LICENSE").write_bytes(b"modified notice")

            with self.assertRaisesRegex(ValueError, "SHA-256"):
                SherpaBuilder(root).stage_onnxruntime_notices(root / "assets/native-licenses")
