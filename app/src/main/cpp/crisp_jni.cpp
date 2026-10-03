#include <jni.h>
#include <memory>
#include <string>
#include <vector>
#include "parakeet.h"

namespace {
void error(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
// java.lang.String(byte[], UTF_8) handles full UTF-8 rather than JNI's modified
// UTF-8 convention, including any non-BMP tokenizer text.
jstring utf8(JNIEnv* env, const char* text) {
    const std::string value(text ? text : "");
    auto bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    auto encoding = env->NewStringUTF("UTF-8");
    auto cls = env->FindClass("java/lang/String");
    auto result = static_cast<jstring>(env->NewObject(cls, env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V"), bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_translander_asr_CrispParakeetBackend_openNative(JNIEnv* env, jobject, jstring path) {
    const char* filename = env->GetStringUTFChars(path, nullptr);
    auto params = parakeet_context_default_params();
    params.n_threads = 4; params.use_gpu = false; params.verbosity = 0;
    parakeet_context* context = nullptr;
    try { context = parakeet_init_from_file(filename, params); }
    catch (const std::exception& e) { error(env, e.what()); }
    env->ReleaseStringUTFChars(path, filename);
    if (!context && !env->ExceptionCheck()) error(env, "Cannot load the selected GGUF model");
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_translander_asr_CrispParakeetBackend_decodeNative(JNIEnv* env, jobject, jlong handle, jfloatArray audio) {
    auto* context = reinterpret_cast<parakeet_context*>(handle);
    const int count = env->GetArrayLength(audio);
    if (!context || count <= 0 || count > 192000) { error(env, "Invalid bounded native inference input"); return nullptr; }
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, 0, count, pcm.data());
    try {
        std::unique_ptr<parakeet_result, decltype(&parakeet_result_free)> result(
            parakeet_transcribe_ex(context, pcm.data(), count, 0), parakeet_result_free);
        if (!result) { error(env, "GGUF transcription failed"); return nullptr; }
        auto pieces = env->NewObjectArray(result->n_tokens, env->FindClass("java/lang/String"), nullptr);
        auto times = env->NewFloatArray(result->n_tokens);
        std::vector<jfloat> timestamps(static_cast<size_t>(result->n_tokens));
        for (int i = 0; i < result->n_tokens; ++i) {
            auto piece = utf8(env, result->tokens[i].text);
            env->SetObjectArrayElement(pieces, i, piece);
            env->DeleteLocalRef(piece);
            // CrispASR's C API uses centiseconds. The shared Kotlin pipeline uses seconds.
            timestamps[static_cast<size_t>(i)] = static_cast<float>(result->tokens[i].t0) / 100.0f;
        }
        env->SetFloatArrayRegion(times, 0, result->n_tokens, timestamps.data());
        auto values = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
        env->SetObjectArrayElement(values, 0, pieces); env->SetObjectArrayElement(values, 1, times);
        env->DeleteLocalRef(pieces); env->DeleteLocalRef(times);
        return values;
    } catch (const std::exception& e) { error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_translander_asr_CrispParakeetBackend_closeNative(JNIEnv*, jobject, jlong handle) {
    if (handle) parakeet_free(reinterpret_cast<parakeet_context*>(handle));
}
