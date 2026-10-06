#include <jni.h>
#include <android/log.h>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "transcribe.h"
#include "arch/parakeet/parakeet.h"
#include "ggml.h"

namespace {
constexpr int kMaxSamples = 192000;
using Session = std::unique_ptr<transcribe_session, decltype(&transcribe_session_free)>;

void logMessage(transcribe_log_level level, const char* message, void*) noexcept {
    const int priority = level == TRANSCRIBE_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
        level == TRANSCRIBE_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(priority, "TranscribeTernary", message ? message : "");
}

void check(transcribe_status status, const char* operation) {
    if (status != TRANSCRIBE_OK) {
        throw std::runtime_error(std::string(operation) + ": " + transcribe_status_string(status));
    }
}

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

jstring utf8(JNIEnv* env, const char* text) {
    // NewStringUTF uses modified UTF-8, not the tokenizer's UTF-8. Decode copied
    // bytes explicitly so non-BMP text survives the JNI boundary correctly.
    const size_t size = std::strlen(text ? text : "");
    auto bytes = env->NewByteArray(static_cast<jsize>(size));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(text));
    auto encoding = env->NewStringUTF("UTF-8");
    auto cls = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto result = static_cast<jstring>(env->NewObject(cls, constructor, bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}

std::array<jlong, 3> layout(const transcribe_session* session) {
    const auto* base = transcribe_get_model(session);
    if (!base || std::strcmp(transcribe_model_arch_string(base), "parakeet") != 0) {
        throw std::runtime_error("Expected a Parakeet model");
    }
    // This read-only assertion intentionally uses the pinned version's internal
    // model layout. Checking getenv alone would not prove weights stayed ternary.
    const auto* model = static_cast<const transcribe::parakeet::ParakeetModel*>(base);
    std::array<jlong, 3> stats{};
    for (auto* tensor = ggml_get_first_tensor(model->ctx_meta); tensor;
         tensor = ggml_get_next_tensor(model->ctx_meta, tensor)) {
        const auto bytes = static_cast<jlong>(ggml_nbytes(tensor));
        stats[2] += bytes;
        if (tensor->type == GGML_TYPE_TQ1_G128) { stats[0]++; stats[1] += bytes; }
    }
    return stats;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_lrq3000_utterlane_asr_TranscribeCppBackend_openNative(JNIEnv* env, jobject, jstring path, jint threads) {
    if (threads < 1 || threads > 32) { fail(env, "Invalid ASR thread count"); return 0; }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string filename;
    try { filename = chars; }
    catch (...) { env->ReleaseStringUTFChars(path, chars); fail(env, "Cannot allocate model path"); return 0; }
    env->ReleaseStringUTFChars(path, chars);
    try {
        static std::once_flag initialized;
        std::call_once(initialized, [] {
            // Only this backend reads the variable. Its worker is restarted on
            // model switch; existing CrispASR/sherpa code is never reconfigured.
            if (setenv("TRANSCRIBE_TERNARY_RUNTIME", "native", 1) != 0) {
                throw std::runtime_error("Cannot select compact ternary runtime");
            }
            transcribe_log_set(logMessage, nullptr);
        });
        transcribe_model_load_params load;
        transcribe_model_load_params_init(&load);
        load.backend = TRANSCRIBE_BACKEND_CPU;
        transcribe_session_params parameters;
        transcribe_session_params_init(&parameters);
        parameters.n_threads = threads;
        transcribe_session* pointer = nullptr;
        const auto status = transcribe_open(filename.c_str(), &load, &parameters, &pointer);
        Session session(pointer, transcribe_session_free);
        check(status, "Load compact Redux");
        const auto stats = layout(session.get());
        // The catalog-pinned checkpoint contains 264 ternary encoder matrices.
        // Refuse an expanded/fallback layout instead of mislabelling it compact.
        if (stats[0] != 264 || stats[2] > 159121504) {
            throw std::runtime_error("Compact Redux did not retain its native ternary weights");
        }
        __android_log_print(ANDROID_LOG_INFO, "TranscribeTernary",
            "native layout verified: tensors=%lld ternary_bytes=%lld weight_bytes=%lld",
            static_cast<long long>(stats[0]), static_cast<long long>(stats[1]), static_cast<long long>(stats[2]));
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) { fail(env, error.what()); }
    catch (...) { fail(env, "Unknown transcribe.cpp initialization failure"); }
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_lrq3000_utterlane_asr_TranscribeCppBackend_decodeNative(JNIEnv* env, jobject, jlong handle, jfloatArray audio) {
    const int count = env->GetArrayLength(audio);
    if (!handle || count <= 0 || count > kMaxSamples) { fail(env, "Invalid bounded audio window"); return nullptr; }
    try {
        auto* session = reinterpret_cast<transcribe_session*>(handle);
        std::vector<float> samples(static_cast<size_t>(count));
        env->GetFloatArrayRegion(audio, 0, count, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        transcribe_run_params parameters;
        transcribe_run_params_init(&parameters);
        parameters.timestamps = TRANSCRIBE_TIMESTAMPS_TOKEN;
        check(transcribe_run(session, samples.data(), count, &parameters), "Transcribe compact Redux");
        const int tokens = transcribe_n_tokens(session);
        if (tokens < 0 || tokens > 8192) throw std::runtime_error("Invalid token count");
        auto pieces = env->NewObjectArray(tokens, env->FindClass("java/lang/String"), nullptr);
        auto times = env->NewFloatArray(tokens);
        auto ends = env->NewFloatArray(tokens);
        if (!pieces || !times || !ends) return nullptr;
        std::vector<jfloat> timestamps(static_cast<size_t>(tokens));
        std::vector<jfloat> end_times(static_cast<size_t>(tokens));
        for (int i = 0; i < tokens; ++i) {
            transcribe_token token;
            transcribe_token_init(&token);
            check(transcribe_get_token(session, i, &token), "Read timestamped token");
            if (!token.text || token.t0_ms < 0) throw std::runtime_error("Missing token text or timestamp");
            auto piece = utf8(env, token.text);
            if (!piece) return nullptr;
            env->SetObjectArrayElement(pieces, i, piece);
            env->DeleteLocalRef(piece);
            timestamps[static_cast<size_t>(i)] = static_cast<float>(token.t0_ms) / 1000.0f;
            end_times[static_cast<size_t>(i)] = static_cast<float>(token.t1_ms) / 1000.0f;
        }
        env->SetFloatArrayRegion(times, 0, tokens, timestamps.data());
        env->SetFloatArrayRegion(ends, 0, tokens, end_times.data());
        auto result = env->NewObjectArray(3, env->FindClass("java/lang/Object"), nullptr);
        if (!result) return nullptr;
        env->SetObjectArrayElement(result, 0, pieces); env->SetObjectArrayElement(result, 1, times);
        env->SetObjectArrayElement(result, 2, ends);
        env->DeleteLocalRef(pieces); env->DeleteLocalRef(times); env->DeleteLocalRef(ends);
        return result;
    } catch (const std::exception& error) { fail(env, error.what()); }
    catch (...) { fail(env, "Unknown transcribe.cpp inference failure"); }
    return nullptr;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_lrq3000_utterlane_asr_TranscribeCppBackend_layoutNative(JNIEnv* env, jobject, jlong handle) {
    try {
        if (!handle) throw std::runtime_error("Model is closed");
        const auto values = layout(reinterpret_cast<transcribe_session*>(handle));
        auto result = env->NewLongArray(3);
        if (result) env->SetLongArrayRegion(result, 0, 3, values.data());
        return result;
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_lrq3000_utterlane_asr_TranscribeCppBackend_closeNative(JNIEnv*, jobject, jlong handle) {
    transcribe_session_free(reinterpret_cast<transcribe_session*>(handle));
}
