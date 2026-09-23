package dev.uzumi.ime.editor

import dev.uzumi.ime.conversion.ConversionRequest

/**
 * 候補行へ表示する最小候補を表す。変換エンジンの候補は、表示元の要求と候補IDをconversionChoiceに持つ。
 */
data class CandidateOption(
    val value: String,
    val label: String,
    val conversionChoice: ConversionChoice? = null,
)

/**
 * 変換エンジンの候補を選ぶ操作の識別子。タップ時に表示元の要求が現在の変換と一致するかを照合する。
 */
data class ConversionChoice(
    val request: ConversionRequest,
    val candidateId: Int,
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
