# Disable obfuscation (keep optimization)
-dontobfuscate

# sherpa-onnx
-keep class com.k2fsa.sherpa.onnx.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep services (referenced in AndroidManifest)
-keep class io.github.lrq3000.utterlane.service.TextInjectionService { *; }
-keep class io.github.lrq3000.utterlane.service.FloatingMicService { *; }
-keep class io.github.lrq3000.utterlane.transcribe.AudioMonitorService { *; }

# Keep broadcast receivers
-keep class io.github.lrq3000.utterlane.receiver.BootReceiver { *; }

# Keep activities launched via intent
-keep class io.github.lrq3000.utterlane.transcribe.TranscribeActivity { *; }
