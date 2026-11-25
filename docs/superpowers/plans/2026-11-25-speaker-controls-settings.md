# Speaker Controls and Settings Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the two local milestones. Steps use checkbox syntax.

**Goal:** Restore the compact live recording layout and make every setting, especially the speaker model, easy to find.

**Architecture:** Preserve existing settings controls behind stable page routes. Connect missing-model recovery to the speaker page without changing inference or automatically moving the user.

**Tech Stack:** Android native Views, Compose Material 3, Kotlin, Android instrumentation and Robolectric.

**Specs:** [Live speaker control](../specs/2026-11-25-speaker-control-design.md) and [Settings navigation](../specs/2026-11-25-settings-navigation-design.md).

## Global Constraints

- Keep capture responsive and offline. Do not start downloads or change defaults when opening Settings.
- Preserve existing settings, intent routes and Android permission/model picker handoffs.
- Commit the live control first, the settings change second; build the normal-identity debug APK without pushing.

## Review Focus

- Compare panel height with and without the speaker control, and verify title and toggle do not overlap.
- Verify checkability, tooltip, and colors in both palettes.
- Verify file setup retains its selected audio and toggle while visiting Settings.
- Verify ordinary, deep-linked and speech-recovery Settings navigation and Back.
- Check every existing control against the baseline Settings inventory and exercise all new destinations.

### Task 1: Header speaker icon

- [x] Add a real-view regression for `RecordingPanel.bind` measuring capture height with and without `LiveSpeakerControl`.
- [x] Place the toggle in the listening header and apply standard native checked-state colors and tooltip.
- [x] Run the focused test and build before committing only this milestone.

### Task 2: Settings and model recovery

- [x] Record the baseline inventory and test menu completeness and the speaker-model recovery route.
- [x] Add route state and destination UI to `SettingsActivity` while reusing its existing controls, activity results, and models.
- [x] Point existing file, result and microphone speaker warnings at the speaker-label destination. Preserve fallback and avoid requiring the speaker model for fixed-one mode.
- [x] Run focused checks, audit the inventory, run the JVM suite and debug/instrumentation builds, and commit this milestone.
