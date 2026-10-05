#include "jni_text.h"
#include "crispasr_session.h"
#include "nemotron3_diar.h"
#include <memory>
#include <vector>
#include <cstdlib>
#include <stdexcept>
#include <array>

// Exact versioned POD layout from the pinned crispasr_c_api.cpp. Upstream's
// public header only forward-declares it; v2 allows an explicitly CPU-only open.
struct crispasr_open_params_v1 {
    int abi_version, n_threads, use_gpu, verbosity, flash_attn, n_gpu_layers, reserved[6];
};

namespace {
struct SpeakerStream {
    nemotron3_diar_context* model = nullptr;
    nemotron3_diar_stream* stream = nullptr;
    ~SpeakerStream() {
        if (stream) nemotron3_diar_stream_free(stream);
        if (model) nemotron3_diar_free(model);
    }
};
// The callback is invocation-scoped and runs on the current native inference
// thread after completed work. It never lets a merely responsive thread renew
// the watchdog, and no JNIEnv/global Java reference survives the call.
struct SpeakerWork {
    JNIEnv* env;
    jobject owner;
    jmethodID method;
    std::array<jlong, 3> completed{};
    static void report(void* pointer, int stage) {
        auto& self = *static_cast<SpeakerWork*>(pointer);
        if (stage >= 0 && stage < 3 && !self.env->ExceptionCheck())
            self.env->CallVoidMethod(self.owner, self.method, ++self.completed[stage], static_cast<jint>(stage));
    }
};
struct SpeakerWorkLease {
    nemotron3_diar_context* model;
    ~SpeakerWorkLease() { utterlane_n3d_set_work_callback(model, nullptr, nullptr); }
};
std::vector<float> audio(JNIEnv* env, jfloatArray input, bool allowEmpty = false) {
    const int n = env->GetArrayLength(input);
    if (n > 192000 || n < (allowEmpty ? 0 : 1)) throw std::runtime_error("Invalid bounded audio input");
    std::vector<float> pcm(n);
    if (n) env->GetFloatArrayRegion(input, 0, n, pcm.data());
    return pcm;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispGenericBackend_openNative(JNIEnv* env, jobject, jstring path, jstring codec, jint threads) {
    if (threads < 1 || threads > 32) { error(env, "Invalid ASR thread count"); return 0; }
    const char* filename = env->GetStringUTFChars(path, nullptr);
    crispasr_session* session = nullptr;
    try {
        crispasr_open_params_v1 params{2, threads, 0, 0, 1, 0, {0}};
        session = crispasr_session_open_with_params(filename, nullptr, &params);
        if (!session) throw std::runtime_error("CrispASR could not load this model. Import a supported speech model and its companion files.");
        if (codec) {
            const char* raw = env->GetStringUTFChars(codec, nullptr);
            const std::string companion(raw);
            env->ReleaseStringUTFChars(codec, raw);
            // MiMo-ASR and other explicit-companion backends do not discover
            // their audio tokenizer from siblings. Configure it BEFORE warm-up.
            if (crispasr_session_set_codec_path(session, companion.c_str()) != 0)
                throw std::runtime_error("Cannot load the selected audio tokenizer / codec companion");
        }
    } catch (const std::exception& e) {
        if (session) crispasr_session_close(session);
        session = nullptr;
        error(env, e.what());
    }
    env->ReleaseStringUTFChars(path, filename);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispGenericBackend_decodeNative(JNIEnv* env, jobject, jlong handle, jfloatArray input) {
    try {
        if (!handle) throw std::runtime_error("Model is closed");
        auto pcm = audio(env, input);
        std::unique_ptr<crispasr_session_result, decltype(&crispasr_session_result_free)> result(
            crispasr_session_transcribe(reinterpret_cast<crispasr_session*>(handle), pcm.data(), pcm.size()), crispasr_session_result_free);
        if (!result) throw std::runtime_error("CrispASR speech transcription failed");
        const int n = crispasr_session_result_n_segments(result.get());
        if (n < 0 || n > 8192) throw std::runtime_error("Invalid transcription result");
        std::string text;
        std::vector<std::string> words;
        std::vector<jfloat> starts, ends;
        for (int i = 0; i < n; ++i) {
            const char* piece = crispasr_session_result_segment_text(result.get(), i);
            if (piece && *piece) { if (!text.empty()) text += ' '; text += piece; }
            if (text.size() > 128000) throw std::runtime_error("Transcription result exceeds IPC budget");
            const int nw = crispasr_session_result_n_words(result.get(), i);
            if (nw < 0 || nw + words.size() > 8192) throw std::runtime_error("Word metadata exceeds IPC budget");
            for (int j = 0; j < nw; ++j) {
                const char* word = crispasr_session_result_word_text(result.get(), i, j);
                words.emplace_back(word ? word : "");
                starts.push_back(crispasr_session_result_word_t0(result.get(), i, j) / 100.0f);
                ends.push_back(crispasr_session_result_word_t1(result.get(), i, j) / 100.0f);
            }
        }
        // Preserve the authoritative full text. Timing metadata is optional and
        // must never force a second ASR pass or silently remove unmatched words.
        auto pieces = env->NewObjectArray(words.size(), env->FindClass("java/lang/String"), nullptr);
        auto t0 = env->NewFloatArray(words.size());
        auto t1 = env->NewFloatArray(words.size());
        for (size_t i = 0; i < words.size(); ++i) {
            auto word = utf8(env, words[i].c_str());
            env->SetObjectArrayElement(pieces, i, word); env->DeleteLocalRef(word);
        }
        if (!words.empty()) {
            env->SetFloatArrayRegion(t0, 0, words.size(), starts.data());
            env->SetFloatArrayRegion(t1, 0, words.size(), ends.data());
        }
        auto output = env->NewObjectArray(4, env->FindClass("java/lang/Object"), nullptr);
        auto raw = utf8(env, text.c_str());
        env->SetObjectArrayElement(output, 0, pieces); env->SetObjectArrayElement(output, 1, t0);
        env->SetObjectArrayElement(output, 2, t1); env->SetObjectArrayElement(output, 3, raw);
        env->DeleteLocalRef(pieces); env->DeleteLocalRef(t0); env->DeleteLocalRef(t1); env->DeleteLocalRef(raw);
        return output;
    } catch (const std::exception& e) { error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispGenericBackend_closeNative(JNIEnv*, jobject, jlong handle) {
    if (handle) crispasr_session_close(reinterpret_cast<crispasr_session*>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispSpeakerStream_openNative(JNIEnv* env, jobject, jstring path,
    jint threads, jstring mode, jint batch, jint cache, jint fifo, jint update) {
    if (threads < 1 || threads > 32 || batch < 1 || batch > 16) { error(env, "Invalid speaker runtime options"); return 0; }
    const char* filename = env->GetStringUTFChars(path, nullptr);
    const char* preset = env->GetStringUTFChars(mode, nullptr);
    std::unique_ptr<SpeakerStream> owner(new SpeakerStream());
    try {
        auto params = nemotron3_diar_default_params();
        params.n_threads = threads; params.use_gpu = false; params.verbosity = 0;
        owner->model = nemotron3_diar_init_from_file(filename, params);
        if (!owner->model || nemotron3_diar_n_speakers(owner->model) != 8) throw std::runtime_error("Cannot load the eight-speaker diarization model");
        if (utterlane_n3d_options_v1(owner->model, cache, fifo, update) != 0)
            throw std::runtime_error("Invalid speaker cache/FIFO/update configuration");
        owner->stream = nemotron3_diar_stream_begin(owner->model, preset);
        if (!owner->stream) throw std::runtime_error("Cannot start speaker stream");
        // Explicitly override environment defaults; only buffered audio is merged.
        nemotron3_diar_stream_set_catchup(owner->stream, batch);
    } catch (const std::exception& e) { error(env, e.what()); }
    env->ReleaseStringUTFChars(path, filename);
    env->ReleaseStringUTFChars(mode, preset);
    return env->ExceptionCheck() ? 0 : reinterpret_cast<jlong>(owner.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispSpeakerStream_pushNative(JNIEnv* env, jobject object, jlong handle, jfloatArray input, jboolean final) {
    try {
        auto* owner = reinterpret_cast<SpeakerStream*>(handle);
        if (!owner || !owner->stream) throw std::runtime_error("Speaker stream is closed");
        auto cls = env->GetObjectClass(object);
        auto method = env->GetMethodID(cls, "onNativeProgress", "(JI)V");
        env->DeleteLocalRef(cls);
        if (!method) return nullptr;
        SpeakerWork work{env, object, method};
        utterlane_n3d_set_work_callback(owner->model, SpeakerWork::report, &work);
        SpeakerWorkLease lease{owner->model};
        auto pcm = audio(env, input, true);
        std::vector<float> combined;
        auto collect = [&](float* raw, int rows) {
            std::unique_ptr<float, decltype(&std::free)> data(raw, std::free);
            if (rows < 0 || rows > 1400 || (rows > 0 && !raw)) throw std::runtime_error("Invalid diarization result");
            if (rows) combined.insert(combined.end(), raw, raw + rows * 8);
        };
        int rows = 0;
        if (!pcm.empty()) { float* out = nemotron3_diar_stream_push(owner->stream, pcm.data(), pcm.size(), &rows); collect(out, rows); }
        if (final) { rows = 0; float* out = nemotron3_diar_stream_end(owner->stream, &rows); collect(out, rows); }
        if (env->ExceptionCheck()) return nullptr;
        auto output = env->NewFloatArray(combined.size());
        if (!combined.empty()) env->SetFloatArrayRegion(output, 0, combined.size(), combined.data());
        return output;
    } catch (const std::exception& e) { error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispSpeakerStream_closeNative(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<SpeakerStream*>(handle);
}
