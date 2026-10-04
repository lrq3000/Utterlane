# Utterlane 2.0 store preparation

Approved in conversation: prepare F-Droid and a **free Google Play** release in
an isolated worktree. GitHub Releases, Obtainium and Komi Store remain direct
installation channels. IzzyOnDroid is out of scope.

## Release and distribution

- Keep application ID `io.github.lrq3000.utterlane`, ARM64, and Android 8 minimum.
- Bump to 2.0.0 / 11 and describe the accumulated fork improvements.
- Target API 36, now required for new Play submissions, with a supported AGP.
- Produce unsigned release APK/AAB artifacts without credentials; enable release
  signing only with a complete externally supplied signing configuration.
- Publish signed APKs from tagged GitHub releases; provide the AAB for Play upload.
  Keep signing secrets out of PR builds and source control.
- Use ordinary F-Droid signing initially. Do not claim interchangeable signing
  across stores or reproducible APKs without comparison evidence.

## Native dependencies

The existing sherpa builder delegates to a script downloading ONNX Runtime from
a GitHub binary release. Replace that path with a portable source builder using
the official MIT-licensed Android runtime from Maven Central (an allowed F-Droid
binary repository). Pin and verify the dependency and source revisions. Compile
sherpa with NDK 28 and verify **every** shipped ARM64 ELF's load alignment as well
as APK ZIP alignment for 16 KB pages. Keep CrispASR/ggml source revisions pinned.
F-Droid must obtain native sources before scanning and compile after scanning;
no blanket binary scan exemptions.

## Assets and publication materials

Capture real English portrait Settings and transcription-dialog screenshots on
LDPlayer using public-domain/test speech, then restore the emulator locale/display.
Reuse these in Fastlane and README. Add GitHub/Obtainium buttons and concrete
Komi Store steps. Label F-Droid/Play availability as pending until actually live.

Provide F-Droid metadata generation bound to an actual release commit, and a
publication guide covering signing, build checks, source provenance, store assets,
privacy, Accessibility API, microphone/special-use foreground services, content
rating and applicable Play account testing requirements. The maintainer supplies
account-specific answers, signing credentials and permission demonstration videos.

## Verification and limitations

Run focused tooling tests, JVM tests, debug/release APK and release AAB builds,
manifest/version/native checks, and emulator inference/UI checks. Attempt F-Droid
lint/scanning with available tooling and distinguish local checks from a full
Linux F-Droid build. Record unperformed modern-Android/16 KB runtime checks and
account-dependent publishing steps explicitly. No public submissions, tagging,
commits or pushes are authorized by this preparation request.
