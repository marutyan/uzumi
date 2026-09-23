package dev.uzumi.ime.conversion

import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.live.LiveConversionCore
import dev.uzumi.ime.live.LiveFieldPolicy
import dev.uzumi.ime.live.LiveUpdate
import dev.uzumi.ime.live.ResultSegment
import dev.uzumi.ime.live.SegmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** ニューラルで直接変換し辞書で補う部分範囲の変換器を、fakeのモデルと辞書で検証する。 */
class NeuralRangeConverterTest {
    /** モデルの表記を辞書の文節へ切り分け、各文節の候補の先頭にモデルの表記を置く。 */
    @Test
    fun splitsNeuralSurfaceAtDictionarySegments() {
        val converter = NeuralRangeConverter(model = fixedModel("きょうはいいてんき" to "今日はいい天気"), dictionary = ::lexiconSegments)

        val segments = converter.convert("きょうはいいてんき")

        assertEquals(listOf("きょう", "は", "いい", "てんき"), segments.map { it.reading })
        assertEquals(listOf("今日", "は", "いい", "天気"), segments.map { it.surface })
        assertEquals(listOf("いい", "良い"), segments[2].candidates)
        assertEquals(listOf("天気", "転機"), segments[3].candidates)
    }

    /** 数字と英字はモデルへ渡さず入力のまま表示し、後ろのかなへ左文脈として渡す。カタカナの出力はひらがなの読みに対応づく。 */
    @Test
    fun digitsAndLettersBypassModelAndBecomeLeftContext() {
        val calls = mutableListOf<Pair<String, String>>()
        val model = NeuralKanaKanjiModel { reading, context ->
            calls += reading to context
            output(mapOf("ねんです" to "年です", "のてすと" to "のテスト")[reading])
        }
        val converter = NeuralRangeConverter(model, ::lexiconSegments)

        val year = converter.convert("2026ねんです")
        val test = converter.convert("abcのてすと")

        assertEquals(listOf("ねんです" to "2026", "のてすと" to "abc"), calls)
        assertEquals(listOf(ResultSegment("2026", "2026")), year.take(1))
        assertEquals(listOf("2026", "年", "です"), year.map { it.surface })
        assertEquals(listOf("abc", "の", "テスト"), test.map { it.surface })
    }

    /** 読みとかなの並びが合わない出力、区切り記号や空の出力、モデルの失敗では、辞書の結果を表示する。 */
    @Test
    fun unusableOutputsFallBackToDictionary() {
        val reading = "でんしゃがおくれる"
        val expected = lexiconSegments(reading)

        for (output in listOf("電車(でんしゃ)なおくれる", "電車が遅", "", null)) {
            val converter = NeuralRangeConverter(model = { _, _ -> output(output) }, dictionary = ::lexiconSegments)
            assertEquals("出力: $output", expected, converter.convert(reading))
        }
    }

    /** 辞書が使えない場合、モデルの表記は一segmentで表示し、モデルも使えなければ読みのまま表示する。 */
    @Test
    fun withoutDictionaryShowsNeuralOrReading() {
        val neuralOnly = NeuralRangeConverter(model = fixedModel("てんき" to "天気"), dictionary = { null })
        val neither = NeuralRangeConverter(model = { _, _ -> output(null) }, dictionary = { null })

        assertEquals(listOf(ResultSegment("てんき", "天気", listOf("天気", "てんき"))), neuralOnly.convert("てんき"))
        assertEquals(listOf(ResultSegment("てんき", "てんき")), neither.convert("てんき"))
    }

    /** 漢字や数字の連なりの内部にある辞書の文節境界では、両側の文節をつなぐ。 */
    @Test
    fun joinsDictionarySegmentsInsideOneNonKanaRun() {
        val converter = NeuralRangeConverter(model = fixedModel("じゅうじから" to "10時から"), dictionary = ::lexiconSegments)

        val segments = converter.convert("じゅうじから")

        assertEquals(
            listOf(
                ResultSegment("じゅうじ", "10時", listOf("10時", "十時")),
                ResultSegment("から", "から", listOf("から", "空")),
            ),
            segments,
        )
    }

