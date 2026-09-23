// Uzumiのニューラルかな漢字変換で、llama.cppを呼ぶJNIの橋渡し。`:neural`のプロセスでだけ読み込まれる。
// Kotlin側は dev.uzumi.ime.neural.NativeNeuralBridge。プロンプトの組み立て（モデルごとの形式）はKotlin側で行い、
// ここではtokenize、greedyの生成、停止条件、要求番号による中断だけを行う。入力と出力の文字列は記録しない。
// 要求番号で古い推論を止める作りと停止条件は、スミレ（MIT）のzenz_bridge.cppの設計を参考にした。コードは写していない。
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

// 推論の終わり方の番号。Kotlin側のNeuralTerminationと同じ値にする。
enum Termination : int {
    kEog = 0,
    kMarker = 1,
    kTokenLimit = 2,
    kCancelled = 3,
    kContextLimit = 4,
    kDecodeError = 5,
    kNotLoaded = 6,
    kTokenizeFailed = 7,
};

// モデルと文脈。g_mutexの中だけで触る。推論は一度に一つだけ。
std::mutex g_mutex;
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
const llama_vocab *g_vocab = nullptr;
int g_n_ctx = 0;
bool g_backend_initialized = false;

// 最新の要求番号と、中断済みの要求番号の上限。binderのthreadから書き、推論のthreadが中断の判定で読む。
std::atomic<int64_t> g_latest{0};
std::atomic<int64_t> g_cancelled_through{0};

// 要求番号の推論を続けてよいか。新しい要求が来たか、中断されたらfalse。
bool is_stale(int64_t request_id) {
    return request_id != g_latest.load(std::memory_order_relaxed) ||
           request_id <= g_cancelled_through.load(std::memory_order_relaxed);
}

// llama.cppの中断callbackへ渡す状態。
struct AbortState {
    int64_t request_id;
};

// 計算の途中でllama.cppから呼ばれ、古い要求ならtrueを返して計算を止める。
bool abort_if_stale(void *data) {
    return is_stale(static_cast<AbortState *>(data)->request_id);
}

// 中断しないcallback。推論を終えたら戻す。
bool never_abort(void *) {
    return false;
}

// 1 tokenをUTF-8のbyte列へ戻す。区切り記号（特殊token）も文字として出し、停止の判定に使う。
std::string token_piece(llama_token token) {
    std::vector<char> buffer(32);
    int n = llama_token_to_piece(g_vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, true);
    if (n < 0) {
        buffer.resize(static_cast<size_t>(-n));
        n = llama_token_to_piece(g_vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, true);
    }
    return n > 0 ? std::string(buffer.data(), static_cast<size_t>(n)) : std::string();
}

// byte列の中で、最初のU+EE00..U+EE0F（UTF-8ではEE B8 80..8F）の位置。無ければnpos。
size_t marker_position(const std::string &text) {
    for (size_t i = 0; i + 2 < text.size(); ++i) {
        const auto b0 = static_cast<unsigned char>(text[i]);
        const auto b1 = static_cast<unsigned char>(text[i + 1]);
        const auto b2 = static_cast<unsigned char>(text[i + 2]);
        if (b0 == 0xEE && b1 == 0xB8 && b2 >= 0x80 && b2 <= 0x8F) return i;
    }
    return std::string::npos;
}

// 終わり方の番号を先頭1 byteに、出力のUTF-8を続けたbyte配列を作る。
jbyteArray make_result(JNIEnv *env, int termination, const std::string &text) {
    const auto size = static_cast<jsize>(text.size() + 1);
    jbyteArray array = env->NewByteArray(size);
    if (array == nullptr) return nullptr;
    const auto code = static_cast<jbyte>(termination);
    env->SetByteArrayRegion(array, 0, 1, &code);
    if (!text.empty()) {
        env->SetByteArrayRegion(array, 1, size - 1, reinterpret_cast<const jbyte *>(text.data()));
    }
    return array;
}

