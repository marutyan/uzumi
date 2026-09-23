package dev.uzumi.ime.conversion

import dev.uzumi.ime.dictionary.UserDictionaryLookup
import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.learning.LearningStore
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import dev.uzumi.ime.live.ConversionResult as LiveResult
import dev.uzumi.ime.live.LiveConversionRules
import dev.uzumi.ime.live.LiveConverter
import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.ResultSegment
import dev.uzumi.ime.live.SegmentState

/**
 * ライブ変換の要求を、保護範囲と入力カーソルで区切った自由な部分範囲ごとに変換する`LiveConverter`。
 * 各部分範囲を独立に変換するため、保護範囲の境界とカーソル位置は必ずsegment境界になり、
 * コアの照合（CROSSES_PROTECTED、CROSSES_CURSOR）で捨てられない結果だけを返す。
 * 変換そのものはconvertRangeへ任せ、Mozcでも辞書なしのかな・カナ候補でも同じ分割規則を使う。
 * 部分範囲の先頭からの読みが学習した句に一致する場合は、句の終わりでも区切り、句と残りを別々に変換する。
 * 句は部分範囲の中だけで探すため、保護範囲とカーソルの境界を越える句は使われない。
 * ユーザーが伸縮で区切りを決めた範囲（fixedRanges）は、それだけを一つの部分範囲として一segmentに変換する。
 */
