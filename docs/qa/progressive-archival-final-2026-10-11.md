# Progressive archival — final implementation and verification

## Delivery location

All final source, build outputs and QA artifacts are in **`C:\git\TranSlander`**,
branch **`feat/progressive-archival-project`**, based on
`72dc059cc9684e64b5f38f10d02daa54c8158086`. The user requested this location after
initial work in an isolated worktree. Both the archival implementation and the
newer microphone/HFP/pinch changes were integrated and independently reviewed.

The implementation was verified before publication; the subsequently authorized
Git delivery is recorded in [the main-first intent ledger](progressive-archival-main-first-2026-10-11.md).
The four pre-existing two-speaker audio/reference files remain untracked and
unchanged. The transfer backup is retained at stash object
`c4f391534a60522ec23797668292c5e0b1d47160`; it was applied, not dropped.

## Implemented contract

- Normal retained imports share one Home/Share/Open policy. History-enabled imports
  ask above ten minutes, or above **50 MiB** for unknown/short duration. Independent
  remembered keep/discard choices and reset controls are available. Original files
  from other applications are never owned or deleted; compressed imports are
  copied unchanged even when saved-audio compression is enabled.
- Compression defaults off, with native **16-kHz mono PCM16 WAV** capture/storage.
  Optional collapsed quality controls validate format/rate/channel/bitrate tuples.
  The default AAC tuple is 16-kHz mono / 96 kbps; unsupported settings are not
  silently claimed. The descriptions include both requested storage and
  re-transcription-accuracy sentences.
- Actual capture formats, route choices, HFP/source/effect options, frozen reopen
  configuration and normalized ASR gain coexist. Archival receives original PCM;
  active recognition does not decode its lossy archival derivative.
- The isolated archival worker encodes progressively before Stop. Durable encoded
  checkpoints and independent consumer positions gate rolling PCM reclamation.
  Model failure/lag retains original backlog. Force-stop preserves capture and
  authoritative encoded-prefix/PCM-tail recovery. Finalization does not retain
  microphone ownership or block another recording.
- A parent foreground supervisor owns the archival notification and **Force stop
  all archival jobs** control, durable cancellation and late-callback fencing.
  Pending fallback repair/publication remains retryable without restarting a stopped
  codec. Job scheduling/maintenance uses bounded working memory and disk indexes.
- SimpleStorage selects separate audio and Markdown folders and preserves URI
  grants. Successful audio publication reclaims private staging only after readers
  release it. Markdown is streamed from the full transcript, named by datetime and
  actual model, and linked to transcript ownership. Existing Delete/prune removes
  linked files; pin/Forever protects them. Unavailable destinations preserve useful
  source and durable pending cleanup. Export metadata and bytes share one snapshot.
- `CORE_VALUES.md` contains the user's general principle verbatim and the two
  explicitly labelled rolling-streaming/live-archival examples.

## Verification summary

| Check | Evidence |
|---|---|
| JVM | **1,090 tests, zero failures/skips** on the integrated root tree; final verification command confirmed up-to-date results |
| Publishing/build helpers | **13 Python tests passed** in final parent verification |
| Android functional suites | **24 distinct native cases passed**, plus the documented native replay/quality-gate follow-up; one C2 encoder case skipped because absent on API 28 |
| Debug / instrumentation APK | Built successfully in the root checkout |
| Minified release APK / AAB | Built successfully with R8 and release vital lint |
| Native packaging | All three app artifacts contain six ARM64 libraries, passing ELF 16-KiB alignment; release ZIP alignment passed |
| Release compatibility metadata | compile SDK 37, minimum API 26, target API 36, version 2.1.0 / 210; release is not debuggable |
| Minified runtime smoke | Locally debug-key-signed release installed and completed real-model file transcription, including the final source words |
| Reviews | Toolchain, import, managed archival, compression, root/HFP integration and final QA evidence independently reviewed; actionable findings fixed |

