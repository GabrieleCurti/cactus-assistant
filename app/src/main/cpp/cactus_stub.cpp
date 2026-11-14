#include "cactus.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

namespace {

int g_loaded_models = 0;
std::string g_last_error;
std::string g_system_prompt;
std::string g_tools_json;
std::string g_audio_language = "en";
std::string g_audio_keywords;
int g_word_timestamps = 0;

int WriteStringToBuffer(const std::string& str, char* out, int out_capacity) {
    if (out == nullptr || out_capacity <= 0) {
        g_last_error = "output buffer is null or empty";
        return -1;
    }
    int to_copy = static_cast<int>(str.size());
    if (to_copy >= out_capacity) {
        to_copy = out_capacity - 1;
    }
    std::memcpy(out, str.data(), static_cast<size_t>(to_copy));
    out[to_copy] = '\0';
    return to_copy;
}

}  // namespace

extern "C" {

CACTUS_API int cactus_load(const unsigned char* cact, unsigned long long n) {
    if (cact == nullptr || n < 4) {
        g_last_error = "invalid .cact archive buffer";
        return -1;
    }
    // Inspect header/content to mark model family bitmask (1 = Needle 3, 2 = Whistle)
    std::string prefix(reinterpret_cast<const char*>(cact), n < 128 ? static_cast<size_t>(n) : 128);
    if (prefix.find("whistle") != std::string::npos || prefix.find("WHISTLE") != std::string::npos) {
        g_loaded_models |= 2;
    } else if (prefix.find("needle") != std::string::npos || prefix.find("NEEDLE") != std::string::npos) {
        g_loaded_models |= 1;
    } else {
        g_loaded_models |= 3;
    }
    g_last_error.clear();
    return g_loaded_models;
}

CACTUS_API int cactus_models(void) {
    return g_loaded_models;
}

CACTUS_API const char* cactus_last_error(void) {
    return g_last_error.c_str();
}

CACTUS_API int cactus_init(
    const char* system_prompt,
    const char* tools_json,
    const char* /* tool_index_path */
) {
    g_system_prompt = system_prompt != nullptr ? system_prompt : "";
    g_tools_json = tools_json != nullptr ? tools_json : "[]";
    g_last_error.clear();
    return 3;
}

CACTUS_API void cactus_set_audio(
    const char* language,
    const char* keywords,
    int word_timestamps
) {
    g_audio_language = (language != nullptr && language[0] != '\0') ? language : "en";
    g_audio_keywords = keywords != nullptr ? keywords : "";
    g_word_timestamps = word_timestamps;
}

CACTUS_API void cactus_set_threads(int /* threads */) {
}

CACTUS_API void cactus_reset(void) {
    g_last_error.clear();
}

CACTUS_API int cactus_transcribe(
    const float* pcm,
    int samples,
    const char* language,
    const char* /* keywords */,
    int /* word_timestamps */,
    char* out,
    int out_capacity
) {
    if (pcm == nullptr || samples <= 0) {
        return WriteStringToBuffer(
            "{\"text\":\"\",\"language\":\"\",\"ttft_ms\":0.0,\"decode_tps\":0.0}",
            out,
            out_capacity
        );
    }

    // Measure loudness range (silence gate, matching Whistle behavior)
    float max_abs = 0.0f;
    for (int i = 0; i < samples; ++i) {
        float a = std::fabs(pcm[i]);
        if (a > max_abs) {
            max_abs = a;
        }
    }
    const char* lang = (language != nullptr && language[0] != '\0')
                           ? language
                           : g_audio_language.c_str();
    if (max_abs < 1e-4f) {
        return WriteStringToBuffer(
            "{\"text\":\"\",\"language\":\"\",\"ttft_ms\":1.2,\"decode_tps\":0.0}",
            out,
            out_capacity
        );
    }

    std::string response = std::string("{\"text\":\"\",\"language\":\"") + lang +
                           "\",\"ttft_ms\":6.1,\"decode_tps\":1319.0,\"stub\":true}";
    return WriteStringToBuffer(response, out, out_capacity);
}

CACTUS_API int cactus_complete(
    const char* input,
    const float* pcm,
    int samples,
    int /* max_new_tokens */,
    char* out,
    int out_capacity
) {
    std::string text_input = input != nullptr ? input : "";
    if (text_input.empty() && (pcm == nullptr || samples <= 0)) {
        return WriteStringToBuffer(
            "{\"type\":\"call\",\"success\":true,\"function_calls\":[],\"confidence\":0.0}",
            out,
            out_capacity
        );
    }

    // When compiled against stub without libcactus.a, return stub marker so Kotlin
    // layer can run its grounded Needle-compatible tool parser seamlessly.
    std::string response =
        "{\"type\":\"call\",\"success\":true,\"stub\":true,\"function_calls\":[],\"confidence\":0.0}";
    return WriteStringToBuffer(response, out, out_capacity);
}

CACTUS_API int cactus_embed(
    const char* /* input */,
    const float* /* pcm */,
    int /* samples */,
    float* out,
    int out_capacity
) {
    if (out == nullptr || out_capacity <= 0) {
        return -1;
    }
    int dim = out_capacity < 64 ? out_capacity : 64;
    for (int i = 0; i < dim; ++i) {
        out[i] = 0.0f;
    }
    return dim;
}

}  // extern "C"
