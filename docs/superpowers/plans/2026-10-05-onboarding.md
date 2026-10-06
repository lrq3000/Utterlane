# Native Onboarding Implementation Plan

> **For agentic workers:** Execution was inline with the executing-plans skill;
> the review workflow also supplied a read-only code review. The maintainer
> approved implementation, then requested a latest-main replay and local squash
> merge. Publishing was not requested.

**Goal:** Deliver the approved onboarding as a working, resumable native Android wizard.

**Architecture:** A declarative page registry and pure navigation/recommendation
policy sit in an isolated onboarding package. A ViewModel combines its private
progress store with an adapter around existing app services. Compose pages know
only the onboarding state/actions; Android handoffs stay in the Activity.

**Tech Stack:** Existing Kotlin, Compose Material 3, lifecycle ViewModel,
DataStore, coroutines, Android activity-result APIs and FileProvider. No new
runtime framework or server.

---

## File responsibilities

All Kotlin production paths below are under
`app/src/main/java/io/github/lrq3000/utterlane/onboarding/`:

| File | Responsibility |
| --- | --- |
| `OnboardingFlow.kt` | Stable steps, conditional traversal, launch and RAM policies |
| `OnboardingRepository.kt` | Separate progress DataStore and draft choices |
| `OnboardingServices.kt` | State/data contracts and app-facing operations |
| `AppOnboardingServices.kt` | Existing managers/settings/service adapter |
| `OnboardingViewModel.kt` | User actions, transfer ownership and navigation |
| `OnboardingActivity.kt` | Permission/settings/folder/share handoffs and lifecycle |
| `OnboardingContent.kt` | Ordered localized page descriptors |
| `OnboardingScreen.kt` | Theme selector, progress, scrollable host and footer |
| `OnboardingIntroPages.kt` | Welcome, everyday uses and vertical summary |
| `OnboardingSetupPages.kt` | Models, downloads and optional setup cards |
| `OnboardingTrialPages.kt` | Real dictation field and sample sharing page |
| `OnboardingArt.kt` | Themed composition of bundled native vector layers |
| `OnboardingVoiceTrial.kt` | Bounded capture callbacks and cleanup |
| `OnboardingSample.kt` | Bundled sample export and native share intent |
| `LocalAudioFolder.kt` | Validated local document-tree → filesystem mapping |

Existing integration changes: `settings/SettingsActivity.kt`,
`settings/SettingsRepository.kt`, and `app/src/main/AndroidManifest.xml`.
New resources: `app/src/main/res/values/onboarding_strings.xml`,
`app/src/main/res/drawable/onboarding_*.xml`, and
`app/src/main/assets/onboarding/` (audio and provenance).

## Task 1 — policies and persisted flow

- [x] Add failing tests in
  `app/src/test/java/io/github/lrq3000/utterlane/onboarding/OnboardingFlowTest.kt`
  and `LocalAudioFolderTest.kt`. Boundary assertions include:

  ```kotlin
  assertEquals(ModelTier.COMPACT, ModelRecommendation.tier(1L shl 30))
  assertEquals(ModelTier.BALANCED, ModelRecommendation.tier((1L shl 30) + 1))
  assertEquals(ModelTier.BALANCED, ModelRecommendation.tier(2L shl 30))
  assertEquals(ModelTier.FULL, ModelRecommendation.tier((2L shl 30) + 1))
  assertNull(ModelRecommendation.tier(0))
  ```

- [x] Run focused `testDebugUnitTest --tests '*OnboardingFlowTest' --tests
  '*LocalAudioFolderTest'`; observe missing-feature failures before implementation.
- [x] Implement `ModelTier`, `ModelRecommendation`, `OnboardingStep`,
  `OnboardingFlow`, and `OnboardingProgress`. Precompute ID/index maps for each
  conditional route, so next/back lookup is O(1).
- [x] Implement the progress repository with atomic DataStore edits. An existing
  saved setting or installed model identifies a configured installation only
  before the onboarding entry policy has first been initialized.
- [x] Rerun the focused tests and verify inclusive boundaries, resume, replay,
  conditional download, duplicate IDs, unknown/removed IDs and folder traversal.

## Task 2 — service adapter and controller

- [x] Define onboarding-owned snapshots for permissions, preferences, featured
  models, transfers and trial state. Expose manager operations behind the
  `OnboardingServices` interface rather than accessing UtterlaneApp in pages.
