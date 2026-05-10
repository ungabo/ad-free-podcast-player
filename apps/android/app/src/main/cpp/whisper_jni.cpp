#include <jni.h>
#include <string>
#include <sstream>
#include <iomanip>
#include "whisper.h"
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "WhisperJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WhisperJNI", __VA_ARGS__)

static whisper_context* gCtx = nullptr;

// Minimal JSON string escaping – handles the characters that appear in English podcast speech.
static std::string jsonEscape(const char* s) {
    if (!s) return "";
    std::string out;
    out.reserve(128);
    for (; *s; ++s) {
        switch (*s) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += *s;     break;
        }
    }
    return out;
}

extern "C" {

// boolean WhisperEngine.nativeInit(String modelPath)
JNIEXPORT jboolean JNICALL
Java_com_adfreepod_player_MainActivity_00024WhisperEngine_nativeInit(JNIEnv* env, jclass /*cls*/, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return JNI_FALSE;

    if (gCtx) {
        whisper_free(gCtx);
        gCtx = nullptr;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    gCtx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!gCtx) {
        LOGE("whisper_init_from_file_with_params failed");
        return JNI_FALSE;
    }
    LOGI("Whisper context loaded OK");
    return JNI_TRUE;
}

// String WhisperEngine.nativeTranscribe(float[] samples, double offsetSeconds)
// Returns a JSON array of segment objects: [{start, end, text}, ...]
JNIEXPORT jstring JNICALL
Java_com_adfreepod_player_MainActivity_00024WhisperEngine_nativeTranscribe(JNIEnv* env, jclass /*cls*/,
                                                         jfloatArray samples,
                                                         jdouble offsetSeconds) {
    if (!gCtx) return env->NewStringUTF("[]");

    jint len = env->GetArrayLength(samples);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return env->NewStringUTF("[]");

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = 4;
    params.language         = "en";
    params.translate        = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = true;
    params.single_segment   = false;
    params.no_context       = true;

    int rc = whisper_full(gCtx, params, data, (int)len);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full returned %d", rc);
        return env->NewStringUTF("[]");
    }

    int n = whisper_full_n_segments(gCtx);
    std::ostringstream out;
    out << std::fixed << std::setprecision(2);
    out << "[";
    for (int i = 0; i < n; i++) {
        if (i > 0) out << ",";
        const char* text = whisper_full_get_segment_text(gCtx, i);
        int64_t t0 = whisper_full_get_segment_t0(gCtx, i);
        int64_t t1 = whisper_full_get_segment_t1(gCtx, i);
        // whisper timestamps are in centiseconds
        double start = offsetSeconds + t0 / 100.0;
        double end   = offsetSeconds + t1 / 100.0;
        out << "{\"start\":" << start
            << ",\"end\":"   << end
            << ",\"text\":\"" << jsonEscape(text) << "\"}";
    }
    out << "]";

    return env->NewStringUTF(out.str().c_str());
}

// void WhisperEngine.nativeFree()
JNIEXPORT void JNICALL
Java_com_adfreepod_player_MainActivity_00024WhisperEngine_nativeFree(JNIEnv* /*env*/, jclass /*cls*/) {
    if (gCtx) {
        whisper_free(gCtx);
        gCtx = nullptr;
        LOGI("Whisper context freed");
    }
}

} // extern "C"
