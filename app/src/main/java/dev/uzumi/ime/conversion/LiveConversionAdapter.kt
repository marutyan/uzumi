package dev.uzumi.ime.conversion

import dev.uzumi.ime.dictionary.UserDictionaryLookup
import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import dev.uzumi.ime.live.ConversionResult as LiveResult
import dev.uzumi.ime.live.LiveConversionRules
import dev.uzumi.ime.live.LiveConverter
import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.ResultSegment

/**
 * ライブ変換の要求を、保護範囲と入力カーソルで区切った自由な部分範囲ごとに変換する`LiveConverter`。
 * 各部分範囲を独立に変換するため、保護範囲の境界とカーソル位置は必ずsegment境界になり、
 * コアの照合（CROSSES_PROTECTED、CROSSES_CURSOR）で捨てられない結果だけを返す。
 * 変換そのものはconvertRangeへ任せ、Mozcでも辞書なしのかな・カナ候補でも同じ分割規則を使う。
 */
class SegmentedLiveConverter(
    // 一つの部分範囲の読みを変換する。読みの連結がその範囲に一致するsegment列を返し、失敗時はnull。
    private val convertRange: (String) -> List<ResultSegment>?,
    // 登録語を候補の先頭へ加えるためのユーザー辞書。nullなら加えない。
    private val userDictionary: UserDictionaryLookup? = null,
) : LiveConverter {
    override fun convert(request: LiveRequest): LiveResult? {
        val identity = request.identity
        val clusters = GraphemeClusters.split(identity.reading)
        val segments = mutableListOf<ResultSegment>()
        var position = identity.targetStart
        while (position < identity.targetEnd) {
            val protected = identity.protectedRanges.firstOrNull { it.readingStart == position }
            if (protected != null) {
                // 保護範囲はコアが表記を保持するため、読みと現在の表記をそのまま返す。
                val reading = clusters.subList(protected.readingStart, protected.readingEnd).joinToString("")
                segments += ResultSegment(reading, protected.surface)
                position = protected.readingEnd
                continue
            }
            val chunkEnd = (identity.protectedRanges.map { it.readingStart } + identity.inputCursor)
                .filter { it > position && it < identity.targetEnd }
                .minOrNull() ?: identity.targetEnd
            val chunk = clusters.subList(position, chunkEnd).joinToString("")
            val converted = convertRange(chunk) ?: return null
            segments += UserDictionaryCandidates.mergeLive(chunk, converted, userDictionary)
            position = chunkEnd
        }
        return LiveResult(identity, segments)
    }
}

/**
 * Mozcの文節を、部分範囲の読みに対する`ResultSegment`列へ写す。
 * 読みの連結が範囲と一致しない場合（Mozcが読みを正規化した場合など）は、範囲全体を一segmentとして
 * Mozcの表記を連結して返し、範囲の境界だけは必ず保つ。
 */
fun toLiveSegments(chunk: String, segments: List<EngineSegment>): List<ResultSegment>? {
    if (segments.isEmpty() || segments.any { it.value.isEmpty() }) return null
    if (segments.joinToString(separator = "") { it.reading } == chunk && segments.all { it.reading.isNotEmpty() }) {
        return segments.map { ResultSegment(it.reading, it.value, (listOf(it.value) + it.candidates).distinct()) }
    }
    val joined = segments.joinToString(separator = "") { it.value }
    return listOf(ResultSegment(chunk, joined, listOf(joined, chunk).distinct()))
}

/**
 * 部分範囲がASCII文字だけか。英字・数字・記号だけの範囲はMozcへ送らず入力のまま表示し、
 * QWERTYで打った英単語がライブ変換で全角化などされることを防ぐ。
 */
fun isAsciiOnly(chunk: String): Boolean = chunk.all { it.code < 0x80 }

/**
 * ユーザー辞書の登録語を変換候補へ加える規則を一か所に置く。登録語は候補の先頭に置き、
 * 変換エンジンの候補との重複は除く。登録語の表示は学習ではないため、学習禁止欄でも参照してよい。
 */
object UserDictionaryCandidates {
    /**
     * 明示変換の先頭文節の候補へ登録語を加える。先頭文節の読みに一致する語と、
     * 読み全体に一致する語（確定すると読み全体を置き換える）を先頭に置く。
     */
    fun mergeExplicit(
        reading: String,
        conversion: EngineConversion,
        userDictionary: UserDictionaryLookup?,
    ): EngineConversion {
        if (userDictionary == null) return conversion
        val headReading = conversion.segments.firstOrNull()?.reading ?: return conversion
        val readings = listOf(headReading, reading).distinct()
        val registered = readings.flatMap { target ->
            userDictionary.exactMatches(target).map { it.surface to target }
        }.distinct()
        if (registered.isEmpty()) return conversion
        val user = registered.mapIndexed { index, (surface, target) ->
            ConversionCandidate(
                id = USER_CANDIDATE_ID_BASE + index,
                value = surface,
                reading = target,
                fromUserDictionary = true,
            )
        }
        val engine = conversion.headCandidates.filterNot { candidate ->
            registered.any { it.first == candidate.value && it.second == candidate.reading }
        }
        return conversion.copy(headCandidates = user + engine)
    }

    /**
     * ライブ変換の一部分範囲の結果へ登録語を加える。範囲全体の読みに一致する語があれば、
     * 範囲を一segmentにまとめてその語を候補の先頭へ置く。それ以外は各segmentの読みに一致する語を先頭へ置く。
     * どちらの場合も表示（第一候補）は変換エンジンの結果のままにする。
     */
    fun mergeLive(
        chunk: String,
        segments: List<ResultSegment>,
        userDictionary: UserDictionaryLookup?,
    ): List<ResultSegment> {
        if (userDictionary == null) return segments
        val whole = userDictionary.exactMatches(chunk).map { it.surface }
        if (whole.isNotEmpty() && segments.size > 1) {
            val joined = segments.joinToString(separator = "") { it.surface }
            return listOf(ResultSegment(chunk, joined, (whole + joined + chunk).distinct()))
        }
        return segments.map { segment ->
            val registered = userDictionary.exactMatches(segment.reading).map { it.surface }
            if (registered.isEmpty()) {
                segment
            } else {
                segment.copy(candidates = (registered + segment.candidates).distinct())
            }
        }
    }

    // 登録語の候補ID。Mozcは負のIDも使うため、Mozcが使わない範囲（Intの最小値付近）から割り当てる。
    private const val USER_CANDIDATE_ID_BASE = Int.MIN_VALUE
}

/**
 * ライブ変換で確定したsegment列から、エンジンへ学習させる単位を作る。
 * 句読点と未変換のsegmentで区切り、続いた変換済みsegmentを一単位にする。一単位は一回の変換として
 * 区切りと表記を再現してから確定するため、文脈の続きをまとめて学習できる。
 */
fun liveLearningUnits(segments: List<LiveSegment>): List<List<LearnedSegment>> {
    val units = mutableListOf<List<LearnedSegment>>()
    var current = mutableListOf<LearnedSegment>()
    for (segment in segments) {
        val learnable = segment.converted &&
            segment.surface.isNotEmpty() &&
            segment.reading !in LiveConversionRules.TERMINAL_PUNCTUATION &&
            segment.reading != LiveConversionRules.SOFT_BOUNDARY &&
            !isAsciiOnly(segment.reading)
        if (learnable) {
            current += LearnedSegment(segment.reading, segment.surface)
        } else if (current.isNotEmpty()) {
            units += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) units += current
    return units
}
