# Build-toolchain milestone — 2026-10-10

Status: **DONE_WITH_CONCERNS**. Verified in Windows worktree
`C:\git\TranSlander\.worktrees\progressive-archival`, branch
`feat/progressive-archival`, baseline `f6975ce9dbc1565325ab461e2ec680a2e7b12018`.
No application source, model/native revision, or release version changes were needed.

## Migration decisions

- Gradle **9.4.1**, AGP **9.2.1**, Kotlin and Compose compiler **2.4.21**.
  Both the root build and standalone sherpa helper explicitly override AGP's
  runtime KGP dependency using `buildscript.dependencies.classpath`. The redundant
  Kotlin Android plugin is removed; typed `compilerOptions` replaces `kotlinOptions`.
- App, transcribe-native, and standalone sherpa helper compile against **37**.
  The SDK package is `platforms;android-37.0`; AGP's default/minimum Build Tools
  version is **36.0.0**. Target **36**, minimum **26**, JDK **21**, and NDK
  **28.2.13676358** are preserved. Sherpa bindings retain their JVM 8 bytecode target.
- SimpleStorage **3.1.0** added. Direct AndroidX dependencies align with its
  published requirements: Core **1.18.0**, Activity **1.13.0**, AppCompat **1.7.1**,
  DocumentFile **1.1.0**, Lifecycle **2.10.0**, coroutines **1.11.0**.
  Fragment resolves transitively to **1.8.9**, app Kotlin stdlib to **2.4.21**.
- The existing Compose BOM remains unchanged. The resolved graphs differ:
  - **Debug:** UI/runtime, foundation/foundation-layout, and animation/animation-core
    resolve to **1.9.2**, with debug-only UI tooling in the graph.
  - **Release:** UI/runtime resolve to **1.9.2**, while foundation/foundation-layout
    and animation/animation-core resolve to **1.7.0**.
  - Both retain Material3 **1.2.0** and material icons/ripple **1.6.1**. Compilation
    and R8 pass; device UI behavior was not exercised in this build-only milestone.
- AGP 9 rejects generated asset Providers in legacy source sets. CrispASR notices
  now use the Variant API and a task-owned `@OutputDirectory`, preserving the copy
  task dependency and the original packaged paths/content.
- The sherpa helper explicitly uses the public `LibraryExtension` DSL and
  `AndroidSourceSet.kotlin.directories`. Its inferred legacy library source-set
  accessor otherwise throws `ClassCastException` with AGP 9.

## Verification

All commands below ran from the worktree root. Java on PATH was Oracle JDK
21.0.5; `JAVA_HOME` was unset. `ANDROID_HOME` was
`C:\Users\33632\AppData\Local\Android\Sdk`. SDK installation was checked before
builds; SDK 37.0 and Build Tools 36.0.0 were already present. No clean, forced
rerun, shared-source preparation, or cache/daemon deletion was used.

```powershell
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --list_installed
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" "platforms;android-37.0" "build-tools;36.0.0"

python -m unittest discover -s tools/tests

.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain

.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath :app:buildEnvironment --console=plain --offline
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --console=plain --offline
.\gradlew.bat buildEnvironment --console=plain --offline
.\gradlew.bat -p tools/sherpa-android help --console=plain --offline

python tools/release_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/bundle/release/app-release.aab
& "$env:ANDROID_HOME/build-tools/36.0.0/zipalign.exe" -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging app/build/outputs/apk/release/app-release-unsigned.apk
git diff --check
```

Results:

- Python tools: **13 tests passed** after the review follow-up (12 in the initial
  run, plus the focused signing-tool selection regression test).
- JVM: **486 tests in 78 suites**, zero failures/errors/skips, 7.259 s test time.
  Debug/instrumentation build completed in **6m 54s**. Android tests were compiled
  and packaged, not executed on a device.
- Combined incremental verification including minified APK/AAB: **BUILD SUCCESSFUL
  in 4m 45s**, 202 tasks (110 executed, 92 up-to-date). R8 and `lintVitalRelease`
  executed successfully. JVM/tests were up-to-date after the asset-only correction.
- App/native debug and release builds compiled CrispASR and transcribe.cpp using
  the shared sources as inputs and this worktree's `.cxx` output directories.
- Root classpath resolves AGP **9.2.1**, KGP/Compose compiler **2.4.21**; app
  dependencies resolve the versions above without an AAR metadata error.
- Debug APK, release APK, and AAB each contain **six ARM64-only libraries**, all
  passing the existing ELF 16 KB check. Release APK passes ZIP alignment. Libraries:
  `libandroidx.graphics.path.so`, `libomp.so`, `libonnxruntime.so`,
  `libsherpa-onnx-jni.so`, `libutterlane_crisp.so`, `libutterlane_transcribe.so`.
- Release badging: package `io.github.lrq3000.utterlane`, version **210 / 2.1.0**,
  compile SDK **37**, min SDK **26**, target SDK **36**. Merged release manifest has
  neither `debuggable` nor `testOnly`; `extractNativeLibs=false`.
- ZIP inspection verified both `assets/crispasr/LICENSE` and
  `assets/crispasr/THIRD_PARTY_NOTICES.txt` byte-for-byte against source in all three
  artifacts, and uncompressed native libraries in both APKs.
