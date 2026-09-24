// Phase 3aの段階0（入力形式の確認）で使う、Mac向けの最小の推論プログラム。
// 標準入力の1行「id<TAB>cmd<TAB>prompt」を受け、cmdが`tok`ならllama.cpp内蔵のtokenizerのtoken列だけを、
// `gen`ならtoken列とgreedyの出力を1行で返す。出力の文字列は壊れたUTF-8も確かめられるよう16進で返す。
// 端末での推論（JNIの橋渡し）とは別物であり、評価条件の段階0で形式・停止条件・打ち切り率を確かめるためだけに使う。
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include "llama.h"

// 経過時間をミリ秒で返す。
static double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

// 1 tokenをUTF-8のbyte列へ戻す。区切り記号（U+EE00..U+EE0F）の検出に使うため特殊tokenも文字として出す。
static std::string piece(const llama_vocab *vocab, llama_token t) {
    std::vector<char> buf(32);
    int n = llama_token_to_piece(vocab, t, buf.data(), (int) buf.size(), 0, true);
    if (n < 0) {
        buf.resize(-n);
        n = llama_token_to_piece(vocab, t, buf.data(), (int) buf.size(), 0, true);
    }
    return n > 0 ? std::string(buf.data(), n) : std::string();
}

// byte列がU+EE00..U+EE0F（UTF-8ではEE B8 80..8F）を含むか。
static bool has_marker(const std::string &s) {
    for (size_t i = 0; i + 2 < s.size(); ++i) {
        if ((unsigned char) s[i] == 0xEE && (unsigned char) s[i + 1] == 0xB8 &&
            (unsigned char) s[i + 2] >= 0x80 && (unsigned char) s[i + 2] <= 0x8F) {
            return true;
        }
    }
    return false;
}

// byte列を16進の文字列にする。
static std::string hex(const std::string &s) {
    static const char *digits = "0123456789abcdef";
    std::string out;
    for (unsigned char c : s) {
        out.push_back(digits[c >> 4]);
        out.push_back(digits[c & 15]);
    }
    return out;
}

int main(int argc, char **argv) {
    if (argc < 6) {
        std::fprintf(stderr, "usage: stage0_probe <model.gguf> <parse_special 0|1> <threads> <max_tokens> <n_ctx>\n");
        return 2;
    }
    const char *model_path = argv[1];
    const bool parse_special = std::strcmp(argv[2], "1") == 0;
    const int threads = std::atoi(argv[3]);
    const int max_tokens = std::atoi(argv[4]);
    const int n_ctx = std::atoi(argv[5]);

    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // 端末に近い条件としてCPUだけで動かす。
    llama_model *model = llama_model_load_from_file(model_path, mp);
    if (!model) {
        std::printf("#load_failed\n");
        return 1;
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = n_ctx;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        std::printf("#context_failed\n");
        return 1;
    }
    std::printf("#ready\n");
    std::fflush(stdout);

    std::string line;
    while (std::getline(std::cin, line)) {
        const size_t tab1 = line.find('\t');
        const size_t tab2 = tab1 == std::string::npos ? std::string::npos : line.find('\t', tab1 + 1);
        if (tab2 == std::string::npos) {
            std::printf("#bad_line\n");
            std::fflush(stdout);
            continue;
        }
        const std::string id = line.substr(0, tab1);
        const std::string cmd = line.substr(tab1 + 1, tab2 - tab1 - 1);
        const std::string prompt = line.substr(tab2 + 1);

        std::vector<llama_token> toks(prompt.size() + 8);
        int n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(),
                               /*add_special=*/false, parse_special);
        if (n < 0) {
            toks.resize(-n);
            n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), false,
                               parse_special);
        }
        if (n <= 0) {
            std::printf("%s\t0\t\t-1\ttokenize_failed\t0\t0\t\n", id.c_str());
            std::fflush(stdout);
            continue;
        }
        toks.resize(n);
        std::string ids;
        for (int i = 0; i < n; ++i) ids += (i ? "," : "") + std::to_string(toks[i]);
        if (cmd != "gen") {
            std::printf("%s\t%d\t%s\t-1\ttok\t0\t0\t\n", id.c_str(), n, ids.c_str());
            std::fflush(stdout);
            continue;
        }

        llama_memory_clear(llama_get_memory(ctx), true);
        auto t0 = std::chrono::steady_clock::now();
        std::string out;
        std::string termination = "token_limit";
        int generated = 0;
        if (n >= n_ctx || llama_decode(ctx, llama_batch_get_one(toks.data(), (int32_t) toks.size())) != 0) {
            termination = n >= n_ctx ? "context_limit" : "decode_failed";
        } else {
            const int n_vocab = llama_vocab_n_tokens(vocab);
            const int limit = std::min(max_tokens, n_ctx - n);
            for (int step = 0; step < limit; ++step) {
                const float *logits = llama_get_logits_ith(ctx, -1);
                llama_token best = 0;
                for (llama_token t = 1; t < n_vocab; ++t) {
                    if (logits[t] > logits[best]) best = t;
                }
                if (llama_vocab_is_eog(vocab, best)) {
                    termination = "eog";
                    break;
                }
                out += piece(vocab, best);
                ++generated;
                if (has_marker(out)) {
                    termination = "marker";
                    break;
                }
                if (step + 1 >= limit) break;
                if (llama_decode(ctx, llama_batch_get_one(&best, 1)) != 0) {
                    termination = "decode_failed";
                    break;
                }
            }
        }
        const double total_ms = ms_since(t0);
        std::printf("%s\t%d\t%s\t%d\t%s\t%.2f\t0\t%s\n", id.c_str(), n, ids.c_str(), generated, termination.c_str(),
                    total_ms, hex(out).c_str());
        std::fflush(stdout);
    }
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
