# Single-speaker streaming test fixture

The maintainer authorized publication of this French/English one-speaker
recording and its three supplied reference/baseline transcripts on 2026-10-06.
These original inputs occupy **1,017,852 bytes** and are preserved byte for byte.
They are outside Android source/assets directories and are not bundled in the app.

- `test-1-speaker-french.m4a`: the supplied recording, approximately 25.45 seconds.
- `*_transcript_true-diarization.txt`: the supplied 70-word, one-speaker reference.
- `*_transcript_no-diarization.txt`: the supplied plain-transcription baseline.
- `*_transcript_current-diarization.txt`: the supplied **historical failing**
  output, not the current application's output. Its truncation and unknown
  labels are intentional regression evidence.

The reusable test source is already in the repository:

- `app/src/androidTest/java/io/github/lrq3000/utterlane/DiarizationFixtureAndroidTest.kt`
  generates fresh transcripts, word intervals, probability dumps and timings.
- `app/src/androidTest/java/io/github/lrq3000/utterlane/DiarizationEvidenceAndroidTest.kt`
  can replay generated evidence through the current attribution logic.
- `tools/qa/diarization_regression.py` scores wording, coverage, speakers and turns.
- `tools/qa/inspect_speaker_frames.py` inspects generated probability dumps.

## Reproduce

To inspect the supplied historical output, run from the repository root:

```console
python tools/qa/diarization_regression.py test_material/streaming_diarization_accuracy --recording test-1-speaker-french
```

For a new acoustic run, install matching app/test APKs and follow the model/device
setup in [the recovery guide](../../docs/qa/diarization-recovery.md#reproduction).
Select the intended ADB target. These commands use the ordinary package ID;
substitute an installed QA suffix in both package IDs when applicable:

```console
adb shell mkdir -p /sdcard/Download/diarization-qa
adb push test_material/streaming_diarization_accuracy/test-1-speaker-french.m4a /sdcard/Download/diarization-qa/
adb shell am instrument -w -e class io.github.lrq3000.utterlane.DiarizationFixtureAndroidTest#replay -e fixture test-1-speaker-french -e tag single-new io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

Use distinct tags for each run. Add `-e diarization false` for a paired plain run,
or `-e repeats 4` for a longer capture and score it with `--repeat-reference 4`.
Record the build, device, model identities, runtime options and warm-up policy
when comparing performance. Generated outputs belong in local `qa-artifacts/`,
not this input directory. See [the scoring guide](../../docs/qa/diarization-scoring.md)
for retrieving and evaluating those outputs. The two-speaker fixture is excluded.
