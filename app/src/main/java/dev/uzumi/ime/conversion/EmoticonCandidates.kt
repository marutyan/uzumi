package dev.uzumi.ime.conversion

import dev.uzumi.ime.dictionary.UserDictionaryCategory
import dev.uzumi.ime.dictionary.UserDictionaryLookup
import dev.uzumi.ime.learning.LearningStore
import dev.uzumi.ime.live.ResultSegment

/**
 * 表記の文字種だけで、顔文字・絵文字かを判定する規則。変換候補の並びとライブ変換の表示値の両方がこの判定を使う。
 * 絵文字は絵文字の符号位置を一つでも含むもの、顔文字はかな・漢字・英数字が少なく、顔の部品になる記号を含む記号列とする。
 * 「・」「〜」「！」「★」のような単独の記号や、「・・・」「！？」のような同じ種類の記号の並びは通常の記号として扱う。
 */
object EmoticonText {
    /** 顔文字とみなす最小の長さ（空白を除くコードポイント数）。2文字以下の記号の組（「！？」「^^」など）を除く。 */
    private const val MIN_KAOMOJI_LENGTH = 3

    /** 顔文字に含まれる記号（かな・漢字・英数字以外）の最小の数。「（笑）」のような括弧書きの語を除く。 */
    private const val MIN_KAOMOJI_SYMBOLS = 3

    /**
     * 顔文字の目・口・輪郭によく使い、通常の記号の並びにはほとんど現れない文字。
     * 「→←」「・・・」のような記号の並びを顔文字と誤らないよう、このいずれかを含むことを顔文字の条件にする。
     */
    private const val FACE_PARTS = "()（）^＾_＿´｀`;；ω∀ДдεΦ≧≦ﾟ゜°∇∩⊂⊃"

    /**
     * 既定で絵文字として表示される基本多言語面の文字（Unicodeの性質Emoji_Presentation=Yes）。
     * 「★」「♥」など、既定で文字として表示される記号は含めない。
     */
    private val BMP_EMOJI_PRESENTATION: List<IntRange> = listOf(
        0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE,
        0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1,
        0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4,
        0x26EA..0x26EA, 0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD,
        0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C, 0x274E..0x274E,
        0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF,
        0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
    )

    /** 表記が顔文字または絵文字か。 */
    fun isEmoticon(surface: String): Boolean = containsEmoji(surface) || isKaomoji(surface)

    /**
     * 絵文字の符号位置を含むか。U+1F000〜U+1FAFFの絵文字の領域（国旗の地域指示子と肌色の修飾子を含む）、
     * 既定で絵文字として表示される基本多言語面の文字、絵文字の結合に使うZWJ（U+200D）・異体字セレクタ16（U+FE0F）・
     * keycap（U+20E3）・タグ文字（U+E0020〜U+E007F）のいずれかを含めば絵文字とする。
     */
    private fun containsEmoji(surface: String): Boolean = surface.codePoints().anyMatch { codePoint ->
        codePoint in 0x1F000..0x1FAFF ||
            codePoint == 0x200D ||
            codePoint == 0xFE0F ||
            codePoint == 0x20E3 ||
            codePoint in 0xE0020..0xE007F ||
            BMP_EMOJI_PRESENTATION.any { codePoint in it }
    }

    /**
     * 顔文字か。空白を除いて3文字以上、2種類以上の文字からなり、顔の部品の記号を含み、
     * 記号が3文字以上で、かな・漢字・英数字（文字）の数が記号の数より少ないものを顔文字とする。
     * 「(^o^)」「ヽ(´ー｀)ノ」のように少しの文字を含む顔文字は拾い、「（笑）」「【速報】」は通常の語として残す。
     */
    private fun isKaomoji(surface: String): Boolean {
        val codePoints = surface.codePoints().filter { !Character.isWhitespace(it) }.toArray()
        if (codePoints.size < MIN_KAOMOJI_LENGTH || codePoints.distinct().size < 2) return false
        if (codePoints.none { FACE_PARTS.contains(String(Character.toChars(it))) }) return false
        val letters = codePoints.count(::isWordCharacter)
        val symbols = codePoints.size - letters
        return symbols >= MIN_KAOMOJI_SYMBOLS && letters < symbols
    }

    /**
     * 語を作る文字（ひらがな、カタカナ、半角カタカナ、漢字、英数字）か。踊り字「ゝゞヽヾ」・長音符「ー」・中黒「・」・
     * 半角の濁点と半濁点は顔文字の部品にも使うため記号として数え、ギリシャ文字やキリル文字も顔文字の部品として記号に数える。
     */
    private fun isWordCharacter(codePoint: Int): Boolean = when (codePoint) {
        in 0x3041..0x3096 -> true
        in 0x30A1..0x30FA -> true
        in 0xFF66..0xFF9D -> true
        in '0'.code..'9'.code, in 'A'.code..'Z'.code, in 'a'.code..'z'.code -> true
        in 0xFF10..0xFF19, in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> true
        else -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
    }
}

/**
 * 候補ごとに、顔文字・絵文字か、繰り返し選ばれて順位を上げてよいかを答える。
 * 表記の文字種に加え、ユーザー辞書に品詞「顔文字」で登録した語も顔文字として扱う。
 * 昇格は学習キャッシュの使用回数で決め、学習禁止欄ではstoreにnullを渡して昇格させない。
 */
