package dev.uzumi.ime.conversion

import dev.uzumi.ime.dictionary.UserDictionaryEntry
import dev.uzumi.ime.dictionary.UserDictionaryLookup
import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import dev.uzumi.ime.live.LiveConversionCore
import dev.uzumi.ime.live.LiveFieldPolicy
import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.LiveUpdate
import dev.uzumi.ime.live.ProtectedRange
import dev.uzumi.ime.live.RequestIdentity
import dev.uzumi.ime.live.ResultSegment
import dev.uzumi.ime.live.SegmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ライブ変換の範囲分割、Mozc結果の写像、ユーザー辞書の候補合成、学習単位の作成を検証する。 */
class LiveConversionAdapterTest {
    /** 保護範囲と対象範囲内の入力カーソルで区切り、自由な部分範囲だけを変換器へ渡す。 */
    @Test
    fun splitsTargetAtProtectedRangesAndInputCursor() {
        val chunks = mutableListOf<String>()
        val converter = SegmentedLiveConverter(convertRange = { chunk ->
            chunks += chunk
            listOf(ResultSegment(chunk, "[$chunk]"))
        })
        val identity = RequestIdentity(
            sessionEpoch = 1,
            revision = 1,
            reading = "こうえんにいく",
            targetStart = 0,
            targetEnd = 7,
            protectedRanges = listOf(ProtectedRange(4, 5, "二")),
            inputCursor = 6,
            converterGeneration = 0,
        )

        val result = assertNotNullAndGet(converter.convert(LiveRequest(identity, "こうえんにいく", learningAllowed = true)))

        assertEquals(listOf("こうえん", "い", "く"), chunks)
        assertEquals(listOf("こうえん", "に", "い", "く"), result.segments.map { it.reading })
        assertEquals(listOf("[こうえん]", "二", "[い]", "[く]"), result.segments.map { it.surface })
    }

    /** 部分範囲の変換に失敗したら、要求全体の結果を返さない（コアは現在の表示を保つ）。 */
    @Test
    fun failedRangeFailsWholeRequest() {
        val converter = SegmentedLiveConverter(convertRange = { null })
        val identity = RequestIdentity(1, 1, "かな", 0, 2, emptyList(), 2, 0)

        assertNull(converter.convert(LiveRequest(identity, "かな", learningAllowed = true)))
    }

    /**
     * コアの要求をこのアダプターで変換すると、候補の選択後やカーソル移動後も結果が拒否されない。
     * 範囲の境界が必ずsegment境界になるためである。
     */
    @Test
    fun coreAcceptsAdapterResultsAfterChoiceAndCursorMove() {
        val core = LiveConversionCore().apply { startField(LiveFieldPolicy.NORMAL) }
        val converter = SegmentedLiveConverter(convertRange = ::lexiconSegments)
        // コアの操作を行い、要求があれば変換して結果を返す。結果が拒否されないことを確かめる。
        fun run(update: LiveUpdate) {
            val request = update.request ?: return
            val applied = core.onConversionResult(assertNotNullAndGet(converter.convert(request)))
            assertNull(applied.rejection)
        }

        run(core.inputText("こうえんにいく"))
        assertEquals("公園に行く", core.display)
        run(core.focusSegment(core.segments.first().id))
        run(core.selectCandidate(core.candidateBar()!!.choices.first { it.value == "講演" }))
        assertEquals(SegmentState.CHOSEN, core.segments.first().state)
        run(core.moveCursorTo(6))
        run(core.inputText("き"))
        run(core.moveCursorTo(8))

        assertEquals("講演", core.segments.first().surface)
        assertEquals("こうえんにいきく", core.reading)
    }

    /** Mozcの文節の読みが範囲と一致すれば文節ごとに写し、第一候補を候補の先頭に置く。 */
    @Test
    fun mozcSegmentsMapToResultSegments() {
        val mapped = toLiveSegments(
            "きょうは",
            listOf(EngineSegment("きょう", "今日", listOf("京", "今日")), EngineSegment("は", "は", emptyList())),
        )

        assertEquals(
            listOf(ResultSegment("きょう", "今日", listOf("今日", "京")), ResultSegment("は", "は", listOf("は"))),
            mapped,
        )
    }

    /** Mozcが読みを正規化して範囲と一致しない場合は、範囲全体を一segmentとして境界を保つ。 */
    @Test
    fun mismatchedMozcReadingKeepsRangeBoundary() {
        val mapped = toLiveSegments("ａｂ", listOf(EngineSegment("ab", "ab", listOf("ab"))))

        assertEquals(listOf(ResultSegment("ａｂ", "ab", listOf("ab", "ａｂ"))), mapped)
        assertNull(toLiveSegments("か", emptyList()))
    }

    /** 各segmentの読みに一致する登録語を登録順に候補の先頭へ置いて最初の語を表示し、Mozc候補との重複を除く。 */
    @Test
    fun userDictionaryWordsLeadSegmentCandidates() {
        val dictionary = FakeLookup("てんき" to "テンキ辞書", "てんき" to "転機")
        val merged = UserDictionaryCandidates.mergeLive(
            "きょうてんき",
            listOf(
                ResultSegment("きょう", "今日", listOf("今日", "京")),
                ResultSegment("てんき", "天気", listOf("天気", "転機")),
            ),
            dictionary,
        )

        assertEquals(listOf("今日", "京"), merged[0].candidates)
        assertEquals("今日", merged[0].surface)
        assertEquals("テンキ辞書", merged[1].surface)
        assertEquals(listOf("テンキ辞書", "転機", "天気"), merged[1].candidates)
    }

