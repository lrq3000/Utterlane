# Included by the pinned upstream project's project() call. This keeps its
# root-relative includes/generated headers intact without modifying vendor code.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-O3)
add_library(utterlane_transcribe SHARED "${CMAKE_CURRENT_LIST_DIR}/transcribe_jni.cpp")
target_compile_features(utterlane_transcribe PRIVATE cxx_std_23)
target_include_directories(utterlane_transcribe PRIVATE
    "${CMAKE_SOURCE_DIR}/include" "${CMAKE_SOURCE_DIR}/src" "${CMAKE_SOURCE_DIR}/ggml/include")
set_target_properties(utterlane_transcribe PROPERTIES
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_link_libraries(utterlane_transcribe PRIVATE transcribe ggml ggml-base log)
# A second ggml must never interpose symbols from utterlane_crisp. Hide the
# complete static archives and export only this bridge's JNI entry points.
target_link_options(utterlane_transcribe PRIVATE
    "-Wl,--exclude-libs,ALL" "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/exports.map")
set_property(TARGET utterlane_transcribe APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/exports.map")
