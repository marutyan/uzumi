package dev.uzumi.ime.editor

/**
 * 候補行へ表示する最小候補を表す。漢字辞書の結果はこの型へ混ぜない。
 */
data class CandidateOption(
    val value: String,
    val label: String,
)

/**
 * 辞書なしで安全に提示できる、ひらがなとカタカナの候補だけを生成する。
 */
object BasicCandidateProvider {
    /** 機密欄では候補を作らず、通常欄では表記変換だけを返す。 */
    fun candidates(reading: String, suppressSuggestions: Boolean): List<CandidateOption> {
        if (reading.isEmpty() || suppressSuggestions) return emptyList()
        val result = mutableListOf(CandidateOption(reading, "かな"))
        val katakana = toKatakana(reading)
        if (katakana != reading) result += CandidateOption(katakana, "カナ")
        return result
    }

    /** ひらがなをカタカナへ写像し、その他の文字はそのままにする。 */
    fun toKatakana(text: String): String {
        return text.map { character ->
            if (character in '\u3041'..'\u3096' || character in '\u309D'..'\u309F') {
                (character.code + 0x60).toChar()
            } else {
                character
            }
        }.joinToString(separator = "")
    }
}