    /** 範囲全体の読みに一致する登録語があれば、範囲を一segmentにまとめてその語を表示する。 */
    @Test
    fun wholeRangeUserDictionaryWordBecomesWholeCandidate() {
        val dictionary = FakeLookup("うずみ" to "Uzumi")
        val merged = UserDictionaryCandidates.mergeLive(
            "うずみ",
            listOf(ResultSegment("う", "鵜", listOf("鵜")), ResultSegment("ずみ", "済み", listOf("済み"))),
            dictionary,
        )

        assertEquals(listOf(ResultSegment("うずみ", "Uzumi", listOf("Uzumi", "鵜済み", "うずみ"))), merged)
    }

    /**
     * 明示変換では、読み全体と先頭文節に一致する登録語を先頭へ置き、エンジンの同じ候補を除く。
     * 読み全体に一致する語があれば、その語を読み全体の一文節として表示する。
     */
    @Test
    fun explicitCandidatesStartWithUserDictionaryWords() {
        val conversion = EngineConversion(
            segments = listOf(ConversionSegment("きょうは", "今日は"), ConversionSegment("いい", "いい")),
            headCandidates = listOf(
                ConversionCandidate(0, "今日は", "きょうは"),
                ConversionCandidate(-1, "キョウハ", "きょうは"),
                ConversionCandidate(3, "京は", "きょうは"),
            ),
        )
        val dictionary = FakeLookup("きょうは" to "京は", "きょうはいい" to "今日はイイ")

        val merged = UserDictionaryCandidates.mergeExplicit("きょうはいい", conversion, dictionary)

        assertEquals(listOf("今日はイイ", "京は", "今日は", "キョウハ"), merged.headCandidates.map { it.value })
        assertEquals(listOf("きょうはいい", "きょうは"), merged.headCandidates.take(2).map { it.reading })
        assertTrue(merged.headCandidates.take(2).all { it.fromUserDictionary })
        assertFalse(merged.headCandidates.drop(2).any { it.fromUserDictionary })
        assertEquals(merged.headCandidates.size, merged.headCandidates.map { it.id }.distinct().size)
        assertEquals(listOf(ConversionSegment("きょうはいい", "今日はイイ", fromUserDictionary = true)), merged.segments)
        assertEquals(conversion, UserDictionaryCandidates.mergeExplicit("きょうはいい", conversion, null))

        // 先頭文節だけに一致する語は候補の先頭に置くが、表示はエンジンの結果のままにする。
        val headOnly = UserDictionaryCandidates.mergeExplicit("きょうはいい", conversion, FakeLookup("きょうは" to "京は"))
        assertEquals(conversion.segments, headOnly.segments)
        assertEquals("京は", headOnly.headCandidates.first().value)
    }

    /** 学習単位は句読点と未変換のsegmentで区切り、ASCIIだけのsegmentは送らない。 */
    @Test
    fun learningUnitsSplitAtPunctuationAndRawSegments() {
        val segments = listOf(
            segment("きょうは", "今日は"),
            segment("いい", "いい"),
            segment("、", "、"),
            segment("てんき", "天気"),
            segment("だ", "だ", converted = false),
            segment("abc", "abc"),
            segment("ね", "ね"),
            segment("。", "。"),
        )

        assertEquals(
            listOf(
                listOf(LearnedSegment("きょうは", "今日は"), LearnedSegment("いい", "いい")),
                listOf(LearnedSegment("てんき", "天気")),
                listOf(LearnedSegment("ね", "ね")),
            ),
            liveLearningUnits(segments),
        )
    }

    /** 試験用の語彙で、部分範囲を最長一致の文節へ分ける。 */
    private fun lexiconSegments(chunk: String): List<ResultSegment> {
        val lexicon = mapOf(
            "こうえん" to listOf("公園", "講演"),
            "に" to listOf("に", "二"),
            "いく" to listOf("行く", "いく"),
        )
        val clusters = GraphemeClusters.split(chunk)
        val result = mutableListOf<ResultSegment>()
        var index = 0
        while (index < clusters.size) {
            val end = (clusters.size downTo index + 1).firstOrNull {
                clusters.subList(index, it).joinToString("") in lexicon
            } ?: (index + 1)
            val reading = clusters.subList(index, end).joinToString("")
            val candidates = lexicon[reading] ?: listOf(reading)
            result += ResultSegment(reading, candidates.first(), candidates)
            index = end
        }
        return result
    }

    /** 学習単位の試験に使う、読みの位置を持たないsegment。 */
    private fun segment(reading: String, surface: String, converted: Boolean = true): LiveSegment {
        return LiveSegment(
            id = 0,
            readingStart = 0,
            readingEnd = 0,
            reading = reading,
            surface = surface,
            state = SegmentState.PROVISIONAL,
            candidates = listOf(surface),
            converted = converted,
            observations = 1,
            lastObservedReadingVersion = 0,
        )
    }

    /** nullでないことを確かめ、その値を返す。 */
    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}

/** 読みと表記の組を登録順に持つだけの、試験用のユーザー辞書。 */
internal class FakeLookup(vararg entries: Pair<String, String>) : UserDictionaryLookup {
    private val entries = entries.map { (reading, surface) -> UserDictionaryEntry(reading, surface) }

    override val generation: Long = 1

    override fun exactMatches(reading: String): List<UserDictionaryEntry> = entries.filter { it.reading == reading }

    override fun prefixMatches(prefix: String, limit: Int): List<UserDictionaryEntry> {
        return entries.filter { it.reading.startsWith(prefix) }.take(limit)
    }
}
