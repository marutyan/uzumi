package dev.uzumi.ime.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外部辞書なしで提示するかな・カナ候補の範囲を検証する。 */
class BasicCandidateProviderTest {
    /** 読みとカタカナだけを順に返す。 */
    @Test
    fun returnsReadingAndKatakanaOnly() {
        val candidates = BasicCandidateProvider.candidates("かなゞ", suppressSuggestions = false)
        assertEquals(listOf("かなゞ", "カナヾ"), candidates.map(CandidateOption::value))
    }

    /** 抑止方針では候補本文を生成しない。 */
    @Test
    fun suppressesAllCandidatesWhenRequested() {
        assertTrue(BasicCandidateProvider.candidates("てすと", suppressSuggestions = true).isEmpty())
    }
}