- [x] Implement the app adapter using `RecognizerManager.selectModel`,
  `ModelManager.downloadModel/importFromFolder/ensureVerified`, existing settings,
  and existing monitor/floating services. Keep download names/bytes in the catalog.
- [x] Implement ViewModel actions with one pending operation at a time; show
  errors in state, preserve cancellation, and disable conflicting model changes.
- [x] Persist choice/step before leaving for Android settings. Re-query actual
  permission and IME/accessibility state in `onResume`.
- [x] Enable speaker labels only after verification. Skip a new optional choice
  without turning off previously enabled settings during replay.

## Task 3 — native host, resources and setup pages

- [x] Add localized resource keys for approved copy and actions. Reuse existing
  theme, permission disclosure and generic action resources where appropriate.
- [x] Bundle vector layers from revision 03. Render layers with Compose theme
  colors, so manually selected dark mode also colors the artwork correctly.
- [x] Add page descriptors and renderer dispatch. Implement a shared header
  containing Back/phase and System/Light/Dark on every screen.
- [x] Implement filled model cards, actual transfer state, input-method setup,
  folder opt-in and speaker opt-in. Use wrapping layouts and semantic controls;
  screen bodies scroll while footer controls remain visible.
- [x] Add the source-details dialog for the published 36.2/164 WPM comparison.
  Keep the cross-study limitation alongside the original-source links.

## Task 4 — real trials and reusable sample

- [x] Add the microphone trial using `UtterlaneApp.microphoneSessions.create`.
  Use `TranscriptStore.preview()` for bounded updates and release the completed
  store. Keep the result editable once recording/processing finishes.
- [x] Stop capture when the Activity becomes non-visible; guard late callbacks
  and double starts. A two-minute trial cap prevents accidental long recordings.
- [x] Bundle a public-domain excerpt with its source, digest and attribution.
  Actual inference showed the initial Apollo radio clip was a poor first-use
  sample for Redux; the final asset is a clearer nine-second LibriVox reading
  sourced from Wikimedia Commons. Preserve both original and excerpt hashes.
- [x] Share the cache copy through the existing FileProvider:

  ```kotlin
  Intent(Intent.ACTION_SEND).apply {
      type = "audio/wav"
      putExtra(Intent.EXTRA_STREAM, uri)
      clipData = ClipData.newRawUri("Sample audio", uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  ```

- [x] Verify the share chooser includes Utterlane and the real transcription
  activity returns to the still-resumable wizard. Both trials remain skippable.

## Task 5 — launch/replay integration and Android checks

- [x] Register the internal onboarding Activity. Gate launcher Settings before
  rendering its UI and add a Settings action to replay the guide.
- [x] Add `app/src/androidTest/java/io/github/lrq3000/utterlane/OnboardingAndroidTest.kt`
  for repository persistence, sample readability, actual Activity navigation,
  persistent appearance and completion. Use existing AndroidJUnit/UiAutomation.
- [x] Build an isolated QA identity with `-PqaApplicationIdSuffix=.onboarding`.
  Run focused unit tests, `assembleDebug` and `assembleDebugAndroidTest` in normal
  incremental mode, without forced rebuilds.
- [x] Install only the isolated QA identity on the selected emulator. Inspect
  Welcome, model selection, optional setup, voice trial and summary in light/dark,
  narrow portrait and enlarged text. Exercise denial/return, Back, configuration changes,
  restart, replay and the sample share handoff.
- [x] Record results in `docs/qa/onboarding.md`, update the user/developer guide,
  and inspect the final diff. Report environmental limits separately from passes.

## Plan self-review

The tasks cover all twelve approved states, appearance on every page, the exact
1/2 GiB boundaries, optional permission branches, real trials, bundled art/audio,
and completion/replay. UI and model engines meet only at the services adapter;
page order and localized content are separate from action implementations.

## Verification-driven additions

Targeted regressions and device runs also covered queued picker results during
initialization, late-recording prevention, initialization retry, early appearance
selection, unavailable-folder removal, failed edits preserving existing monitoring,
speaker-count preservation, raw local Downloads trees, DNS cancellation, and the
pre-29 FileObserver compatibility correction. The QA report records the actual
device coverage and remaining platform/translation limits.
