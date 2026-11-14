#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "CactusJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::fprintf(stdout, "[CactusJNI INFO] " __VA_ARGS__); std::fprintf(stdout, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[CactusJNI ERROR] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "cactus.h"

namespace {

constexpr int kDefaultResponseBufferCapacity = 65536;

struct MappedArchive {
    void* addr = nullptr;
    size_t length = 0;
    std::vector<unsigned char> owned_bytes;

    ~MappedArchive() {
        if (addr != nullptr && length > 0) {
            munmap(addr, length);
            addr = nullptr;
            length = 0;
        }
    }
};

// Cactus engine owns a process-global state and reads .cact archives in place.
// We keep memory-mapped .cact archives pinned for the lifetime of the process
// and serialize engine calls with g_engine_mutex.
std::mutex g_engine_mutex;
std::unordered_map<std::string, std::unique_ptr<MappedArchive>> g_mapped_archives;
std::vector<std::unique_ptr<MappedArchive>> g_byte_archives;

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv* env, jstring str) : env_(env), str_(str), chars_(nullptr) {
        if (str_ != nullptr) {
            chars_ = env_->GetStringUTFChars(str_, nullptr);
        }
    }

    ~ScopedUtfChars() {
        if (str_ != nullptr && chars_ != nullptr) {
            env_->ReleaseStringUTFChars(str_, chars_);
        }
    }

    const char* get() const { return chars_; }

private:
    JNIEnv* env_;
    jstring str_;
    const char* chars_;
};

