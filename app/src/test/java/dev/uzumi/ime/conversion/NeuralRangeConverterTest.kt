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
            mapOf("ねんです" to "年です", "のてすと" to "のテスト")[reading]
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
            val converter = NeuralRangeConverter(model = { _, _ -> output }, dictionary = ::lexiconSegments)
            assertEquals("出力: $output", expected, converter.convert(reading))
        }
    }

    /** 辞書が使えない場合、モデルの表記は一segmentで表示し、モデルも使えなければ読みのまま表示する。 */
    @Test
    fun withoutDictionaryShowsNeuralOrReading() {
        val neuralOnly = NeuralRangeConverter(model = fixedModel("てんき" to "天気"), dictionary = { null })
        val neither = NeuralRangeConverter(model = { _, _ -> null }, dictionary = { null })

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

    /** 読みに完全一致する入力だけに決まった表記を返すfakeのモデル。 */
    private fun fixedModel(vararg outputs: Pair<String, String>): NeuralKanaKanjiModel {
        val table = outputs.toMap()
        return NeuralKanaKanjiModel { reading, _ -> table[reading] }
    }

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
            "じゅう" to listOf("十", "銃"),
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
