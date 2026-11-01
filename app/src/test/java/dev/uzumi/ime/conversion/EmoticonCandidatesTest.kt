package dev.uzumi.ime.conversion

import dev.uzumi.ime.dictionary.UserDictionaryCategory
import dev.uzumi.ime.dictionary.UserDictionaryEntry
import dev.uzumi.ime.live.ResultSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 顔文字・絵文字の判定の境界と、候補の並べ直しの規則を検証する。 */
class EmoticonCandidatesTest {
    /** 絵文字の符号位置（ZWJ列、異体字セレクタ16、肌色の修飾子、国旗、keycap）を含む表記は絵文字とする。 */
    @Test
    fun emojiSequencesAreEmoticons() {
        val emoji = listOf(
            "😂",
            "👨‍👩‍👧",
            "❤️",
            "👍🏽",
            "🇯🇵",
            "1️⃣",
            "⭐",
            "✨",
            "笑😂",
        )
        for (surface in emoji) assertTrue(surface, EmoticonText.isEmoticon(surface))
    }

    /** 記号と少しの文字からなる顔文字は顔文字とする。 */
    @Test
    fun kaomojiAreEmoticons() {
        val kaomoji = listOf("(^_^)", "(^o^)", "(*^3^)", "m(_ _)m", "ヽ(´ー｀)ノ", "(´・ω・｀)", "（＾＾）", "_(:3」∠)_", "^_^", "(゜ロ゜)")
        for (surface in kaomoji) assertTrue(surface, EmoticonText.isEmoticon(surface))
    }

    /** 単独の記号、同じ種類の記号の並び、文字を表す記号、かな・漢字の語、括弧書きの語は通常の語とする。 */
    @Test
    fun ordinarySymbolsAndWordsAreNotEmoticons() {
        val ordinary = listOf(
            "・", "〜", "！", "？", "★", "☆", "♥", "❤", "♪", "→", "※", "…", "（", "）", "「」", "【】",
            "・・・", "！？", "!?!?", "〜〜〜", "★☆★", "^^", "___",
            "（笑）", "(笑)", "【速報】", "「はい」",
            "漢字", "かんじ", "カタカナ", "ｶﾀｶﾅ", "Uzumi", "１２３", "10時",
        )
        for (surface in ordinary) assertFalse(surface, EmoticonText.isEmoticon(surface))
    }

    /** ユーザー辞書の品詞「顔文字」の語は、文字種で判定できなくても顔文字とする。他の品詞では文字種だけで判定する。 */
    @Test
    fun userDictionaryEmoticonCategoryIsEmoticon() {
        val dictionary = FakeLookup(
            listOf(
                UserDictionaryEntry("わら", "（笑）", UserDictionaryCategory.EMOTICON),
                UserDictionaryEntry("かっこ", "（笑）", UserDictionaryCategory.NOUN),
            ),
        )
        val judge = EmoticonJudge(dictionary, learningStore = null)

        assertTrue(judge.isEmoticon("わら", "（笑）"))
        assertFalse(judge.isEmoticon("かっこ", "（笑）"))
        assertTrue(judge.isEmoticon("かっこ", "😂"))
    }

    /** 昇格していない顔文字・絵文字は6番目以降へ、昇格したものは2番目以降へ置き、先頭には置かない。足りなければ末尾へ置く。 */
    @Test
    fun rankKeepsEmoticonsAwayFromHead() {
        val items = listOf("😂", "a", "b", "c", "d", "e", "f")
        assertEquals(listOf("a", "b", "c", "d", "e", "😂", "f"), rank(items, promoted = emptySet()))
        assertEquals(listOf("a", "😂", "b", "c", "d", "e", "f"), rank(items, promoted = setOf("😂")))
        assertEquals(listOf("a", "b", "😂"), rank(listOf("😂", "a", "b"), promoted = emptySet()))
        // 元の位置が最小の位置より後ろなら、元の位置のまま残す。
        assertEquals(listOf("a", "b", "c", "😂"), rank(listOf("a", "b", "c", "😂"), promoted = setOf("😂")))
        // 同じ位置へ差し込む顔文字・絵文字は元の順を保つ。
        assertEquals(listOf("a", "😂", "(^_^)", "b"), rank(listOf("😂", "(^_^)", "a", "b"), promoted = setOf("😂", "(^_^)")))
    }

    /** ライブ変換のsegmentでは表示値を顔文字・絵文字以外にし、すべて顔文字・絵文字なら読みのまま表示する。 */
    @Test
    fun arrangeLiveNeverShowsEmoticon() {
        val judge = EmoticonJudge(userDictionary = null, learningStore = null)

        val mixed = EmoticonCandidates.arrangeLive(ResultSegment("わらい", "😂", listOf("😂", "笑い", "わらい")), judge)
        assertEquals(ResultSegment("わらい", "笑い", listOf("笑い", "わらい", "😂")), mixed)

        val onlyEmoji = EmoticonCandidates.arrangeLive(ResultSegment("にこ", "😊", listOf("😊", "☺️")), judge)
        assertEquals(ResultSegment("にこ", "にこ", listOf("にこ", "😊", "☺️")), onlyEmoji)

        val plain = ResultSegment("かんじ", "漢字", listOf("漢字", "感じ"))
        assertEquals(plain, EmoticonCandidates.arrangeLive(plain, judge))
    }

    /** 文字列の候補を、顔文字・絵文字の判定とpromotedの集合で並べ直す。 */
    private fun rank(items: List<String>, promoted: Set<String>): List<String> =
        EmoticonCandidates.rank(items, EmoticonText::isEmoticon) { it in promoted }
}
