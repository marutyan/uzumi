package dev.uzumi.ime.conversion

import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.live.ResultSegment

/**
 * ニューラルかな漢字変換モデルへの最小のport。実装（別プロセスで動かすllama.cpp）は、
 * モデルごとのプロンプト形式（読みのカタカナ化、NFKC、区切り記号）を内部で組み立てる。
 * 読みはかなだけを渡す。時間超過・モデル不在・異常終了ではnullを返し、呼び出し側は辞書の結果を使う。
 */
fun interface NeuralKanaKanjiModel {
    /** 読みを変換した表記を一つ返す。leftContextは読みの直前に表示されている文字列。失敗時はnull。 */
    fun convert(reading: String, leftContext: String): String?
}

/**
 * ニューラルで直接変換し、辞書で補う部分範囲の変換器。`SegmentedLiveConverter`の`convertRange`へ差し込んで使い、
 * 保護範囲・カーソルでの分割とユーザー辞書・学習語の合成は既存の規則に任せる。
 * かなの連なりだけをモデルへ渡し、数字・英字・記号は入力のまま表示する。モデルの出力は読みに対応づけてから
 * 辞書の文節へ切り分け、対応づけられない出力（読みに無い語の生成など）は捨てて辞書の結果を表示する。
 */
class NeuralRangeConverter(
    private val model: NeuralKanaKanjiModel,
    // 一つのかなの連なりを辞書（Mozc）で変換する。読みの連結が入力と一致するsegment列を返し、使えなければnull。
    private val dictionary: (String) -> List<ResultSegment>?,
) {
    /** 部分範囲の読みを、読みの連結が部分範囲と一致するsegment列へ変換する。モデルと辞書が使えなくても読みのまま返す。 */
    fun convert(chunk: String): List<ResultSegment> {
        val segments = mutableListOf<ResultSegment>()
        for (run in scriptRuns(chunk)) {
            segments += if (run.isKana) {
                // 同じ部分範囲で先に出した表記（数字などを含む）を左文脈にする。
                convertKana(run.text, leftContext = segments.joinToString(separator = "") { it.surface })
            } else {
                listOf(ResultSegment(run.text, run.text))
            }
        }
        return segments
    }

    /** かなの連なり一つを、モデルの表記と辞書の文節・候補から変換する。 */
    private fun convertKana(reading: String, leftContext: String): List<ResultSegment> {
        val dictionarySegments = runCatching { dictionary(reading) }.getOrNull()?.takeIf { segments ->
            segments.isNotEmpty() &&
                segments.all { it.reading.isNotEmpty() && it.surface.isNotEmpty() } &&
                segments.joinToString(separator = "") { it.reading } == reading
        }
        val output = runCatching { model.convert(reading, leftContext) }.getOrNull()
        val cuts = output?.takeIf(::isAcceptableOutput)?.let { alignToReading(reading, it) }
        return when {
            output != null && cuts != null -> splitByDictionary(reading, output, cuts, dictionarySegments)
            dictionarySegments != null -> dictionarySegments
            else -> listOf(ResultSegment(reading, reading))
        }
    }

    /**
     * 読みに対応づけたモデルの表記を、辞書の文節境界のうち切れる点に当たるものだけで切る。切れない境界の両側はつなぐ。
     * 候補は、つながなかった文節ではモデルの表記の後に辞書の候補を続け、つないだ文節ではモデルの表記と辞書の表記の連結にする。
     */
    private fun splitByDictionary(
        reading: String,
        output: String,
        cuts: Map<Int, Int>,
        dictionarySegments: List<ResultSegment>?,
    ): List<ResultSegment> {
        val outputClusters = GraphemeClusters.split(output)
        if (dictionarySegments == null) return listOf(ResultSegment(reading, output, listOf(output, reading).distinct()))
        val result = mutableListOf<ResultSegment>()
        // 今つないでいる辞書の文節と、その開始位置（読みの書記素位置）。
        val group = mutableListOf<ResultSegment>()
        var groupStart = 0
        var position = 0
        for (segment in dictionarySegments) {
            group += segment
            position += GraphemeClusters.split(segment.reading).size
            val surfaceEnd = cuts[position] ?: continue
            val surface = outputClusters.subList(cuts.getValue(groupStart), surfaceEnd).joinToString(separator = "")
            val candidates = if (group.size == 1) {
                listOf(surface) + segment.candidates
            } else {
                listOf(surface, group.joinToString(separator = "") { it.surface })
            }
            result += ResultSegment(group.joinToString(separator = "") { it.reading }, surface, candidates.distinct())
            group.clear()
            groupStart = position
        }
        return result
    }
}

