"""Publication must fail closed without credentials or a real release revision."""
import os
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from fdroid_metadata import FdroidMetadata
from sign_release import ReleaseSigner


class PublishingGuardsTest(unittest.TestCase):
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