class EmoticonJudge(
    private val userDictionary: UserDictionaryLookup?,
    private val learningStore: LearningStore?,
) {
    /** 読みと表記の組が顔文字・絵文字か。 */
    fun isEmoticon(reading: String, surface: String): Boolean =
        EmoticonText.isEmoticon(surface) ||
            userDictionary?.exactMatches(reading)?.any {
                it.surface == surface && it.category == UserDictionaryCategory.EMOTICON
            } == true

    /** 読みと表記の組を、昇格に必要な回数以上確定したか。 */
    fun isPromoted(reading: String, surface: String): Boolean =
        learningStore?.exactMatches(reading, limit = Int.MAX_VALUE)
            ?.any { it.surface == surface && it.useCount >= EmoticonCandidates.PROMOTION_USE_COUNT } == true
}

/**
 * 顔文字・絵文字の候補の順位と、ライブ変換の表示値の規則。
 * 顔文字・絵文字は候補の先頭（index 0）へは決して置かない。昇格していないものは6番目（index 5）以降へ、
 * 昇格したものも2番目（index 1）より前へは出さない。ライブ変換の表示値には使わない。
 */
object EmoticonCandidates {
    /** 昇格に必要な確定回数。学習キャッシュの回数を使い、専用の保存は持たない。 */
    const val PROMOTION_USE_COUNT = 2

    /** 昇格していない顔文字・絵文字を置ける最小の位置（6番目）。 */
    const val UNPROMOTED_MIN_INDEX = 5

    /** 昇格した顔文字・絵文字を置ける最小の位置（2番目）。 */
    const val PROMOTED_MIN_INDEX = 1

    /**
     * 候補を並べ直す。顔文字・絵文字以外の並びは保ち、顔文字・絵文字は元の位置と最小の位置の遅いほうへ、
     * 元の順のまま差し込む。候補が足りなければ末尾へ置く。
     */
    fun <T> rank(items: List<T>, isEmoticon: (T) -> Boolean, isPromoted: (T) -> Boolean): List<T> {
        val result = items.filterNot(isEmoticon).toMutableList()
        // 差し込む位置の昇順に並べる。同じ位置では元の順を保つため、直前に差し込んだ位置より前へは置かない。
        val emoticons = items.withIndex()
            .filter { isEmoticon(it.value) }
            .map { (index, item) -> maxOf(index, if (isPromoted(item)) PROMOTED_MIN_INDEX else UNPROMOTED_MIN_INDEX) to item }
            .sortedBy { it.first }
        var next = 0
        for ((target, item) in emoticons) {
            val position = minOf(maxOf(target, next), result.size)
            result.add(position, item)
            next = position + 1
        }
        return result
    }

    /**
     * ライブ変換の一segmentの候補を並べ直し、表示値が顔文字・絵文字なら顔文字・絵文字でない最初の候補へ替える。
     * 候補がすべて顔文字・絵文字なら、読みを表示値と先頭の候補にする（読みのまま表示する）。
     */
    fun arrangeLive(segment: ResultSegment, judge: EmoticonJudge): ResultSegment {
        val isEmoticon = { surface: String -> judge.isEmoticon(segment.reading, surface) }
        val base = if (segment.surface in segment.candidates) segment.candidates else listOf(segment.surface) + segment.candidates
        if (base.none(isEmoticon)) return segment
        val withPlain = if (base.all(isEmoticon)) listOf(segment.reading) + base else base
        val ranked = rank(withPlain.distinct(), isEmoticon) { judge.isPromoted(segment.reading, it) }
        val surface = if (isEmoticon(segment.surface)) {
            ranked.firstOrNull { !isEmoticon(it) } ?: segment.reading
        } else {
            segment.surface
        }
        return segment.copy(surface = surface, candidates = ranked)
    }

    /**
     * 明示変換の先頭文節の候補を並べ直し、表示する文節の値が顔文字・絵文字なら替える。
     * 先頭文節は同じ読みを確定する顔文字・絵文字でない最初の候補へ、それ以外の文節と候補が無い場合は読みへ替える。
     * 差し替えた文節はエンジンの選択と表記が違うため、登録語と同じく全文確定をエンジンへ学習させない印を付ける。
     */
    fun arrangeExplicit(conversion: EngineConversion, judge: EmoticonJudge): EngineConversion {
        val isEmoticon = { candidate: ConversionCandidate -> judge.isEmoticon(candidate.reading, candidate.value) }
        val ranked = rank(conversion.headCandidates, isEmoticon) { judge.isPromoted(it.learnedReading ?: it.reading, it.value) }
        val segments = conversion.segments.mapIndexed { index, segment ->
            if (!judge.isEmoticon(segment.reading, segment.value)) return@mapIndexed segment
            val replacement = ranked.takeIf { index == 0 }
                ?.firstOrNull { it.reading == segment.reading && !isEmoticon(it) }
                ?.value
                ?: segment.reading
            segment.copy(value = replacement, fromUserDictionary = true)
        }
        return EngineConversion(segments = segments, headCandidates = ranked)
    }
}
