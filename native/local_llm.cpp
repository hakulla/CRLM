#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LocalLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LocalModel {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> cachedTokens;
    std::atomic<bool> cancelRequested{false};
    std::mutex mutex;
};

std::string fromJava(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return "";
    }
    std::string text(raw);
    env->ReleaseStringUTFChars(value, raw);
    return text;
}

jstring toJava(JNIEnv * env, const std::string & text) {
    std::vector<jchar> utf16;
    utf16.reserve(text.size());
    for (size_t i = 0; i < text.size();) {
        unsigned char c = static_cast<unsigned char>(text[i]);
        uint32_t cp = 0;
        size_t extra = 0;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F;
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F;
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07;
            extra = 3;
        } else {
            cp = '?';
        }
        size_t j = 1;
        for (; j <= extra && i + j < text.size(); ++j) {
            unsigned char next = static_cast<unsigned char>(text[i + j]);
            if ((next & 0xC0) != 0x80) {
                cp = '?';
                extra = 0;
                break;
            }
            cp = (cp << 6) | (next & 0x3F);
        }
        i += (extra == 0 && cp == '?') ? 1 : j;
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.empty() ? nullptr : utf16.data(),
                          static_cast<jsize>(utf16.size()));
}

size_t utf8CompleteLength(const std::string & text) {
    size_t i = 0;
    size_t complete = 0;
    while (i < text.size()) {
        unsigned char c = static_cast<unsigned char>(text[i]);
        int length = 1;
        if (c < 0x80) {
            length = 1;
        } else if ((c & 0xE0) == 0xC0) {
            length = 2;
        } else if ((c & 0xF0) == 0xE0) {
            length = 3;
        } else if ((c & 0xF8) == 0xF0) {
            length = 4;
        } else {
            i++;
            complete = i;
            continue;
        }
        if (i + static_cast<size_t>(length) > text.size()) {
            break;
        }
        i += static_cast<size_t>(length);
        complete = i;
    }
    return complete;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_mobileagent_agent_LocalLlm_nativeLoad(JNIEnv * env, jclass,
                                               jstring path, jint threads,
                                               jint contextSize) {
    std::string modelPath = fromJava(env, path);
    if (modelPath.empty()) {
        return 0;
    }
    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.use_mmap = true;
    llama_model * model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (model == nullptr) {
        modelParams.use_mmap = false;
        model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    }
    if (model == nullptr) {
        LOGE("model load failed: %s", modelPath.c_str());
        return 0;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = contextSize > 0 ? static_cast<uint32_t>(contextSize) : 1536;
    contextParams.n_batch = 512;
    contextParams.n_ubatch = 512;
    contextParams.n_threads = threads > 0 ? threads : 4;
    contextParams.n_threads_batch = threads > 0 ? threads : 4;
    contextParams.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, contextParams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }

    LocalModel * holder = new LocalModel();
    holder->model = model;
    holder->ctx = ctx;
    holder->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(holder);
}

namespace {

bool runGeneration(LocalModel * holder, const std::string & text, int maxTokens,
                   float temperature,
                   const std::function<void(const std::string &)> & onToken,
                   std::string * output) {
    if (holder == nullptr || holder->ctx == nullptr || holder->vocab == nullptr
            || output == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> lock(holder->mutex);
    holder->cancelRequested = false;
    output->clear();
    if (text.empty()) {
        return false;
    }
    const bool addSpecial = true;
    const bool parseSpecial = true;
    int needed = llama_tokenize(holder->vocab, text.data(),
                                static_cast<int32_t>(text.size()),
                                nullptr, 0, addSpecial, parseSpecial);
    if (needed < 0) {
        needed = -needed;
    }
    if (needed <= 0) {
        return false;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    int count = llama_tokenize(holder->vocab, text.data(),
                               static_cast<int32_t>(text.size()),
                               tokens.data(), needed, addSpecial, parseSpecial);
    if (count <= 0) {
        return false;
    }
    tokens.resize(static_cast<size_t>(count));

    int limit = maxTokens > 0 ? maxTokens : 48;
    uint32_t nctx = llama_n_ctx(holder->ctx);
    int maxPrompt = static_cast<int>(nctx) - limit - 8;
    if (maxPrompt < 32) {
        maxPrompt = 32;
    }
    if (static_cast<int>(tokens.size()) > maxPrompt) {
        tokens.erase(tokens.begin(), tokens.begin()
                + (static_cast<int>(tokens.size()) - maxPrompt));
    }

    size_t common = 0;
    while (common < holder->cachedTokens.size() && common < tokens.size()
            && holder->cachedTokens[common] == tokens[common]) {
        common++;
    }
    if (common >= tokens.size()) {
        common = 0;
    }
    llama_memory_t memory = llama_get_memory(holder->ctx);
    if (common > 0) {
        if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1)) {
            llama_memory_clear(memory, true);
            common = 0;
        }
    } else {
        llama_memory_clear(memory, true);
    }

    const int batchSize = static_cast<int>(llama_n_batch(holder->ctx));
    for (size_t i = common; i < tokens.size();) {
        if (holder->cancelRequested.load()) {
            return false;
        }
        int chunk = std::min(static_cast<int>(tokens.size() - i), batchSize);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(holder->ctx, batch) != 0) {
            return false;
        }
        i += static_cast<size_t>(chunk);
    }
    holder->cachedTokens = tokens;

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    samplerParams.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(samplerParams);
    if (temperature > 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    for (int i = 0; i < limit; ++i) {
        if (holder->cancelRequested.load()) {
            break;
        }
        llama_token id = llama_sampler_sample(sampler, holder->ctx, -1);
        if (llama_vocab_is_eog(holder->vocab, id)) {
            break;
        }
        char piece[256];
        int size = llama_token_to_piece(holder->vocab, id, piece,
                                        static_cast<int32_t>(sizeof(piece)), 0, true);
        if (size > 0) {
            std::string value(piece, static_cast<size_t>(size));
            if (value.find("<|im_end|>") != std::string::npos) {
                break;
            }
            output->append(value);
            if (onToken) {
                onToken(value);
            }
        }
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(holder->ctx, batch) != 0) {
            break;
        }
    }
    llama_sampler_free(sampler);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_mobileagent_agent_LocalLlm_nativeGenerate(JNIEnv * env, jclass,
                                                   jlong handle, jstring prompt,
                                                   jint maxTokens, jfloat temperature) {
    LocalModel * holder = reinterpret_cast<LocalModel *>(handle);
    if (holder == nullptr) {
        return env->NewStringUTF("");
    }
    std::string output;
    runGeneration(holder, fromJava(env, prompt), maxTokens, temperature, nullptr, &output);
    return toJava(env, output);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_mobileagent_agent_LocalLlm_nativeGenerateStream(JNIEnv * env, jclass,
                                                         jlong handle, jstring prompt,
                                                         jint maxTokens, jfloat temperature,
                                                         jobject listener) {
    LocalModel * holder = reinterpret_cast<LocalModel *>(handle);
    if (holder == nullptr || listener == nullptr) {
        return JNI_FALSE;
    }
    jclass listenerClass = env->GetObjectClass(listener);
    if (listenerClass == nullptr) {
        return JNI_FALSE;
    }
    jmethodID onToken = env->GetMethodID(listenerClass, "onToken", "(Ljava/lang/String;)V");
    if (onToken == nullptr) {
        env->DeleteLocalRef(listenerClass);
        return JNI_FALSE;
    }
    std::string output;
    std::string pendingBytes;
    bool ok = runGeneration(holder, fromJava(env, prompt), maxTokens, temperature,
            [&](const std::string & piece) {
                pendingBytes += piece;
                size_t complete = utf8CompleteLength(pendingBytes);
                if (complete == 0) {
                    return;
                }
                std::string valid = pendingBytes.substr(0, complete);
                pendingBytes = pendingBytes.substr(complete);
                jstring value = toJava(env, valid);
                env->CallVoidMethod(listener, onToken, value);
                env->DeleteLocalRef(value);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    holder->cancelRequested.store(true);
                }
            }, &output);
    env->DeleteLocalRef(listenerClass);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_mobileagent_agent_LocalLlm_nativeCancel(JNIEnv *, jclass, jlong handle) {
    LocalModel * holder = reinterpret_cast<LocalModel *>(handle);
    if (holder != nullptr) {
        holder->cancelRequested.store(true);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_mobileagent_agent_LocalLlm_nativeInfo(JNIEnv * env, jclass, jlong handle) {
    LocalModel * holder = reinterpret_cast<LocalModel *>(handle);
    if (holder == nullptr || holder->model == nullptr) {
        return env->NewStringUTF("");
    }
    char desc[512];
    desc[0] = '\0';
    llama_model_desc(holder->model, desc, sizeof(desc));
    return toJava(env, std::string(desc));
}

extern "C" JNIEXPORT void JNICALL
Java_org_mobileagent_agent_LocalLlm_nativeFree(JNIEnv *, jclass, jlong handle) {
    LocalModel * holder = reinterpret_cast<LocalModel *>(handle);
    if (holder == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(holder->mutex);
        if (holder->ctx != nullptr) {
            llama_free(holder->ctx);
            holder->ctx = nullptr;
        }
        if (holder->model != nullptr) {
            llama_model_free(holder->model);
            holder->model = nullptr;
        }
    }
    delete holder;
}