std::string MakeErrorJson(const char* message, int code) {
    std::string escaped;
    if (message != nullptr) {
        for (const char* p = message; *p != '\0'; ++p) {
            if (*p == '"' || *p == '\\') {
                escaped.push_back('\\');
            }
            if (*p == '\n') {
                escaped += "\\n";
            } else if (*p != '\r') {
                escaped.push_back(*p);
            }
        }
    }
    return std::string("{\"type\":\"error\",\"success\":false,\"error\":\"") +
           escaped + "\",\"error_code\":" + std::to_string(code) +
           ",\"function_calls\":[],\"confidence\":0.0}";
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ScopedUtfChars path(env, model_path);
    if (path.get() == nullptr) {
        LOGE("nativeLoadModel: model_path is null");
        return -1;
    }

    std::string path_str(path.get());
    auto existing = g_mapped_archives.find(path_str);
    if (existing != g_mapped_archives.end() && existing->second->addr != nullptr) {
        int rc = cactus_load(
            static_cast<const unsigned char*>(existing->second->addr),
            static_cast<unsigned long long>(existing->second->length)
        );
        return rc;
    }

    int fd = open(path.get(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("nativeLoadModel: failed to open %s", path.get());
        return -2;
    }

    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        LOGE("nativeLoadModel: invalid file size for %s", path.get());
        close(fd);
        return -3;
    }

    size_t length = static_cast<size_t>(st.st_size);
    void* mapped = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);

    if (mapped == MAP_FAILED || mapped == nullptr) {
        LOGE("nativeLoadModel: mmap failed for %s", path.get());
        return -4;
    }

    int rc = cactus_load(
        static_cast<const unsigned char*>(mapped),
        static_cast<unsigned long long>(length)
    );
    if (rc < 0) {
        const char* err = cactus_last_error();
        LOGE("cactus_load failed (%d) for %s: %s", rc, path.get(), err ? err : "unknown");
        munmap(mapped, length);
        return rc;
    }

    auto archive = std::make_unique<MappedArchive>();
    archive->addr = mapped;
    archive->length = length;
    g_mapped_archives[path_str] = std::move(archive);

    LOGI("Loaded .cact archive %s (%zu bytes), loaded_models=%d",
         path.get(), length, cactus_models());
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeLoadModelBytes(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray model_bytes
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (model_bytes == nullptr) {
        return -1;
    }

    jsize len = env->GetArrayLength(model_bytes);
    if (len <= 0) {
        return -2;
    }

    auto archive = std::make_unique<MappedArchive>();
    archive->owned_bytes.resize(static_cast<size_t>(len));
    env->GetByteArrayRegion(
        model_bytes,
        0,
        len,
        reinterpret_cast<jbyte*>(archive->owned_bytes.data())
    );

    int rc = cactus_load(
        archive->owned_bytes.data(),
        static_cast<unsigned long long>(archive->owned_bytes.size())
    );
    if (rc >= 0) {
        g_byte_archives.push_back(std::move(archive));
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeLoadedModels(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return cactus_models();
}

JNIEXPORT jstring JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeLastError(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    const char* err = cactus_last_error();
    return env->NewStringUTF(err != nullptr ? err : "");
}

JNIEXPORT jint JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring system_prompt,
    jstring tools_json,
    jstring tool_index_path
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ScopedUtfChars sys(env, system_prompt);
    ScopedUtfChars tools(env, tools_json);
    ScopedUtfChars index_path(env, tool_index_path);

    int rc = cactus_init(sys.get(), tools.get(), index_path.get());
    if (rc < 0) {
        const char* err = cactus_last_error();
        LOGE("cactus_init failed (%d): %s", rc, err ? err : "unknown");
    }
    return rc;
}

JNIEXPORT void JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeSetAudio(
    JNIEnv* env,
    jobject /* thiz */,
    jstring language,
    jstring keywords,
    jboolean word_timestamps
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ScopedUtfChars lang(env, language);
    ScopedUtfChars kw(env, keywords);
    cactus_set_audio(lang.get(), kw.get(), word_timestamps ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeSetThreads(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint threads
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    cactus_set_threads(threads > 0 ? static_cast<int>(threads) : 0);
}

JNIEXPORT jstring JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeTranscribe(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray pcm_16k_mono,
    jstring language,
    jstring keywords,
    jboolean word_timestamps
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (pcm_16k_mono == nullptr) {
        return env->NewStringUTF("{\"text\":\"\",\"language\":\"\",\"error\":\"null pcm array\"}");
    }

    jsize num_samples = env->GetArrayLength(pcm_16k_mono);
    if (num_samples <= 0) {
        return env->NewStringUTF("{\"text\":\"\",\"language\":\"\"}");
    }

    jfloat* pcm_ptr = env->GetFloatArrayElements(pcm_16k_mono, nullptr);
    if (pcm_ptr == nullptr) {
        return env->NewStringUTF("{\"text\":\"\",\"language\":\"\",\"error\":\"oom\"}");
    }

    ScopedUtfChars lang(env, language);
    ScopedUtfChars kw(env, keywords);

    std::vector<char> out_buf(kDefaultResponseBufferCapacity, '\0');
    int written = cactus_transcribe(
        pcm_ptr,
        static_cast<int>(num_samples),
        lang.get(),
        kw.get(),
        word_timestamps ? 1 : 0,
        out_buf.data(),
        static_cast<int>(out_buf.size())
    );

    env->ReleaseFloatArrayElements(pcm_16k_mono, pcm_ptr, JNI_ABORT);

    if (written < 0) {
        const char* err = cactus_last_error();
        LOGE("cactus_transcribe failed (%d): %s", written, err ? err : "unknown");
        std::string err_json = MakeErrorJson(err ? err : "cactus_transcribe failed", written);
        return env->NewStringUTF(err_json.c_str());
    }

    if (written < static_cast<int>(out_buf.size())) {
        out_buf[written] = '\0';
    } else {
        out_buf.back() = '\0';
    }

    return env->NewStringUTF(out_buf.data());
}

JNIEXPORT jstring JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeCompleteText(
    JNIEnv* env,
    jobject /* thiz */,
    jstring input,
    jint max_new_tokens
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ScopedUtfChars in_str(env, input);
    if (in_str.get() == nullptr) {
        return env->NewStringUTF(MakeErrorJson("input is null", -1).c_str());
    }

    std::vector<char> out_buf(kDefaultResponseBufferCapacity, '\0');
    int tokens = max_new_tokens > 0 ? static_cast<int>(max_new_tokens) : 512;

    int written = cactus_complete(
        in_str.get(),
        nullptr,
        0,
        tokens,
        out_buf.data(),
        static_cast<int>(out_buf.size())
    );

    if (written < 0) {
        const char* err = cactus_last_error();
        LOGE("cactus_complete(text) failed (%d): %s", written, err ? err : "unknown");
        return env->NewStringUTF(MakeErrorJson(err ? err : "cactus_complete failed", written).c_str());
    }

    if (written < static_cast<int>(out_buf.size())) {
        out_buf[written] = '\0';
    } else {
        out_buf.back() = '\0';
    }

    return env->NewStringUTF(out_buf.data());
}

JNIEXPORT jstring JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeCompleteAudio(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray pcm_16k_mono,
    jint max_new_tokens
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (pcm_16k_mono == nullptr) {
        return env->NewStringUTF(MakeErrorJson("pcm_16k_mono is null", -1).c_str());
    }

    jsize num_samples = env->GetArrayLength(pcm_16k_mono);
    if (num_samples <= 0) {
        return env->NewStringUTF(
            "{\"type\":\"call\",\"success\":true,\"function_calls\":[],\"confidence\":0.0,\"audio_text\":\"\",\"audio_language\":\"\"}"
        );
    }

    jfloat* pcm_ptr = env->GetFloatArrayElements(pcm_16k_mono, nullptr);
    if (pcm_ptr == nullptr) {
        return env->NewStringUTF(MakeErrorJson("failed to pin pcm array", -2).c_str());
    }

    std::vector<char> out_buf(kDefaultResponseBufferCapacity, '\0');
    int tokens = max_new_tokens > 0 ? static_cast<int>(max_new_tokens) : 512;

    int written = cactus_complete(
        nullptr,
        pcm_ptr,
        static_cast<int>(num_samples),
        tokens,
        out_buf.data(),
        static_cast<int>(out_buf.size())
    );

    env->ReleaseFloatArrayElements(pcm_16k_mono, pcm_ptr, JNI_ABORT);

    if (written < 0) {
        const char* err = cactus_last_error();
        LOGE("cactus_complete(audio) failed (%d): %s", written, err ? err : "unknown");
        return env->NewStringUTF(MakeErrorJson(err ? err : "cactus_complete audio failed", written).c_str());
    }

    if (written < static_cast<int>(out_buf.size())) {
        out_buf[written] = '\0';
    } else {
        out_buf.back() = '\0';
    }

    return env->NewStringUTF(out_buf.data());
}

JNIEXPORT void JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeReset(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    cactus_reset();
}

JNIEXPORT jboolean JNICALL
Java_com_cactus_assistant_engine_CactusNativeBridge_nativeIsUsingStub(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
#if defined(CACTUS_USING_STUB) && CACTUS_USING_STUB
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

}  // extern "C"