    /**
     * かなの読みから生まれた数字の表記は、同じ読みに重なる辞書の文節の候補にある場合だけ使う。
     * 辞書にない数字（「じゅうじ」から「11時」、「にせんにじゅうろくねん」から「1999年」）は辞書の結果へ戻し、
     * 辞書が使えなければ数字を含む出力は確かめられないため読みのまま表示する。
     */
    @Test
    fun digitsGeneratedFromKanaNeedDictionaryCandidate() {
        val withDictionary = { text: String -> NeuralRangeConverter(model = { _, _ -> output(text) }, dictionary = ::lexiconSegments) }
        val withoutDictionary = { text: String -> NeuralRangeConverter(model = { _, _ -> output(text) }, dictionary = { null }) }

        assertEquals(lexiconSegments("じゅうじ"), withDictionary("11時").convert("じゅうじ"))
        assertEquals(lexiconSegments("にせんにじゅうろくねん"), withDictionary("1999年").convert("にせんにじゅうろくねん"))
        assertEquals(listOf(ResultSegment("じゅうじ", "じゅうじ")), withoutDictionary("10時").convert("じゅうじ"))
        assertEquals(listOf(ResultSegment("じゅうじ", "じゅうじ")), withoutDictionary("11時").convert("じゅうじ"))

        // 数字の頭を削った出力は、辞書の候補の部分文字列であっても完全一致しないため捨てる。
        assertEquals(lexiconSegments("じゅうじ"), withDictionary("0時").convert("じゅうじ"))
        assertEquals(lexiconSegments("にせんにじゅうろくねん"), withDictionary("26年").convert("にせんにじゅうろくねん"))
        assertEquals(lexiconSegments("にせんにじゅうろくねん"), withDictionary("6年").convert("にせんにじゅうろくねん"))
        assertEquals(lexiconSegments("じゅうじから"), withDictionary("0時から").convert("じゅうじから"))

        // 辞書の候補にある数字は、文節をまたいで連結した候補（「10」＋「時」）でも一segmentの候補でも使う。
        assertEquals(listOf("10時"), withDictionary("10時").convert("じゅうじ").map { it.surface }.take(1))
        assertEquals(listOf("2026年"), withDictionary("2026年").convert("にせんにじゅうろくねん").map { it.surface })
    }

    /** 辞書の文節が数字の連なりの読みの範囲の境界をまたぐ場合は、候補で確かめられないため辞書の結果へ戻す。 */
    @Test
    fun digitsOverlappingDictionarySegmentPartlyAreRejected() {
        // 「じゅうじか」を一文節とする辞書。出力の「10時」は読み「じゅうじ」に対応し、文節の一部だけに重なる。
        val crossing = listOf(ResultSegment("じゅうじか", "10時か", listOf("10時か")), ResultSegment("ら", "ら"))
        val converter = NeuralRangeConverter(model = { _, _ -> output("10時から") }, dictionary = { crossing })

        assertEquals(crossing, converter.convert("じゅうじから"))
    }

    /** 漢字の読みは確かめないため、読みに合わない漢字語は捨てられない（設計上の限界を固定する）。 */
    @Test
    fun kanjiReadingIsNotVerified() {
        val converter = NeuralRangeConverter(model = fixedModel("でんしゃがおくれる" to "電車が遅延"), dictionary = ::lexiconSegments)

        assertEquals(listOf("電車", "が", "遅延"), converter.convert("でんしゃがおくれる").map { it.surface })
    }

    /** 読みへの割り当てが一つに決まらない出力は、内部で切らずにかなの連なり全体を一segmentにする。 */
    @Test
    fun ambiguousAlignmentKeepsWholeRun() {
        val converter = NeuralRangeConverter(model = fixedModel("かかかか" to "蚊か蚊"), dictionary = ::lexiconSegments)

        assertEquals(listOf(ResultSegment("かかかか", "蚊か蚊", listOf("蚊か蚊", "蚊蚊蚊蚊"))), converter.convert("かかかか"))
    }

    /** `SegmentedLiveConverter`へ差し込んだ結果を、候補の選択後もコアが拒否せずに適用する。 */
    @Test
    fun coreAcceptsNeuralResultsThroughSegmentedConverter() {
        val core = LiveConversionCore().apply { startField(LiveFieldPolicy.NORMAL) }
        val model = fixedModel("こうえんにいく" to "公園に行く", "にいくよ" to "に行くよ")
        val converter = SegmentedLiveConverter(convertRange = NeuralRangeConverter(model, ::lexiconSegments)::convert)
        // コアの操作を行い、要求があれば変換して結果を返す。結果が拒否されないことを確かめる。
        fun run(update: LiveUpdate) {
            val request = update.request ?: return
            val result = converter.convert(request)
            assertNotNull(result)
            assertNull(core.onConversionResult(result!!).rejection)
        }

        run(core.inputText("こうえんにいく"))
        assertEquals("公園に行く", core.display)
        run(core.focusSegment(core.segments.first().id))
        run(core.selectCandidate(core.candidateBar()!!.choices.first { it.value == "講演" }))
        run(core.returnToInputPosition())
        run(core.inputText("よ"))

        assertEquals("講演に行くよ", core.display)
        assertEquals(SegmentState.CHOSEN, core.segments.first().state)
    }

