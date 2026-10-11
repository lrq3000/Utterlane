buildscript {
    repositories { mavenCentral() }
    dependencies {
        // AGP owns Kotlin integration; override its bundled KGP to match Compose.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
