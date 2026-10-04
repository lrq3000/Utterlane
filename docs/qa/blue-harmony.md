# Blue harmony UI verification

Date: 2026-10-04. Worktree: `.worktrees/brand-ui-design`, branch `design/brand-ui`.
Base: `9730bbe` (includes the new first-launch Ultra Q8 selection).

## Delivered presentation

Revision-3 option A: source-derived transparent wordmark and icon, soft
violet/blue/cyan Settings header, clean icons, rounded section cards, blue primary
actions, matching navy dark mode, consistent dialogs, native recording panel and
floating microphone. Native controls observe the same appearance preference as
Compose. Active-recording red and destructive-action red are retained.

The wordmark is generated offline from the hash-checked original artwork. Existing
launcher/store artwork remains byte-identical. No additional runtime dependency.

## Commands and results

- `python tools/generate_brand_assets.py`: generated the two transparent wordmarks.
- `gradlew.bat --offline --console=plain --quiet testDebugUnitTest assembleDebug assembleDebugAndroidTest`:
  passed; 31 unit tests, zero failures/errors (15 pipeline, 4 capture telemetry,
  5 model catalog, 7 history). Debug and instrumentation APKs built.
- Installed with `adb -s emulator-5554 install -r`.
- `adb -s emulator-5554 shell am instrument -w -e class io.github.lrq3000.utterlane.CapturePanelAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner`:
  passed, one test; central stop button and silence/progress presentation still work.
- `python tools/qa/check_rebrand.py --aapt <SDK>/build-tools/35.0.0/aapt.exe --apk app/build/outputs/apk/debug/app-debug.apk`:
  passed source, original-art, document links and APK identity/DEX/JNI checks.
- `git diff --check`: passed.

## Visual evidence

Inspected actual LDPlayer API-28 screenshots in the existing French locale:

- Light and dark Settings, including the original logo and clean section icons.
- Model selection, dark dictionary fields and empty history dialog.
- Dark native recording waveform and processing/progress panel.
- Completed file transcription of the existing `speech-source.wav` fixture;
  text appeared with the blue Copy and tonal Share buttons.
- 320dp-wide Settings with font scale 1.3; actions wrap below model details rather
  than squeezing long names into a narrow column.
- Landscape Settings: the logo row is bounded instead of scaling with the screen.
- Landscape transcription: the completed result card is centered and width-capped.
- Removed a duplicate Compose scrim that produced a dark rectangular strip around
  the already-dimmed floating transcription dialog.

Screenshots/UI trees are retained locally in `qa-artifacts/harmony-*.png` and `.xml`.
The emulator display was restored to its original 1920×1080, font scale to 1.0,
and app appearance to System. Its French locale was not changed.

## Limits and runtime observations

Another session initially installed a different APK and held the UI-automation
connection. The operator paused that session; this build was reinstalled before
continuing. No conflicting build was used as final visual evidence.

Direct ADB launch of the non-focusable `VoiceInputActivity` produced null
accessibility roots and an Android ANR reporting **no focused window**. The actual
waveform and progress views were captured using `screencap`. That launch/focus path
is not changed by this restyle and was not fixed here; do not infer a successful
end-to-end voice-activity contract test from those screenshots. The focused native
panel test passed. Full IME/API recognition, populated-history actions, live
floating-mic interaction, Android 12+ dynamic-color behavior and Android 15
edge-to-edge rendering were not independently exercised in this pass.
