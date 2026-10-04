plugins { id("com.android.library") }

android {
    namespace = "io.github.lrq3000.utterlane.transcribenative"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                targets += "utterlane_transcribe"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_PROJECT_transcribe_INCLUDE=${projectDir.invariantSeparatorsPath}/src/main/cpp/bridge.cmake",
                    "-DTRANSCRIBE_MODEL_SET=custom", "-DTRANSCRIBE_MODELS=parakeet",
                    "-DTRANSCRIBE_BUILD_TESTS=OFF", "-DTRANSCRIBE_BUILD_EXAMPLES=OFF",
                    "-DTRANSCRIBE_BUILD_TOOLS=OFF", "-DTRANSCRIBE_INSTALL=OFF",
                    "-DTRANSCRIBE_BUILD_SHARED=OFF", "-DTRANSCRIBE_SHARED_EMBED=OFF",
                    "-DTRANSCRIBE_USE_OPENMP=OFF", "-DTRANSCRIBE_USE_SYSTEM_BLAS=OFF",
                    "-DTRANSCRIBE_ENABLE_EARSHOT=OFF", "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF", "-DGGML_CPU_ARM_ARCH=armv8-a",
                    "-DGGML_CPU_KLEIDIAI=OFF", "-DGGML_BACKEND_DL=OFF",
                    "-DTRANSCRIBE_METAL=OFF", "-DTRANSCRIBE_VULKAN=OFF",
                    "-DTRANSCRIBE_CUDA=OFF", "-DTRANSCRIBE_HIP=OFF"
                )
            }
        }
    }
    // Separate CMake configuration is essential: transcribe.cpp and CrispASR
    // both define ggml targets but use different, incompatible patched versions.
    externalNativeBuild {
        cmake {
            path = file("../.native-cache/transcribe/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
