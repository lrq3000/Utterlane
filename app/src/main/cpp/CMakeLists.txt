cmake_minimum_required(VERSION 3.22)
project(translander_crisp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CRISP_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../.native-cache/crispasr")
if(NOT EXISTS "${CRISP_SOURCE}/src/parakeet.cpp")
    message(FATAL_ERROR "Run python tools/prepare_native.py before building")
endif()

# Use the actual pinned Parakeet runtime and shared primitives, not the much
# larger all-ASR/TTS registry. Android owns audio capture/decoding and downloads.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
add_compile_definitions(GGML_MAX_NAME=128)
add_subdirectory("${CRISP_SOURCE}/ggml" "${CMAKE_BINARY_DIR}/ggml" EXCLUDE_FROM_ALL)

# Android's debug configuration otherwise leaves ggml's CPU/quantization hot
# loops at -O0. Optimizing only the JNI target does not propagate to libraries;
# retain debug symbols, but use the same kernel optimization as release builds.
foreach(kernel ggml ggml-base ggml-cpu)
    target_compile_options(${kernel} PRIVATE -O3)
endforeach()

add_library(translander_crisp SHARED
    crisp_jni.cpp
    "${CRISP_SOURCE}/src/parakeet.cpp"
    "${CRISP_SOURCE}/src/core/mel.cpp"
    "${CRISP_SOURCE}/src/core/gguf_loader.cpp"
    "${CRISP_SOURCE}/src/crispasr_imatrix.cpp")
target_include_directories(translander_crisp PRIVATE
    "${CRISP_SOURCE}/src" "${CRISP_SOURCE}/include" "${CRISP_SOURCE}/ggml/src")
target_compile_options(translander_crisp PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(translander_crisp PRIVATE ggml ggml-base ggml-cpu log)