// モデルと文脈を解放する。g_mutexの中で呼ぶ。
void free_model_locked() {
    if (g_ctx != nullptr) {
        llama_set_abort_callback(g_ctx, never_abort, nullptr);
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    g_n_ctx = 0;
}

// greedyで生成し、終わり方と出力を返す。g_mutexの中で呼ぶ。
int generate_locked(int64_t request_id, const std::string &prompt, bool parse_special, int max_tokens, std::string *out) {
    if (g_model == nullptr || g_ctx == nullptr) return kNotLoaded;
    if (is_stale(request_id)) return kCancelled;

    std::vector<llama_token> tokens(prompt.size() + 8);
    int n = llama_tokenize(g_vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), /*add_special=*/false, parse_special);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(g_vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), false, parse_special);
    }
    if (n <= 0) return kTokenizeFailed;
    tokens.resize(static_cast<size_t>(n));
    if (n >= g_n_ctx) return kContextLimit;
    const int limit = std::min(max_tokens, g_n_ctx - n);
    if (limit <= 0) return kContextLimit;

    llama_memory_clear(llama_get_memory(g_ctx), true);
    AbortState state{request_id};
    llama_set_abort_callback(g_ctx, abort_if_stale, &state);
    // 途中で戻る場合も中断callbackを外すための終わりの処理。
    struct Restore {
        ~Restore() { llama_set_abort_callback(g_ctx, never_abort, nullptr); }
    } restore;

    if (llama_decode(g_ctx, llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()))) != 0) {
        return is_stale(request_id) ? kCancelled : kDecodeError;
    }
    const int n_vocab = llama_vocab_n_tokens(g_vocab);
    for (int step = 0; step < limit; ++step) {
        if (is_stale(request_id)) return kCancelled;
        const float *logits = llama_get_logits_ith(g_ctx, -1);
        if (logits == nullptr) return kDecodeError;
        llama_token best = 0;
        for (llama_token t = 1; t < n_vocab; ++t) {
            if (logits[t] > logits[best]) best = t;
        }
        if (llama_vocab_is_eog(g_vocab, best)) return kEog;
        out->append(token_piece(best));
        const size_t marker = marker_position(*out);
        if (marker != std::string::npos) {
            out->resize(marker);
            return kMarker;
        }
        // 最後に許した1 tokenの後は、次を求めないので計算しない。
        if (step + 1 >= limit) break;
        if (llama_decode(g_ctx, llama_batch_get_one(&best, 1)) != 0) {
            return is_stale(request_id) ? kCancelled : kDecodeError;
        }
    }
    return kTokenLimit;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_uzumi_ime_neural_NativeNeuralBridge_nativeLoad(JNIEnv *env, jobject, jstring path, jint n_ctx, jint n_threads) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const std::string model_path(chars);
    env->ReleaseStringUTFChars(path, chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
    if (!g_backend_initialized) {
        llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        llama_backend_init();
        g_backend_initialized = true;
    }
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (g_model == nullptr) return JNI_FALSE;
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(n_ctx);
    context_params.n_batch = static_cast<uint32_t>(n_ctx);
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;
    g_ctx = llama_init_from_model(g_model, context_params);
    if (g_ctx == nullptr) {
        free_model_locked();
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);
    g_n_ctx = n_ctx;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uzumi_ime_neural_NativeNeuralBridge_nativeMarkLatest(JNIEnv *, jobject, jlong request_id) {
    g_latest.store(request_id, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uzumi_ime_neural_NativeNeuralBridge_nativeCancel(JNIEnv *, jobject, jlong request_id) {
    int64_t current = g_cancelled_through.load(std::memory_order_relaxed);
    while (current < request_id && !g_cancelled_through.compare_exchange_weak(current, request_id)) {
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_uzumi_ime_neural_NativeNeuralBridge_nativeGenerate(JNIEnv *env, jobject, jlong request_id, jbyteArray prompt,
                                                          jboolean parse_special, jint max_tokens) {
    const jsize length = env->GetArrayLength(prompt);
    std::string text(static_cast<size_t>(length), '\0');
    if (length > 0) env->GetByteArrayRegion(prompt, 0, length, reinterpret_cast<jbyte *>(text.data()));
    std::string output;
    int termination;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        termination = generate_locked(request_id, text, parse_special == JNI_TRUE, max_tokens, &output);
    }
    // 打ち切り以外で失敗した場合は、途中までの出力を返さない。
    if (termination != kEog && termination != kMarker && termination != kTokenLimit) output.clear();
    return make_result(env, termination, output);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uzumi_ime_neural_NativeNeuralBridge_nativeClose(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
}