class SegmentedLiveConverter(
    // 一つの部分範囲の読みを変換する。読みの連結がその範囲に一致するsegment列を返し、失敗時はnull。
    private val convertRange: (String) -> List<ResultSegment>?,
    // 登録語を候補の先頭へ加えるためのユーザー辞書。nullなら加えない。
    private val userDictionary: UserDictionaryLookup? = null,
    // 読みに完全一致する学習語の表記を、優先する順に返す。nullなら学習語を加えない（学習禁止欄・学習OFF）。
    private val learnedSurfaces: ((String) -> List<String>)? = null,
    // 読みに完全一致する学習した句の表記を、優先する順に返す。nullなら句で区切りを合わせない。
    private val learnedPhrases: ((String) -> List<String>)? = null,
    // ユーザーが区切りを決めた読みを必ず一segmentとして変換する。nullまたは失敗時はconvertRangeの結果を一segmentにまとめる。
    private val convertFixed: ((String) -> ResultSegment?)? = null,
) : LiveConverter {
    // 読みに完全一致する学習語と句の表記を合わせて引く。どちらも無ければnullで、学習語を合成しない。
    private val exactSurfaces: ((String) -> List<String>)? = combineExactSurfaces(learnedSurfaces, learnedPhrases)

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
            val fixed = identity.fixedRanges.firstOrNull { it.readingStart == position }
            if (fixed != null) {
                val reading = clusters.subList(fixed.readingStart, fixed.readingEnd).joinToString("")
                segments += convertFixedMerged(reading) ?: return null
                position = fixed.readingEnd
                continue
            }
            val chunkEnd = (
                identity.protectedRanges.map { it.readingStart } +
                    identity.fixedRanges.map { it.readingStart } +
                    identity.inputCursor
                )
                .filter { it > position && it < identity.targetEnd }
                .minOrNull() ?: identity.targetEnd
            val chunkClusters = clusters.subList(position, chunkEnd)
            val chunk = chunkClusters.joinToString("")
            val phraseEnd = headPhraseEnd(chunk, chunkClusters)
            if (phraseEnd == null) {
                segments += convertMerged(chunk) ?: return null
            } else {
                // 句の読みを一つの範囲として変換すると、範囲全体に一致する句の表記の一segmentにまとまる。残りは別に変換する。
                segments += convertMerged(chunkClusters.subList(0, phraseEnd).joinToString("")) ?: return null
                segments += convertMerged(chunkClusters.subList(phraseEnd, chunkClusters.size).joinToString("")) ?: return null
            }
            position = chunkEnd
        }
        return LiveResult(identity, segments)
    }

    /** 一つの読みを変換し、「ユーザー辞書 → 学習 → エンジン」の順に候補を合成する。学習語を先に合成し、その上へ登録語を置く。 */
    private fun convertMerged(reading: String): List<ResultSegment>? {
        val converted = convertRange(reading) ?: return null
        val learned = exactSurfaces?.let { mergeExactSurfaces(reading, converted, it) } ?: converted
        return UserDictionaryCandidates.mergeLive(reading, learned, userDictionary)
    }

    /**
     * ユーザーが区切りを決めた読みを一segmentとして変換し、convertMergedと同じ順で登録語と学習語を合成する。
     * 読み全体が一segmentなので、合成しても一segmentのまま残る。
     */
    private fun convertFixedMerged(reading: String): ResultSegment? {
        val single = convertFixed?.invoke(reading)?.takeIf { it.reading == reading }
            ?: convertRange(reading)?.let { joinIntoOne(reading, it) }
            ?: return null
        val learned = exactSurfaces?.let { mergeExactSurfaces(reading, listOf(single), it) } ?: listOf(single)
        val merged = UserDictionaryCandidates.mergeLive(reading, learned, userDictionary).single()
        return merged.copy(candidatesComplete = single.candidatesComplete)
    }

    /**
     * 部分範囲の先頭から始まり、範囲より短い学習した句のうち最も長いものの終わり（範囲内の書記素位置）を返す。
     * 範囲全体の読みに登録語か学習語があれば、範囲を一segmentにまとめる既存の規則を優先するため句を探さない。
     */
    private fun headPhraseEnd(chunk: String, chunkClusters: List<String>): Int? {
        val phrases = learnedPhrases ?: return null
        if (userDictionary?.exactMatches(chunk)?.isNotEmpty() == true) return null
        if (exactSurfaces?.invoke(chunk)?.isNotEmpty() == true) return null
        return (chunkClusters.size - 1 downTo 1).firstOrNull { end ->
            phrases(chunkClusters.subList(0, end).joinToString("")).isNotEmpty()
        }
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
 * 一つの読みに対するsegment列を、読み全体の一segmentへまとめる。一segmentならそのまま返し、
 * 複数なら表記を連結したものを表示と第一候補にし、読みそのものを候補に加える。
 */
fun joinIntoOne(reading: String, segments: List<ResultSegment>): ResultSegment? {
    if (segments.isEmpty() || segments.any { it.surface.isEmpty() }) return null
    segments.singleOrNull()?.takeIf { it.reading == reading }?.let { return it }
    val joined = segments.joinToString(separator = "") { it.surface }
    return ResultSegment(reading, joined, listOf(joined, reading).distinct())
}

/**
 * 読みに完全一致する学習語と学習した句の表記を、学習語を先にして合わせて引く関数を作る。
 * ライブ変換の部分範囲と、注目したsegmentの候補の取り直しで同じ規則を使うために一か所へ置く。どちらも無ければnull。
 */
fun combineExactSurfaces(
    learnedSurfaces: ((String) -> List<String>)?,
    learnedPhrases: ((String) -> List<String>)?,
): ((String) -> List<String>)? {
    if (learnedSurfaces == null && learnedPhrases == null) return null
    return { reading -> (learnedSurfaces?.invoke(reading).orEmpty() + learnedPhrases?.invoke(reading).orEmpty()).distinct() }
}

/**
 * 注目したsegmentに取り直した候補へ、ライブ変換の部分範囲と同じ順（ユーザー辞書 → 学習 → エンジン）で
 * 登録語と学習語を合成し、候補の表記だけを返す。exactSurfacesは読みに完全一致する学習語と句の表記を返す。
 */
fun mergeSegmentCandidates(
    reading: String,
    engineCandidates: List<String>,
    userDictionary: UserDictionaryLookup?,
    exactSurfaces: ((String) -> List<String>)?,
): List<String> {
    if (engineCandidates.isEmpty()) return emptyList()
    val base = listOf(ResultSegment(reading, engineCandidates.first(), engineCandidates))
    val learned = exactSurfaces?.let { mergeExactSurfaces(reading, base, it) } ?: base
    return UserDictionaryCandidates.mergeLive(reading, learned, userDictionary).single().candidates
}

/**
 * 部分範囲がASCII文字だけか。英字・数字・記号だけの範囲はMozcへ送らず入力のまま表示し、
 * QWERTYで打った英単語がライブ変換で全角化などされることを防ぐ。
 */
fun isAsciiOnly(chunk: String): Boolean = chunk.all { it.code < 0x80 }

/**
 * ユーザー辞書の登録語を変換候補へ加える規則を一か所に置く。登録語は登録順に候補の先頭へ置き、
 * 変換エンジンの候補との重複は除く。読みが完全に一致する登録語は、最初に表示する候補にもする。
 * 登録語の表示は学習ではないため、学習禁止欄でも参照してよい。
 */
object UserDictionaryCandidates {
    /**
     * 明示変換の先頭文節の候補へ登録語を加える。読み全体に一致する語（確定すると読み全体を置き換える）と、
     * 先頭文節の読みに一致する語を先頭に置く。読み全体に一致する語があれば、その最初の語を読み全体の
     * 一文節として表示する。
     */
    fun mergeExplicit(
        reading: String,
        conversion: EngineConversion,
        userDictionary: UserDictionaryLookup?,
    ): EngineConversion {
        if (userDictionary == null) return conversion
        val headReading = conversion.segments.firstOrNull()?.reading ?: return conversion
        val readings = listOf(reading, headReading).distinct()
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
        val whole = user.firstOrNull { it.reading == reading }
        val segments = if (whole != null) {
            listOf(ConversionSegment(reading, whole.value, fromUserDictionary = true))
        } else {
            conversion.segments
        }
        return EngineConversion(segments = segments, headCandidates = user + engine)
    }

    /**
     * ライブ変換の一部分範囲の結果へ登録語を加える。範囲全体の読みに一致する語があれば、
     * 範囲を一segmentにまとめる。それ以外は各segmentの読みに一致する語を加える。
     * どちらの場合も、最初の登録語を表示し、登録語の後に変換エンジンの候補を続ける。
     */
    fun mergeLive(
        chunk: String,
        segments: List<ResultSegment>,
        userDictionary: UserDictionaryLookup?,
    ): List<ResultSegment> {
        if (userDictionary == null) return segments
        return mergeExactSurfaces(chunk, segments) { reading -> userDictionary.exactMatches(reading).map { it.surface } }
    }

    // 登録語の候補ID。Mozcは負のIDも使うため、Mozcが使わない範囲（Intの最小値付近）から割り当てる。
    private const val USER_CANDIDATE_ID_BASE = Int.MIN_VALUE
}

/**
 * ライブ変換の一部分範囲の結果へ、読みが完全一致する語（ユーザー辞書の登録語、学習語）を合成する共通の規則。
 * 範囲全体の読みに一致する語があれば範囲を一segmentにまとめ、それ以外は各segmentの読みに一致する語を加える。
 * どちらの場合も、surfacesForが最初に返した語を表示し、その後に元の候補を続ける。
 */
fun mergeExactSurfaces(
    chunk: String,
    segments: List<ResultSegment>,
    surfacesFor: (String) -> List<String>,
): List<ResultSegment> {
    val whole = surfacesFor(chunk)
    if (whole.isNotEmpty()) {
        val joined = segments.joinToString(separator = "") { it.surface }
        val engineCandidates = if (segments.size == 1) segments.single().candidates else listOf(joined)
        return listOf(ResultSegment(chunk, whole.first(), (whole + engineCandidates + chunk).distinct()))
    }
    return segments.map { segment ->
        val matched = surfacesFor(segment.reading)
        if (matched.isEmpty()) {
            segment
        } else {
            segment.copy(surface = matched.first(), candidates = (matched + segment.candidates).distinct())
        }
    }
}

/**
 * 明示変換の先頭文節の候補へ、IME側の学習語を合成する。表示（第一候補）は変えず、候補の並びだけを変える。
 * 読み全体と先頭文節の読みに完全一致する学習語をエンジンの候補の前へ、前方一致する学習語（予測）を最後へ置く。
 * 学習語がエンジンの候補と同じ表記・読みなら、エンジンの候補を前へ移し、確定をエンジンへも学習させる。
 */
object LearnedCandidates {
    /** 変換結果の先頭文節の候補へ学習語を合成した結果を返す。storeがnullなら元の結果をそのまま返す。 */
    fun mergeExplicit(reading: String, conversion: EngineConversion, store: LearningStore?): EngineConversion {
        if (store == null) return conversion
        val headReading = conversion.segments.firstOrNull()?.reading ?: return conversion
        val exact = listOf(reading, headReading).distinct().flatMap { target ->
            store.exactMatches(target).map { it.surface to target }
        }.distinct()
        val predicted = store.prefixMatches(reading)
        if (exact.isEmpty() && predicted.isEmpty()) return conversion
        var nextId = LEARNED_CANDIDATE_ID_BASE
        val promoted = exact.map { (surface, target) ->
            conversion.headCandidates.firstOrNull { it.value == surface && it.reading == target }
                ?: ConversionCandidate(id = nextId++, value = surface, reading = target, learnedReading = target)
        }
        val engine = conversion.headCandidates.filterNot { candidate -> promoted.any { it.id == candidate.id } }
        val shown = (promoted + engine).map { it.value }.toSet()
        // 予測は入力済みの読み全体を置き換える候補にする。確定時は学習語の本来の読みで記録し直す。
        val predictions = predicted.filter { it.surface !in shown }.map { word ->
            ConversionCandidate(id = nextId++, value = word.surface, reading = reading, learnedReading = word.reading)
        }
        return conversion.copy(headCandidates = promoted + engine + predictions)
    }

    // 学習語の候補ID。登録語（Intの最小値から数件）ともMozcの候補IDとも重ならない範囲から割り当てる。
    private const val LEARNED_CANDIDATE_ID_BASE = Int.MIN_VALUE + 0x10000
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
            current += LearnedSegment(segment.reading, segment.surface, chosen = segment.state == SegmentState.CHOSEN)
        } else if (current.isNotEmpty()) {
            units += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) units += current
    return units
}
