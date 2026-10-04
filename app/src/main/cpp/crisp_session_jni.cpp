#include "jni_text.h"
#include "crispasr_session.h"
#include "nemotron3_diar.h"
#include <memory>
#include <vector>
#include <cstdlib>
#include <stdexcept>

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
std::vector<float> audio(JNIEnv* env, jfloatArray input, bool allowEmpty = false) {
    const int n = env->GetArrayLength(input);
    if (n > 192000 || n < (allowEmpty ? 0 : 1)) throw std::runtime_error("Invalid bounded audio input");
    std::vector<float> pcm(n);
    if (n) env->GetFloatArrayRegion(input, 0, n, pcm.data());
    return pcm;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispGenericBackend_openNative(JNIEnv* env, jobject, jstring path, jstring codec) {
    const char* filename = env->GetStringUTFChars(path, nullptr);
    crispasr_session* session = nullptr;
    try {
        crispasr_open_params_v1 params{2, 4, 0, 0, 1, 0, {0}};
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

extern "C" JNIEXPORT jstring JNICALL
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
        for (int i = 0; i < n; ++i) {
            const char* piece = crispasr_session_result_segment_text(result.get(), i);
            if (piece && *piece) { if (!text.empty()) text += ' '; text += piece; }
            if (text.size() > 128000) throw std::runtime_error("Transcription result exceeds IPC budget");
        }
        return utf8(env, text.c_str());
    } catch (const std::exception& e) { error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispGenericBackend_closeNative(JNIEnv*, jobject, jlong handle) {
    if (handle) crispasr_session_close(reinterpret_cast<crispasr_session*>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispSpeakerStream_openNative(JNIEnv* env, jobject, jstring path) {
    const char* filename = env->GetStringUTFChars(path, nullptr);
    std::unique_ptr<SpeakerStream> owner(new SpeakerStream());
    try {
        auto params = nemotron3_diar_default_params();
        params.n_threads = 4; params.use_gpu = false; params.verbosity = 0;
        owner->model = nemotron3_diar_init_from_file(filename, params);
        if (!owner->model || nemotron3_diar_n_speakers(owner->model) != 8) throw std::runtime_error("Cannot load the eight-speaker diarization model");
        // 0.64 s lookahead fits inside the ASR pipeline's one-second right context.
        owner->stream = nemotron3_diar_stream_begin(owner->model, "very_low_latency");
        if (!owner->stream) throw std::runtime_error("Cannot start speaker stream");
    } catch (const std::exception& e) { error(env, e.what()); }
    env->ReleaseStringUTFChars(path, filename);
    return env->ExceptionCheck() ? 0 : reinterpret_cast<jlong>(owner.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispSpeakerStream_pushNative(JNIEnv* env, jobject, jlong handle, jfloatArray input, jboolean final) {
    try {
        auto* owner = reinterpret_cast<SpeakerStream*>(handle);
        if (!owner || !owner->stream) throw std::runtime_error("Speaker stream is closed");
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
        auto output = env->NewFloatArray(combined.size());
        if (!combined.empty()) env->SetFloatArrayRegion(output, 0, combined.size(), combined.data());
        return output;
    } catch (const std::exception& e) { error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_lrq3000_utterlane_asr_CrispSpeakerStream_closeNative(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<SpeakerStream*>(handle);
}
