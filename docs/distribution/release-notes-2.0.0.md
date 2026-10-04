# Utterlane 2.0.0

Fast, offline voice typing and audio transcription for Android. This major
release gives the independently maintained fork its own Utterlane identity and
Blue harmony interface, alongside incremental transcription, optional local
recording history, additional Parakeet models and improved session recovery.
The combined release also includes compact native ternary Redux, custom model
imports, optional streaming speaker labels, persistent app language, and idle
model unloading.

## Download

- **Android 8 or later, ARM64:** install `Utterlane-2.0.0-arm64-v8a.apk`.
- `Utterlane-2.0.0.aab` is for Google Play upload, not direct installation.
- `SHA256SUMS` contains artifact checksums. The signing certificate can be
  inspected with `apksigner verify --print-certs`.
- Obtainium and Komi Store can track the GitHub release APK.

Download or locally import a speech model before first use (approximately
159–674 MB for catalog models; custom models vary). Recognition then works offline; audio and transcripts are not
uploaded to a speech-recognition service.

Utterlane uses `io.github.lrq3000.utterlane` and installs separately from its
predecessor. Model files, preferences, permissions and history do not migrate
automatically. Development/debug builds can also have a different signing key;
export anything important before uninstalling a differently signed build.

F-Droid and a free Google Play listing are being prepared. Store availability
will be announced after review and publication.

See [the changelog](../../CHANGELOG.md) for details and
[the privacy policy](../../PRIVACY_POLICY.md) for local data handling.