    /** 保護範囲の表記と前の部分範囲の表記は、`SegmentedLiveConverter`から左文脈としてモデルへ渡る。 */
    @Test
    fun protectedSurfaceBecomesLeftContext() {
        val calls = mutableListOf<Pair<String, String>>()
        val model = NeuralKanaKanjiModel { reading, context ->
            calls += reading to context
            output(mapOf("てんき" to "天気", "きょうは" to "今日は")[reading])
        }
        val converter = SegmentedLiveConverter(convertRange = NeuralRangeConverter(model, ::lexiconSegments)::convert)
        val identity = dev.uzumi.ime.live.RequestIdentity(
            sessionEpoch = 1, revision = 1, reading = "きょうはてんき", targetStart = 0, targetEnd = 7,
            protectedRanges = listOf(dev.uzumi.ime.live.ProtectedRange(0, 4, "京は")), inputCursor = 7, converterGeneration = 0,
        )

        val result = converter.convert(dev.uzumi.ime.live.ConversionRequest(identity, "きょうはてんき", learningAllowed = true))

        assertEquals(listOf("てんき" to "京は"), calls)
        assertEquals(listOf("京は", "天気"), result!!.segments.map { it.surface })
    }

    /** 30文字を超えるかなの連なりは、30文字以内で最も後ろの辞書の文節境界で区切り、前の表記を後ろの左文脈にする。 */
    @Test
    fun longKanaRunIsSplitAtLastDictionaryBoundaryWithinLimit() {
        val calls = mutableListOf<Pair<String, String>>()
        val model = NeuralKanaKanjiModel { reading, context ->
            calls += reading to context
            output(reading)
        }
        val observed = mutableListOf<Boolean>()
        val observer = object : NeuralConversionObserver {
            override fun onKanaRun(reading: String, split: Boolean) {
                observed += split
            }
        }
        // 「あいう」を1文節とする辞書で、31文字（あいう×10＋え）を変換する。境界は3文字ごと。
        val dictionary = { chunk: String ->
            GraphemeClusters.split(chunk).chunked(3).map { part -> ResultSegment(part.joinToString(""), part.joinToString("")) }
        }
        val reading = "あいう".repeat(10) + "え"
        val segments = NeuralRangeConverter(model, dictionary, observer).convert(reading)

        assertEquals(listOf("あいう".repeat(10), "え"), calls.map { it.first })
        assertEquals("あいう".repeat(10), calls[1].second)
        assertEquals(listOf(true, false), observed)
        assertEquals(reading, segments.joinToString("") { it.reading })
    }

    /** token上限で打ち切った出力（検査2）は使わず、辞書の結果を表示する。検査の結果は窓口へ伝わる。 */
    @Test
    fun truncatedOutputIsRejectedByCheckTwo() {
        val verdicts = mutableListOf<NeuralVerdict>()
        val observer = object : NeuralConversionObserver {
            override fun onModelOutput(reading: String, output: String, verdict: NeuralVerdict) {
                verdicts += verdict
            }
        }
        val converter = NeuralRangeConverter(
            model = { _, _ -> NeuralModelOutput.Completed("今日は", truncated = true) },
            dictionary = ::lexiconSegments,
            observer = observer,
        )

        assertEquals(lexiconSegments("きょうは"), converter.convert("きょうは"))
        assertEquals(listOf(NeuralVerdict.CHECK2_TRUNCATED), verdicts)
    }

    /** 新しい入力で推論が中断されたら、辞書へ戻さず変換要求全体を取り下げる。 */
    @Test(expected = NeuralRequestCancelled::class)
    fun cancelledInferenceAbortsWholeRequest() {
        NeuralRangeConverter(model = { _, _ -> NeuralModelOutput.Cancelled }, dictionary = ::lexiconSegments).convert("てんき")
    }

    /** 読みに完全一致する入力だけに決まった表記を返すfakeのモデル。 */
    private fun fixedModel(vararg outputs: Pair<String, String>): NeuralKanaKanjiModel {
        val table = outputs.toMap()
        return NeuralKanaKanjiModel { reading, _ -> output(table[reading]) }
    }

    /** fakeのモデルの出力を作る。nullはモデルが使えない場合を表す。 */
    private fun output(text: String?): NeuralModelOutput =
        text?.let { NeuralModelOutput.Completed(it) } ?: NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE)

    /** 小さな語彙で最長一致の文節に分けるfakeの辞書。語彙に無い書記素はそのまま一文節にする。 */
    private fun lexiconSegments(chunk: String): List<ResultSegment> {
        val lexicon = mapOf(
            "きょう" to listOf("今日", "京"),
            "は" to listOf("は", "葉"),
            "いい" to listOf("良い", "いい"),
            "てんき" to listOf("天気", "転機"),
            "ねん" to listOf("年", "念"),
            "です" to listOf("です"),
            "の" to listOf("の", "野"),
            "てすと" to listOf("テスト"),
            "でんしゃ" to listOf("電車"),
            "が" to listOf("が", "蛾"),
            "おくれる" to listOf("遅れる", "送れる"),
            "じゅう" to listOf("十", "10", "銃"),
            "にせんにじゅうろくねん" to listOf("2026年", "二千二十六年"),
            "じ" to listOf("時", "字"),
            "から" to listOf("から", "空"),
            "か" to listOf("蚊", "か"),
            "こうえん" to listOf("公園", "講演"),
            "に" to listOf("に", "二"),
            "いく" to listOf("行く", "いく"),
            "よ" to listOf("よ"),
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
}
