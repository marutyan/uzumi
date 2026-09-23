package dev.uzumi.ime.conversion

import java.text.Normalizer

/**
 * モデルごとのプロンプト形式。Phase 3aの段階0（`docs/phase3a-stage0.md`の「固定した入力形式」）で固定した規則を
 * ここ一か所に置き、Macでの確認（`tools/phase3a/stage0.py`）と同じ文字列を作る。
 * 版を上げたら変換結果の世代も変わり、前の形式で作った結果を適用しない。
 */
enum class NeuralPromptFormat(
    /** llama.cppのtokenizeで区切り記号を特殊tokenとして読むか（jinenの`U+EE00`〜`U+EE02`は単独token）。 */
    val parseSpecial: Boolean,
) {
    /** zenz v3.2：左文脈があるときだけ`U+EE02`を付け、半角空白を全角へ写す。 */
    ZENZ(parseSpecial = false),

    /** jinen v2：左文脈が空でも`U+EE02`を付け、プロンプト全体をNFKC正規化する。 */
    JINEN(parseSpecial = true),
    ;

    /** 読み（かな）と左文脈からプロンプトを作る。左文脈は後ろの64文字だけを使う。 */
    fun build(reading: String, leftContext: String): String {
        val context = sanitize(leftContext).takeLastCodePoints(MAX_CONTEXT_CODE_POINTS)
        val kana = toKatakana(sanitize(reading))
        return when (this) {
            ZENZ -> {
                val prompt = (if (context.isEmpty()) "" else CONTEXT_TAG + context) + INPUT_TAG + kana + OUTPUT_TAG
                prompt.replace(' ', '　')
            }
            JINEN -> Normalizer.normalize(CONTEXT_TAG + context + INPUT_TAG + kana + OUTPUT_TAG, Normalizer.Form.NFKC)
        }
    }

    companion object {
        /** 形式の版。規則を変えたら上げる。段階0で固定した版が1。 */
        const val VERSION = 1

        // 評価条件で固定した左文脈の最大の文字数（Unicodeの符号位置で数える。段階0の確認と同じ数え方）。
        const val MAX_CONTEXT_CODE_POINTS = 64

        // プロンプトの区切り記号。U+EE02が左文脈、U+EE00が読みの始まり、U+EE01が出力の始まり。
        private const val CONTEXT_TAG = ""
        private const val INPUT_TAG = ""
        private const val OUTPUT_TAG = ""

        /** 欄から区切り記号（U+EE00..U+EE0F）と制御文字を除き、欄の中身でプロンプトの形が変わらないようにする。 */
        fun sanitize(text: String): String = buildString {
            text.codePoints().forEach { cp ->
                if (cp !in 0xEE00..0xEE0F && cp >= 0x20 && cp != 0x7F) appendCodePoint(cp)
            }
        }

        /** ひらがな（ぁ..ゖ、ゝゞ）を同じ音のカタカナへ写す。 */
        fun toKatakana(text: String): String = buildString {
            text.codePoints().forEach { cp ->
                appendCodePoint(if (cp in 0x3041..0x3096 || cp in 0x309D..0x309E) cp + 0x60 else cp)
            }
        }

        /** 後ろからcount個の符号位置だけを返す。サロゲートの途中では切らない。 */
        private fun String.takeLastCodePoints(count: Int): String {
            val total = codePointCount(0, length)
            if (total <= count) return this
            return substring(offsetByCodePoints(0, total - count))
        }
    }
}

/**
 * 評価の比較条件のモデル。ファイル名とSHA-256は`docs/neural-probe.md`の固定値で、端末では
 * アプリのfilesの下の`neural/<fileName>`へadbで置いたファイルを、SHA-256を確かめてから読む。
 */
enum class NeuralModelSpec(
    /** 評価条件の条件名（debugの設定とadbの指定に使う）。 */
    val key: String,
    val fileName: String,
    val sha256: String,
    val format: NeuralPromptFormat,
) {
    ZENZ_SMALL("ZS", "zenz-v3.2-small-Q5_K_M.gguf", "29c223d4c23327b80fd13ebb5ab2555057a46317997d5da391584ffbef0db673", NeuralPromptFormat.ZENZ),
    ZENZ_XSMALL("ZX", "zenz-v3.2-xsmall-Q5_K_M.gguf", "00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58", NeuralPromptFormat.ZENZ),
    JINEN_SMALL("JS", "jinen-v2-small-Q5_K_M.gguf", "80482707513d6b67dafc31774371cf95d765542abf8d74eebf5f32f92d788bd3", NeuralPromptFormat.JINEN),
    JINEN_XSMALL("JX", "jinen-v2-xsmall-Q5_K_M.gguf", "24ff3af5db712fbbb4aa9254ee28ec4d731207134471ab68b06c1828726284c2", NeuralPromptFormat.JINEN),
    ;

    /**
     * 変換結果の世代（`converterGeneration`）。モデルのSHA-256とプロンプト形式の版から決め、
     * モデルや形式を切り替えた後に前の世代の結果を適用しないようにする。Mozcだけの条件は0。
     */
    val generation: Long
        get() = (sha256.substring(0, 12).toLong(16) shl 4) or NeuralPromptFormat.VERSION.toLong()

    companion object {
        /** 条件名からモデルを引く。Mozcだけ（`M`）や未知の名前ではnull。 */
        fun fromKey(key: String?): NeuralModelSpec? = entries.firstOrNull { it.key == key }
    }
}

// 推論の設定（評価条件で全モデル共通に固定した値）。
object NeuralInferenceSettings {
    /** 推論のthread数。 */
    const val THREADS = 4

    /** llama.cppの`n_ctx`。 */
    const val N_CTX = 512

    /** 最大出力token。段階0の打ち切り率が0%だったため64のまま。 */
    const val MAX_TOKENS = 64

    /** 入力の受理からこの時間を超えたら、モデルを待たずMozcの結果を使う（ミリ秒）。 */
    const val TIMEOUT_MILLIS = 300L
}
