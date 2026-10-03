# Utterlane verification notes

Current application/test identity:
`io.github.lrq3000.utterlane` / `io.github.lrq3000.utterlane.test`.
First-party test classes live under `io.github.lrq3000.utterlane`.

The dated investigation reports in this folder describe actual historical runs
against their recorded commits. Old package names or local workspace paths in
those reports are evidence of the tested build, not current setup instructions.
Likewise, older implementation plans in `docs/superpowers` are historical records.

## Reusable commands

```text
gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s YOUR_DEVICE_SERIAL shell am instrument -w -e class io.github.lrq3000.utterlane.CapturePanelAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

Use `./gradlew` on Unix. Native/model tests require the selected artifacts on the
device. `ModelRecoveryAndroidTest` expects catalog-verified Q8 files under
`/sdcard/Download/parakeet-qa/<model-id>.gguf` and the speech fixture at
`/sdcard/Download/speech-source.wav`. `AudioIntegrationAndroidTest` also requires
the default model installed in the app. Consult each test before running it;
do not interpret a missing fixture as a recognition regression.

`tools/qa/audio_fixture.py` generates bounded audio fixtures and
`tools/qa/emulator_ui.py` captures screenshots/UI trees or taps visible labels.
Select an explicit ADB serial and use UI-tree bounds for interactions.

Branding assets can be regenerated with `tools/generate_brand_assets.py`; the
source image is hash-checked and its derived launcher masks have a visual preview
in `docs/design/utterlane-icon-preview.png`.

The [rebrand verification report](utterlane-rebrand.md) records the packaged-APK,
native inference, input-integration and visual checks. Rerun the source audit with
`python tools/qa/check_rebrand.py`; add `--aapt` and one or more `--apk` arguments
to inspect distribution artifacts too.