- Parsed `BundleConfig.pb` using bundletool 1.18.3/protobuf 3.25.5 classes:
  `uncompress_native_libraries.enabled=true`, `alignment=PAGE_ALIGNMENT_16K`.

### Standalone helper verification

The supplied app sherpa AAR was previously source-built. The shared `.native-cache`
junction contains only CrispASR and transcribe.cpp, so it cannot be used for a
sherpa rebuild without modifying shared inputs. An ignored, private staging tree
under `app/build/toolchain-qa/sherpa-helper` copied the exact helper build/settings/
manifest files, reused JNI/notices from the supplied AAR, and checked out upstream
Kotlin sources at pinned revision `7e227a529be6c383134a358c5744d0eb1cb5ae1f`.

```powershell
.\gradlew.bat -p app/build/toolchain-qa/sherpa-helper/tools/sherpa-android assembleRelease "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain --offline
python tools/release_artifacts.py app/build/toolchain-qa/sherpa-helper/tools/sherpa-android/build/outputs/aar/utterlane-sherpa-release.aar
python app/build/toolchain-qa/verify_artifacts.py
```

Result: **28 tasks executed, BUILD SUCCESSFUL in 15s**. Actual `compileReleaseKotlin`
ran (not `NO-SOURCE`); AAR classes include `OfflineRecognizer` at JVM class version
52 and metadata `minCompileSdk=37`. Both packaged ARM64 libraries pass 16 KB ELF
checks. This verifies Kotlin/AAR packaging; sherpa JNI itself was not recompiled.
The ignored staging/inspection scripts remain with local build outputs.

## Failures encountered and resolved

1. Initial upgraded app configuration failed on the generated-assets Provider
   restriction. Migrated to the Variant API. Task-graph inspection then caught that
   a standalone directory property did not carry the Sync producer; a task-owned
   output property fixed this. Final packaging verified the notice contents.
2. Standalone helper configuration failed on an old library-source-set cast.
   Merely using `named` instead of indexing did not fix the inferred DSL type;
   explicitly configuring the public AGP 9 `LibraryExtension` did. Real upstream
   source compilation subsequently passed.
3. Review found `tools/sign_release.py` still selected Build Tools 35.0.0 while CI
   installs 36.0.0. A new test exercises the real signer flow with a temporary SDK
   containing only 36.0.0 executables, mocked archive validation/subprocesses, and
   dummy signing inputs. It failed on the old `35.0.0/aapt` selection, then passed
   after updating the signer to 36.0.0. It covers both `aapt` inspections, `zipalign`,
   `apksigner` signing/verification, and creation of release outputs/checksums.

Review follow-up verification:

```powershell
python -m unittest tools.tests.test_publishing_guards.PublishingGuardsTest.test_signing_uses_only_build_tools_installed_on_clean_ci_runner -v
python -m unittest discover -s tools/tests -v
git diff --check
```

The focused test and all **13** tools tests passed. The release dependency report
also confirmed the separate Compose graphs documented above. APK/AAB builds were
not repeated for this signer/test/documentation-only follow-up; real signing was
not performed.

## Remaining concerns / boundaries

- **F-Droid is not verified.** No fdroiddata checkout or supported Linux build
  environment is present. The preserved historical recipe installs SDK 35/36 and
  Build Tools 35, invokes externally supplied Gradle, and retains its 2.0.0/11
  generator guard. It needs an approved packaging update and Gradle 9.4.1 before a
  real `fdroid build`; see the distribution guide's migration constraint.
- SimpleStorage's manifest adds **`WRITE_EXTERNAL_STORAGE`** (without a maximum
  SDK) and its non-exported `EmptyActivity`. The merger report attributes both to
  `com.anggrayudi:storage:3.1.0`. This milestone adds no permission-request code;
  the parent storage-feature integration must decide its legacy-permission policy.
- Existing Kotlin warnings remain, including nullable receiver warnings in
  `DiarizedWindowProcessor`, data-class copy-visibility migration warnings for
  Kotlin 2.5, deprecated lifecycle imports, and coroutines delicate/deprecated API
  usage. They are warnings under 2.4.21; no algorithm changes were made to suppress
  them. Pinned native sources also emit existing warnings.
- sdkmanager 11.0 warns about newer SDK XML schemas but successfully recognizes the
  installed packages. Device UI, minified runtime inference, signing/publication,
  and Play validation were not performed.

## Official references

- [Gradle 9.4.1 binary SHA-256](https://services.gradle.org/distributions/gradle-9.4.1-bin.zip.sha256):
  `2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`.
- [AGP 9.2 compatibility](https://developer.android.google.cn/build/releases/agp-9-2-0-release-notes?hl=en).
- [Built-in Kotlin migration](https://developer.android.google.cn/build/migrate-to-built-in-kotlin?hl=en).
- [Explicit higher KGP classpath](https://developer.android.google.cn/build/releases/agp-9-0-0-release-notes?hl=en#upgrade-to-a-higher-kgp-version).
- [SimpleStorage 3.1.0 published POM](https://repo.maven.apache.org/maven2/com/anggrayudi/storage/3.1.0/storage-3.1.0.pom).
