"""Render submission metadata only for an actual 2.0.0 / 11 release commit.

The release SHA cannot be embedded in its own commit. Keep the reviewed recipe
as a template and render the separate fdroiddata file after committing/tagging.
"""
import argparse
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "docs/distribution/fdroid/io.github.lrq3000.utterlane.yml.in"


class FdroidMetadata:
    def render(self, commit):
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise ValueError("Provide the full 40-character release commit hash")
        gradle = subprocess.check_output(["git", "show", f"{commit}:app/build.gradle.kts"], cwd=ROOT, text=True)
        if not re.search(r'versionName\s*=\s*"2\.0\.0"', gradle) or not re.search(r"versionCode\s*=\s*11\b", gradle):
            raise ValueError("The selected commit does not contain version 2.0.0 / 11")
        return TEMPLATE.read_text(encoding="utf-8").replace("@RELEASE_COMMIT@", commit)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    text = FdroidMetadata().render(args.commit)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Never overwrite another packager's existing metadata without a review.
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(text)
    print(f"Wrote {args.output}")
