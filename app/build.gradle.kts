plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sherpaOnnxVersion = "1.12.23"
val aarFile = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile
val crispNotices = layout.buildDirectory.dir("generated/crispNotices")
val copyCrispNotices = tasks.register<Sync>("copyCrispNotices") {
    val source = rootProject.file(".native-cache/crispasr")
    from(source) { include("LICENSE", "THIRD_PARTY_NOTICES.txt") }
    into(crispNotices.map { it.dir("crispasr") })
    doFirst { check(source.resolve("THIRD_PARTY_NOTICES.txt").isFile) { "Run python tools/prepare_native.py first" } }
}

tasks.register("checkSherpaOnnxAar") {
    doLast {
        if (!aarFile.exists()) {
            throw GradleException(
                "sherpa-onnx AAR not found at: $aarFile\n" +
                "Run python tools/build_sherpa.py first to build it from source."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("checkSherpaOnnxAar")
    dependsOn(copyCrispNotices)
}

android {
    ndkVersion = "28.2.13676358"
    namespace = "io.github.lrq3000.utterlane"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lrq3000.utterlane"
        minSdk = 26
        targetSdk = 36
        versionCode = 210
        versionName = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only include arm64-v8a for modern phones (~50% smaller APK)
        // Remove this filter if you need to support older 32-bit devices or emulators
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake { targets += "utterlane_crisp"; arguments += "-DANDROID_STL=c++_static" }
        }
    }

    // F-Droid supplies its own signature. Local/CI release signing is opt-in;
    // partial credentials must fail rather than silently produce an unsigned APK.
    val signingNames = listOf("UTTERLANE_KEYSTORE", "UTTERLANE_STORE_PASSWORD", "UTTERLANE_KEY_ALIAS", "UTTERLANE_KEY_PASSWORD")
    val signingValues = signingNames.associateWith { System.getenv(it) }
    val hasSigning = signingValues.values.any { !it.isNullOrBlank() }
    if (hasSigning) {
        require(signingValues.values.all { !it.isNullOrBlank() }) {
            "Release signing requires all four UTTERLANE_* signing environment variables"
        }
        signingConfigs.create("release") {
            storeFile = file(signingValues.getValue("UTTERLANE_KEYSTORE")!!)
            storePassword = signingValues.getValue("UTTERLANE_STORE_PASSWORD")
            keyAlias = signingValues.getValue("UTTERLANE_KEY_ALIAS")
            keyPassword = signingValues.getValue("UTTERLANE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Optional local QA identity prevents parallel emulator runs replacing
            // each other's data. A persisted QA property cannot rename releases.
            applicationIdSuffix = providers.gradleProperty("qaApplicationIdSuffix").getOrElse("")
        }
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    sourceSets.getByName("main").assets.srcDir(crispNotices)

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":transcribe-native"))
    // Source-built sherpa JNI/bindings + official Maven Central ONNX Runtime.
    implementation(files(aarFile))

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    // Jetpack DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
