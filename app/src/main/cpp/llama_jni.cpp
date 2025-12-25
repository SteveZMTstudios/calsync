#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <chrono>
#include <android/log.h>
#include <memory>
#include <mutex>

// llama.cpp headers (modern API)
#include "llama.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "llama_jni", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "llama_jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "llama_jni", __VA_ARGS__)

namespace {
    constexpr size_t kMaxOutputChars = 4096;
    constexpr int64_t kMaxGenMillis = 10000;  // 10s timeout
    constexpr int kMinContextSize = 256;
    
    // Static init guard
    static bool g_backend_initialized = false;
    static std::mutex g_backend_mutex;
}

/**
 * LlamaContext: manages both model and context lifecycle safely.
 * Holds unique ownership of both resources.
 */
class LlamaContext {
public:
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    
    LlamaContext() = default;
    
    ~LlamaContext() {
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_model_free(model);
            model = nullptr;
        }
    }
    
    bool isValid() const {
        return model != nullptr && ctx != nullptr;
    }
    
    // Disable copy
    LlamaContext(const LlamaContext&) = delete;
    LlamaContext& operator=(const LlamaContext&) = delete;
};

static std::string jstringToUtf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return {};
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static void initBackend() {
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        LOGD("llama backend initialized");
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_stevezmt_calsync_llm_LlamaCpp_nativeInit(JNIEnv* env, jclass, jstring jModelPath, jint nCtx, jint nThreads) {
    try {
        const std::string modelPath = jstringToUtf8(env, jModelPath);
        if (modelPath.empty()) {
            LOGE("nativeInit: empty model path");
            return 0;
        }
        
        initBackend();
        
        // Validate context size
        int nCtxClamped = (int)std::max((jint)kMinContextSize, std::min(nCtx, (jint)4096));
        int nThreadsClamped = (int)std::max((jint)1, std::min(nThreads, (jint)16));
        
        LOGD("nativeInit: model=%s ctx=%d threads=%d", modelPath.c_str(), nCtxClamped, nThreadsClamped);
        
        // Load model (CPU-only)
        llama_model_params mparams = llama_model_default_params();
        llama_model* model = llama_model_load_from_file(modelPath.c_str(), mparams);
        if (!model) {
            LOGE("nativeInit: failed to load model from %s", modelPath.c_str());
            return 0;
        }
        
        // Create context with optimized parameters
        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t)nCtxClamped;
        cparams.n_threads = (int32_t)nThreadsClamped;
        cparams.n_threads_batch = (int32_t)nThreadsClamped;
        // Use same values as llama-cli for better performance
        cparams.n_batch = 2048;      // max tokens to process in parallel
        cparams.n_ubatch = 512;      // physical batch size for prompt processing
        cparams.no_perf = false;     // enable perf stats for debugging
        
        llama_context* ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            LOGE("nativeInit: failed to create llama context");
            llama_model_free(model);
            return 0;
        }
        
        // Create managed wrapper
        auto* llama_ctx = new LlamaContext();
        llama_ctx->model = model;
        llama_ctx->ctx = ctx;
        
        // Warmup: run a small decode to initialize caches
        LOGD("nativeInit: performing warmup...");
        llama_token warmup_token = llama_vocab_bos(llama_model_get_vocab(model));
        llama_batch warmup_batch = llama_batch_init(1, 0, 1);
        warmup_batch.token[0] = warmup_token;
        warmup_batch.pos[0] = 0;
        warmup_batch.n_seq_id[0] = 1;
        warmup_batch.seq_id[0][0] = 0;
        warmup_batch.logits[0] = 1;
        warmup_batch.n_tokens = 1;
        
        int warmup_result = llama_decode(ctx, warmup_batch);
        llama_batch_free(warmup_batch);
        
        if (warmup_result == 0) {
            LOGD("nativeInit: warmup complete");
        } else {
            LOGW("nativeInit: warmup returned %d (may be OK)", warmup_result);
        }
        
        // Clear sequence 0 after warmup (removes all KV cache for this sequence)
        llama_memory_seq_rm(llama_get_memory(ctx), 0, -1, -1);
        llama_synchronize(ctx);
        LOGD("nativeInit: KV cache cleared after warmup");
        
        LOGD("nativeInit: success, handle=%p", (void*)llama_ctx);
        return reinterpret_cast<jlong>(llama_ctx);
    } catch (const std::exception& e) {
        LOGE("nativeInit: exception: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("nativeInit: unknown exception");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_stevezmt_calsync_llm_LlamaCpp_nativeFree(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    
    try {
        auto* llama_ctx = reinterpret_cast<LlamaContext*>(handle);
        LOGD("nativeFree: freeing handle=%p", (void*)llama_ctx);
        delete llama_ctx;
    } catch (...) {
        LOGE("nativeFree: exception during cleanup");
    }
}

// Complete: generates text using the loaded model with proper error handling
extern "C" JNIEXPORT jstring JNICALL
Java_top_stevezmt_calsync_llm_LlamaCpp_nativeComplete(JNIEnv* env, jclass, jlong handle, jstring jPrompt, jint maxTokens) {
    auto* llama_ctx = reinterpret_cast<LlamaContext*>(handle);
    
    if (!llama_ctx || !llama_ctx->isValid()) {
        LOGE("nativeComplete: invalid context handle");
        return env->NewStringUTF("");
    }
    
    const std::string prompt = jstringToUtf8(env, jPrompt);
    if (prompt.empty()) {
        LOGW("nativeComplete: empty prompt");
        return env->NewStringUTF("");
    }
    
    try {
        llama_context* ctx = llama_ctx->ctx;
        llama_model* model = llama_ctx->model;
        const llama_vocab* vocab = llama_model_get_vocab(model);
        
        if (!vocab) {
            LOGE("nativeComplete: failed to get vocab");
            return env->NewStringUTF("");
        }
        
        const int n_ctx = llama_n_ctx(ctx);
        LOGD("nativeComplete: start prompt_len=%zu ctx=%d maxTokens=%d", prompt.length(), n_ctx, (int)maxTokens);
        
        // Tokenize input
        std::vector<llama_token> tokens;
        tokens.resize(std::max((size_t)prompt.size() + 32, (size_t)64));
        
        int n_tok = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), (int)tokens.size(), true, true);
        
        // Resize if needed
        if (n_tok < 0) {
            tokens.resize((size_t)(-n_tok));
            n_tok = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), (int)tokens.size(), true, true);
        }
        
        if (n_tok <= 0) {
            LOGW("nativeComplete: tokenization failed, n_tok=%d", n_tok);
            return env->NewStringUTF("");
        }
        
        tokens.resize((size_t)n_tok);
        
        if (n_tok >= n_ctx - 8) {
            LOGW("nativeComplete: prompt too long for context, n_tok=%d ctx=%d", n_tok, n_ctx);
            return env->NewStringUTF("");
        }
        
        LOGD("nativeComplete: tokenized n_tok=%d", n_tok);
        
        // Clear sequence 0 before new inference (removes all KV cache)
        llama_memory_seq_rm(llama_get_memory(ctx), 0, -1, -1);
        llama_synchronize(ctx);
        LOGD("nativeComplete: KV cache cleared");
        
        // Process prompt in smaller chunks to avoid hanging
        const auto t_prompt_start = std::chrono::steady_clock::now();
        const int chunk_size = 256;  // Smaller chunks for mobile device
        
        LOGD("nativeComplete: decoding %d tokens in chunks of %d", n_tok, chunk_size);
        
        llama_batch batch = llama_batch_init(chunk_size, 0, 1);
        
        for (int i = 0; i < n_tok; i += chunk_size) {
            const int n_eval = std::min(chunk_size, n_tok - i);
            
            if (i % (chunk_size * 2) == 0 || i + n_eval >= n_tok) {
                LOGD("nativeComplete: chunk %d-%d/%d", i, i + n_eval - 1, n_tok);
            }
            
            // Fill batch manually with correct positions
            batch.n_tokens = n_eval;
            for (int j = 0; j < n_eval; ++j) {
                batch.token[j] = tokens[i + j];
                batch.pos[j] = i + j;
                batch.n_seq_id[j] = 1;
                batch.seq_id[j][0] = 0;
                batch.logits[j] = false;
            }
            // Only compute logits for last token of last chunk
            if (i + n_eval >= n_tok) {
                batch.logits[n_eval - 1] = true;
            }
            
            if (llama_decode(ctx, batch) != 0) {
                LOGE("nativeComplete: decode failed at chunk %d-%d", i, i + n_eval - 1);
                llama_batch_free(batch);
                return env->NewStringUTF("");
            }
        }
        
        llama_batch_free(batch);

        
        const auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_prompt_start
        ).count();
        
        LOGD("nativeComplete: prompt processed in %lldms", (long long)prompt_ms);
        
        std::string output;
        output.reserve(kMaxOutputChars);
        
        int n_pos = n_tok;
        const int capped_max_tokens = (int)std::min((jint)256, std::max((jint)1, maxTokens));
        
        // Create sampler (greedy)
        llama_sampler* sampler = llama_sampler_init_greedy();
        if (!sampler) {
            LOGE("nativeComplete: failed to create sampler");
            return env->NewStringUTF("");
        }
        
        const auto t_gen_start = std::chrono::steady_clock::now();
        int tokens_generated = 0;
        
        for (int i = 0; i < capped_max_tokens; i++) {
            // Check time limit
            const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t_gen_start
            ).count();
            
            if (elapsed_ms > kMaxGenMillis) {
                LOGD("nativeComplete: generation timeout after %lldms", (long long)elapsed_ms);
                break;
            }
            
            // Check output size limit
            if (output.size() >= kMaxOutputChars) {
                LOGD("nativeComplete: output size limit reached");
                break;
            }
            
            // Check context space
            if (n_pos >= n_ctx - 2) {
                LOGD("nativeComplete: context full n_pos=%d n_ctx=%d", n_pos, n_ctx);
                break;
            }
            
            // Sample next token
            const llama_token next_token = llama_sampler_sample(sampler, ctx, -1);
            
            // Check for end-of-generation
            if (llama_vocab_is_eog(vocab, next_token)) {
                LOGD("nativeComplete: end-of-generation token");
                break;
            }
            
            // Convert token to string
            std::vector<char> piece_buf(64);
            int piece_len = llama_token_to_piece(vocab, next_token, piece_buf.data(), (int)piece_buf.size(), 0, true);
            
            if (piece_len < 0) {
                piece_buf.resize((size_t)(-piece_len));
                piece_len = llama_token_to_piece(vocab, next_token, piece_buf.data(), (int)piece_buf.size(), 0, true);
            }
            
            if (piece_len > 0) {
                output.append(piece_buf.data(), (size_t)piece_len);
            }
            
            // Eval next token (need mutable token for llama_batch_get_one)
            llama_token mutable_next = next_token;
            batch = llama_batch_get_one(&mutable_next, 1);
            
            if (llama_decode(ctx, batch) != 0) {
                LOGE("nativeComplete: llama_decode(gen token) failed");
                break;
            }
            
            n_pos += 1;
            tokens_generated++;
            
            if ((i + 1) % 16 == 0) {
                const auto step_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - t_gen_start
                ).count();
                LOGD("nativeComplete: gen %d tokens in %lldms", i + 1, (long long)step_ms);
            }
        }
        
        llama_sampler_free(sampler);
        
        const auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_gen_start
        ).count();
        
        LOGD("nativeComplete: done tokens_gen=%d time=%lldms output_len=%zu", tokens_generated, (long long)total_ms, output.size());
        
        return env->NewStringUTF(output.c_str());
        
    } catch (const std::exception& e) {
        LOGE("nativeComplete: exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("nativeComplete: unknown exception");
        return env->NewStringUTF("");
    }
}
