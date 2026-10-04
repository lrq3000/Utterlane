plugins {
    id("com.android.library") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// Package the pinned upstream bindings without modifying its example projects
// or depending on their unrelated UI libraries, Gradle versions and APKs.
android {
    namespace = "com.k2fsa.sherpa.onnx"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    sourceSets["main"].apply {
        java.srcDir("../../.native-cache/sherpa-onnx/sherpa-onnx/kotlin-api")
        jniLibs.srcDir("../../.native-cache/sherpa-package/jni")
        assets.srcDir("../../.native-cache/sherpa-package/assets")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
