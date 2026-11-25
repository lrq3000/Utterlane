# Restricted Settings Help Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Help eligible users find Android's user-controlled Restricted Settings option without adding a wizard step.

**Architecture:** Read the installer of record once in the Android onboarding adapter. Compute card visibility from SDK, installer, and live permission state. Route the button through the existing app-details intent.

**Tech Stack:** Kotlin, Jetpack Compose, Android `PackageManager`, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-25-onboarding-restricted-settings-design.md`.

## Global Constraints

- No network, additional permissions, automatic grants, new onboarding screens, or changes to the accessibility disclosure and skip path.
- Installation from a non-Play store or missing installer metadata is a reason to offer conditional help, not evidence of a denial.

## Review Focus

- Android 12L and below: no help and no installer lookup.
- Google Play installer: no help even when both permissions are missing.
- No installer record: optional help, not a claim of a blocked permission.
- Exactly one permission missing: help remains; both enabled: help disappears.
- The expected menu item may be absent until after a denial, or unavailable on the device: wording leaves the normal setup and skip paths open.

---

### Task 1: Gate and display the recovery card

**Files:** `OnboardingFlow.kt` (visibility rule), `OnboardingFlowTest.kt` and `OnboardingSystemSettingsTest.kt` (regression), `OnboardingServices.kt` and `AppOnboardingServices.kt` (device state and Android metadata), `OnboardingSetupPages.kt` and `values/onboarding_strings.xml` (card and copy).

**Interface:** `OnboardingPermissions.shouldShowRestrictedSettingsHelp(sdkInt: Int, installerPackage: String?): Boolean`. `Context.onboardingInstallerPackage(): String?` returns the installer of record on API 33+. `OnboardingDeviceState.showRestrictedSettingsHelp` controls the card on the input page.

- [ ] Write tests covering SDK 32/33/current; Play, other installer, and no installer record; neither, either, and both permissions granted. Test installer-of-record vs. initiator, the missing metadata fallback, and accessibility configured but not yet connected.
- [ ] Run `./gradlew --offline :app:testDebugUnitTest --tests '*OnboardingFlowTest' --tests '*OnboardingSystemSettingsTest'` with JDK 21. Verify the red result identifies the absent visibility and lookup rules.
- [ ] Implement the rule and wire live state, installer metadata, and the existing `AndroidSetup.APP` action. Describe the user-owned system menu and how to continue if it is not present.
- [ ] Run `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest` with JDK 21. Verify assembly and the onboarding regressions, inspect the final diff, and record any device-only validation gap.
- [ ] Commit the verified local change as `feat(onboarding): guide sideloaded installs through restricted settings`.

**Device check:** On an Android 13+ test device with a fresh outside-Play install, visit the shortcuts page and tap **Open app info**. Verify that the system screen identifies this Utterlane package. Return with Back, check that the same wizard page and skip action remain available, then retry the desired permissions. Record whether the device shows the override before or only after a denial. A real Google Play installation can be checked once one is available.
