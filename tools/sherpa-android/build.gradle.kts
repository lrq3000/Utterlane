buildscript {
    repositories { mavenCentral() }
    dependencies {
        // This standalone build uses the root wrapper but resolves its own KGP.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.library") version "9.2.1"
}

// Package the pinned upstream bindings without modifying its example projects
// or depending on their unrelated UI libraries, Gradle versions and APKs.
// Use the public AGP 9 DSL type, not the legacy library source-set accessor.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.k2fsa.sherpa.onnx"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    sourceSets.named("main") {
        kotlin.directories += "../../.native-cache/sherpa-onnx/sherpa-onnx/kotlin-api"
        jniLibs.directories += "../../.native-cache/sherpa-package/jni"
        assets.directories += "../../.native-cache/sherpa-package/assets"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
    }
}