Final root command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
python -m unittest discover -s tools/tests -v
python tools/release_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/bundle/release/app-release.aab
```

The final Gradle command succeeded in **4m 8s**, 205 tasks (106 executed, 99
up-to-date). This included root release-native compilation and minification, not
replacement with another checkout's APK. Build Tools 36.0.0 `zipalign -c -P 16 4`
and `aapt dump badging` separately confirmed release alignment and SDK/ABI metadata.

## Speed, fidelity and native variation

The same 25.449125-second fixture, verified Parakeet Ultra Q8_0 model, runtime
options and emulator gave **50.715 s OFF / 50.458 s ON** in the representative
fresh-worker ASR-window comparison. This is a within-build compression comparison,
not a pre-change benchmark or a speed improvement claim. Source/accepted/processed
counts were **407,186** in each arm; every actual worker-input window hash matched.

Both arms retained all 70 reference words and the final 12 words. OFF inserted an
extra “en fait”, while ON matched the reference. One archival-free control reproduced
both exact native token variants from the same PCM in the same worker with no
archival worker running. Exact generated-text equality was therefore an overstrong
determinism assumption, not a demonstrated archival PCM regression. The replacement
fixture gate still checks exact PCM/count/context, all reference words in order,
the observed two-insertion ceiling and exact final words; negative controls fail.
The underlying native/runtime cause is not identified, and universal deterministic
transcripts or handset performance are not claimed.

See [the recording report](progressive-archival-recording-2026-10-11.md) for original
failed assertions, actual-model provenance, control evidence and platform limits.

## Minified release smoke

`qa-artifacts/app-release-local-qa.apk` is a **local QA copy signed with the ordinary
debug key**, not a distributable production-signed release. Signature verification
passed before installation on the same explicitly owned `emulator-5554`.

The minified app launched Home and transcribed its existing local fixture through
the normal `TranscribeActivity` file path. Scoped logs record committed intervals
`0..160000`, `160000..277246`, and `277246..407186`. The resulting dialog displays
“Transcript complete”, 100%, “Audio saved in history”, 25-second playback, and the
final “capture bien ou pas”. No AndroidRuntime error appears in the scoped log.

Evidence: `qa-artifacts/release-smoke.png`, `release-smoke-tree.xml`, and
`release-smoke-logcat.txt`. The first `/dev/tty` XML attempt only returned a dump
status line; the separate on-device-file extraction is the valid hierarchy.

## Boundaries and remaining release gates

- Native execution used **Android 9 / API 28**, including actual 16-kHz mono and
  44.1-kHz stereo AudioRecord, OMX AAC, AMR-NB/WB, real SAF grants and linked file
  deletion. C2 AAC, Android 14/15+ foreground-service behavior and physical Bluetooth
  hardware/thermal behavior still require suitable targets.
- Full F-Droid execution is unavailable here: no installed CLI or fdroiddata
  checkout. Its historical SDK/Gradle recipe constraints are explicitly documented
  in `docs/distribution/README.md`; no unsupported publishing claim is made.
- New UI strings use English fallback pending the normal translation batch.
- Prior incremental Kotlin/Compose generated-field mismatches were diagnosed and
  repaired with targeted recompilation, not blanket native/cache cleaning. Earlier
  Windows Java filesystem intermittence was reproduced outside app code; final root
  JVM verification is green, but its external cause remains unknown. Details are in
  [the investigation](progressive-archival-jvm-deletion-2026-10-11.md).
- Extreme MP4 sample-table limits stop optional encoding and preserve mixed input;
  automatic splitting at that extreme limit is not implemented. Device storage and
  delayed-consumer disk backlog remain physical limits, not silent input loss.

## Runtime cleanup

After evidence preservation, session-owned **agent-progressive-archival-a418f9**
(index 0) was stopped and its ownership released. `list2` reports Android stopped
and player/VM PIDs `-1`; `adb devices` is empty. The saved original player **11704**
and VM **39792** identities have nonzero Windows exit times and exit code zero.
The initial checker incorrectly waited for their retained kernel objects to become
unqueryable; checking actual exit state established that both processes had ended.

No other running instance appeared in the final inventory. Disposable instance
storage is retained for review/reopening until the project/PR retirement checkpoint;
there is no accepted keep-running request or active delegated consumer.