/** 部分範囲を文字の種類で分けた一つの連なり。isKanaがtrueならモデルへ渡し、falseなら入力のまま表示する。 */
private data class ScriptRun(val text: String, val isKana: Boolean)

/** 部分範囲を、かなの連なりとそれ以外（数字、英字、記号、絵文字など）の連なりへ分ける。 */
private fun scriptRuns(chunk: String): List<ScriptRun> {
    val runs = mutableListOf<ScriptRun>()
    for (cluster in GraphemeClusters.split(chunk)) {
        val kana = isKanaCluster(cluster)
        val last = runs.lastOrNull()
        if (last != null && last.isKana == kana) {
            runs[runs.lastIndex] = last.copy(text = last.text + cluster)
        } else {
            runs += ScriptRun(cluster, kana)
        }
    }
    return runs
}

/**
 * 書記素がかな（ひらがな、カタカナ、長音記号、繰り返し記号、結合用の濁点・半濁点）だけでできているか。
 * 中黒（U+30FB）はカタカナのブロックにあるが記号なので含めない。
 */
private fun isKanaCluster(cluster: String): Boolean =
    cluster.isNotEmpty() &&
        cluster.codePoints().allMatch {
            it in 0x3041..0x3096 || it in 0x3099..0x309F || it in 0x30A1..0x30FA || it in 0x30FC..0x30FF
        }

/** かなをひらがなへそろえる。読み（ひらがな）とモデルの出力（カタカナを含む）を同じ文字として比べるために使う。 */
private fun toHiragana(cluster: String): String = buildString {
    cluster.codePoints().forEach { codePoint ->
        // カタカナ（ァ..ヶ、ヽヾ）は同じ音のひらがなから0x60だけ後ろにある。長音記号（ー）はそのまま比べる。
        appendCodePoint(if (codePoint in 0x30A1..0x30F6 || codePoint in 0x30FD..0x30FE) codePoint - 0x60 else codePoint)
    }
}

/**
 * モデルの出力を表示してよい文字だけでできているか。空、制御文字、置換文字（U+FFFD）、
 * プロンプト用の区切り記号（U+EE00..U+EE0F）を含む出力は使わない。
 */
private fun isAcceptableOutput(output: String): Boolean =
    output.isNotEmpty() &&
        output.codePoints().noneMatch { it < 0x20 || it == 0x7F || it == 0xFFFD || it in 0xEE00..0xEE0F }

/**
 * モデルの出力を読みに対応づけ、読みと表記を同じ位置で切れる点（読みの書記素位置 → 出力の書記素位置）を返す。
 * 出力のかなは読みの同じ位置の文字と一致し、それ以外（漢字、数字、記号など）の連なりは読みの1文字以上に対応する、
 * という割り当てを探す。割り当てが無ければ読みに無い語の生成などとみなしてnullを返す。
 * 割り当てが二つ以上あれば内部で切れる点を決められないため、先頭と末尾だけを返す。
 */
private fun alignToReading(reading: String, output: String): Map<Int, Int>? {
    val readingClusters = GraphemeClusters.split(reading).map(::toHiragana)
    val outputClusters = GraphemeClusters.split(output)
    // 出力を、かな1文字ずつの単位と、かな以外の連なりの単位に分ける。各単位は出力の[start, end)を指す。
    val units = mutableListOf<IntRange>()
    for ((index, cluster) in outputClusters.withIndex()) {
        val last = units.lastOrNull()
        if (!isKanaCluster(cluster) && last != null && !isKanaCluster(outputClusters[last.first])) {
            units[units.lastIndex] = last.first..index
        } else {
            units += index..index
        }
    }
    val n = readingClusters.size
    // ways[u][j]：単位u以降を読みのj文字目以降へ割り当てる方法の数（2以上は2に丸める）。
    val ways = Array(units.size + 1) { IntArray(n + 1) }
    ways[units.size][n] = 1
    for (u in units.indices.reversed()) {
        val unit = units[u]
        val isKana = isKanaCluster(outputClusters[unit.first])
        for (j in 0..n) {
            ways[u][j] = if (isKana) {
                if (j < n && readingClusters[j] == toHiragana(outputClusters[unit.first])) ways[u + 1][j + 1] else 0
            } else {
                (j + 1..n).sumOf { ways[u + 1][it] }.coerceAtMost(2)
            }
        }
    }
    return when (ways[0][0]) {
        0 -> null
        1 -> {
            val cuts = mutableMapOf(0 to 0)
            var j = 0
            for (u in units.indices) {
                val unit = units[u]
                j = if (isKanaCluster(outputClusters[unit.first])) j + 1 else (j + 1..n).first { ways[u + 1][it] == 1 }
                cuts[j] = unit.last + 1
            }
            cuts
        }
        else -> mapOf(0 to 0, n to outputClusters.size)
    }
}
