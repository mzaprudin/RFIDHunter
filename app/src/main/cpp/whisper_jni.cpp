#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

namespace {
constexpr auto kTranscriptionTimeout = std::chrono::seconds(45);

struct WhisperHandle {
    whisper_context * context = nullptr;
    std::mutex mutex;
};

struct AbortDeadline {
    std::chrono::steady_clock::time_point value;
};

bool abortAfterDeadline(void * data) {
    const auto * deadline = static_cast<const AbortDeadline *>(data);
    return deadline != nullptr && std::chrono::steady_clock::now() >= deadline->value;
}

WhisperHandle * fromHandle(jlong value) {
    return reinterpret_cast<WhisperHandle *>(value);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_rfidhunter_app_voice_WhisperNative_create(JNIEnv * env, jclass, jstring model_path) {
    if (model_path == nullptr) return 0;
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    auto * handle = new WhisperHandle();
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    handle->context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    if (handle->context == nullptr) {
        delete handle;
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_rfidhunter_app_voice_WhisperNative_transcribe(JNIEnv * env, jclass, jlong native_handle, jshortArray audio, jint sample_rate) {
    auto * handle = fromHandle(native_handle);
    if (handle == nullptr || handle->context == nullptr || audio == nullptr || sample_rate <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(handle->mutex);

    const jsize count = env->GetArrayLength(audio);
    if (count <= 0) return nullptr;
    std::vector<jshort> pcm16(static_cast<size_t>(count));
    env->GetShortArrayRegion(audio, 0, count, pcm16.data());
    std::vector<float> pcmf32(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) pcmf32[static_cast<size_t>(i)] = static_cast<float>(pcm16[static_cast<size_t>(i)]) / 32768.0f;

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.max_tokens = 24;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = "ru";
    params.detect_language = false;
    AbortDeadline deadline{std::chrono::steady_clock::now() + kTranscriptionTimeout};
    params.abort_callback = abortAfterDeadline;
    params.abort_callback_user_data = &deadline;

    if (sample_rate != WHISPER_SAMPLE_RATE) {
        // The Kotlin capture path is fixed at 16 kHz. Reject an unexpected rate
        // rather than silently decoding audio at the wrong speed.
        return nullptr;
    }
    if (whisper_full(handle->context, params, pcmf32.data(), static_cast<int>(pcmf32.size())) != 0) return nullptr;

    std::string result;
    const int segments = whisper_full_n_segments(handle->context);
    for (int i = 0; i < segments; ++i) {
        const char * text = whisper_full_get_segment_text(handle->context, i);
        if (text != nullptr) result += text;
    }
    if (result.empty()) return nullptr;
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ru_rfidhunter_app_voice_WhisperNative_release(JNIEnv *, jclass, jlong native_handle) {
    auto * handle = fromHandle(native_handle);
    if (handle == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(handle->mutex);
        if (handle->context != nullptr) whisper_free(handle->context);
        handle->context = nullptr;
    }
    delete handle;
}
