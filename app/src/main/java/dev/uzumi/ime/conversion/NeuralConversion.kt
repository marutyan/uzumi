package dev.uzumi.ime.conversion

import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.live.ResultSegment

/**
 * ニューラルかな漢字変換モデルへの最小のport。実装（別プロセスで動かすllama.cpp）は、
 * モデルごとのプロンプト形式（読みのカタカナ化、NFKC、区切り記号）を内部で組み立てる。
 * 読みはかなだけを渡す。結果は[NeuralModelOutput]で返し、表示してよいかの検査は呼び出し側（[NeuralRangeConverter]）が行う。
 */
fun interface NeuralKanaKanjiModel {
    /** 読みを変換した表記を一つ返す。leftContextは読みの直前に表示されている文字列。 */
    fun convert(reading: String, leftContext: String): NeuralModelOutput
}

/** モデルへの一回の要求の結果。検査1・2の材料（出力の文字列と、token上限で打ち切ったか）をそのまま渡す。 */
sealed interface NeuralModelOutput {
    /**
     * モデルが出力を返した。truncatedがtrueなら終端tokenの前に最大出力tokenへ達した（検査2で捨てる）。
     * 壊れたUTF-8は置換文字（U+FFFD）にして返し、検査1で捨てる。
     */
    data class Completed(val text: String, val truncated: Boolean = false) : NeuralModelOutput

    /** モデルが使えない（未準備、別プロセスの終了、時間超過、異常）。呼び出し側は辞書の結果を使う。 */
    data class Failed(val reason: NeuralFailure) : NeuralModelOutput

    /** 新しい入力で推論を中断した。この要求の結果は古いため、変換要求全体を取り下げる。 */
    data object Cancelled : NeuralModelOutput
}

/** モデルが使えなかった理由。評価用計数で時間超過とそれ以外を分けるために使う。 */
enum class NeuralFailure {
    /** モデルの読み込み前、読み込みの失敗、別プロセスの未接続・終了。 */
    UNAVAILABLE,

    /** 時間超過（評価条件の300 ms）。 */
    TIMEOUT,

    /** 推論の失敗（decodeの失敗、`n_ctx`超過など）。 */
    ERROR,
}

/** 新しい入力でモデルの推論を中断したときに投げ、変換要求全体を取り下げる。呼び出し側は結果を返さない。 */
class NeuralRequestCancelled : RuntimeException("neural request cancelled")

/** モデルの出力を検査した結果。評価用計数で、どの検査で捨てたかを数えるために使う。 */
enum class NeuralVerdict {
    ACCEPTED,

    /** 検査1：空、制御文字、置換文字、区切り記号を含む。 */
    CHECK1_INVALID_TEXT,

    /** 検査2：終端tokenで終わらなかった（最大出力tokenに達した）。 */
    CHECK2_TRUNCATED,

    /** 検査3：かなの並びが読みと合わない。 */
    CHECK3_KANA_MISMATCH,

    /** 検査4：かなの読みから生まれた数字・英字が辞書の候補に無い。 */
    CHECK4_UNBACKED_DIGITS,
}

/**
 * [NeuralRangeConverter]の処理の経過を受け取る窓口。評価用計数（件数だけ）を作るために使い、既定では何もしない。
 * 呼び出しは変換を行うthread（直列worker）からだけ行われる。
 */
interface NeuralConversionObserver {
    /** かなの連なりを一つ変換し始めた。splitは30文字を超えたため区切ったか。 */
    fun onKanaRun(reading: String, split: Boolean) {}

    /** モデルの出力を検査した。outputは検査前の出力で、失敗・中断では呼ばれない。 */
    fun onModelOutput(reading: String, output: String, verdict: NeuralVerdict) {}

    /** モデルが使えなかった。 */
    fun onModelFailed(reason: NeuralFailure) {}

    /** かなの連なりを、モデルを使わず辞書の結果（usedReading=falseのとき）か読みのまま（true）で表示した。 */
    fun onFallback(usedReading: Boolean) {}

    companion object {
        /** 何もしない既定の窓口。 */
        val NONE = object : NeuralConversionObserver {}
    }
}

// モデルへ渡すかなの連なりの最大の文字数（評価条件で全モデル共通に固定）。超えたら辞書の文節境界で区切る。
const val NEURAL_MAX_KANA_CHARS = 30

/**
 * ニューラルで直接変換し、辞書で補う部分範囲の変換器。`SegmentedLiveConverter`の`convertRange`へ差し込んで使い、
 * 保護範囲・カーソルでの分割とユーザー辞書・学習語の合成は既存の規則に任せる。
 * かなの連なりだけをモデルへ渡し、数字・英字・記号は入力のまま表示する。モデルの出力は、かなの並びが読みと合うか、
 * かなの読みから生まれた数字・英字が辞書の候補にあるかを確かめてから辞書の文節へ切り分け、合わなければ辞書の結果を表示する。
 * 漢字の読みは確かめないため、読みに合わない漢字語（例：「おくれる」から「遅延」）は捨てられない。
 */
class NeuralRangeConverter(
    private val model: NeuralKanaKanjiModel,
    // 一つのかなの連なりを辞書（Mozc）で変換する。読みの連結が入力と一致するsegment列を返し、使えなければnull。
    private val dictionary: (String) -> List<ResultSegment>?,
    private val observer: NeuralConversionObserver = NeuralConversionObserver.NONE,
    // 検査4を行うか。falseは、評価用計数が検査と独立に違反を数えることをJVMテストで確かめるためだけに使う。
    private val checkDigitsWithDictionary: Boolean = true,
    // 左文脈をモデルへ渡すか。学習禁止欄と機密欄ではfalseにし、保護範囲や先に変換した表記をモデルへ渡さない。
    private val useLeftContext: Boolean = true,
) {
    /**
     * 部分範囲の読みを、読みの連結が部分範囲と一致するsegment列へ変換する。モデルと辞書が使えなくても読みのまま返す。
     * leftContextは部分範囲より前の表示（保護範囲の表記と、同じ要求で先に変換した表記）。
     * モデルの推論が新しい入力で中断された場合は[NeuralRequestCancelled]を投げる。
     */
    fun convert(chunk: String, leftContext: String = ""): List<ResultSegment> {
        val segments = mutableListOf<ResultSegment>()
        for (run in scriptRuns(chunk)) {
            segments += if (run.isKana) {
                // 部分範囲より前の表示と、同じ部分範囲で先に出した表記（数字などを含む）を左文脈にする。
                val context = if (useLeftContext) leftContext + segments.joinToString(separator = "") { it.surface } else ""
                convertKanaRun(run.text, context)
            } else {
                listOf(ResultSegment(run.text, run.text))
            }
        }
        return segments
    }

    /**
     * かなの連なり一つを変換する。30文字を超える場合は、30文字以内で最も後ろにある辞書の文節境界で区切り、
     * 前の部分を別の連なりとして先に変換して、その表記を後ろの左文脈にする。辞書が使えなければ30文字で区切る。
     */
    private fun convertKanaRun(reading: String, leftContext: String): List<ResultSegment> {
        val clusters = GraphemeClusters.split(reading)
        if (clusters.size <= NEURAL_MAX_KANA_CHARS) {
            observer.onKanaRun(reading, split = false)
            return convertKana(reading, leftContext, lookupDictionary(reading))
        }
        val dictionarySegments = lookupDictionary(reading)
        // 辞書の文節境界（書記素位置）のうち、先頭から30文字以内で最も後ろのもの。無ければ30文字目で区切る。
        var boundary = 0
        var cut = 0
        dictionarySegments?.forEach { segment ->
            boundary += GraphemeClusters.split(segment.reading).size
            if (boundary <= NEURAL_MAX_KANA_CHARS) cut = boundary
        }
        if (cut == 0) cut = NEURAL_MAX_KANA_CHARS
        val head = clusters.subList(0, cut).joinToString(separator = "")
        val tail = clusters.subList(cut, clusters.size).joinToString(separator = "")
        observer.onKanaRun(head, split = true)
        val headSegments = convertKana(head, leftContext, lookupDictionary(head))
        val tailContext = if (useLeftContext) leftContext + headSegments.joinToString(separator = "") { it.surface } else ""
        return headSegments + convertKanaRun(tail, tailContext)
    }

    /** 辞書で変換し、読みの連結が入力と一致する結果だけを返す。 */
    private fun lookupDictionary(reading: String): List<ResultSegment>? =
        runCatching { dictionary(reading) }.getOrNull()?.takeIf { segments ->
            segments.isNotEmpty() &&
                segments.all { it.reading.isNotEmpty() && it.surface.isNotEmpty() } &&
                segments.joinToString(separator = "") { it.reading } == reading
        }

    /** かなの連なり一つを、モデルの表記と辞書の文節・候補から変換する。 */
    private fun convertKana(
        reading: String,
        leftContext: String,
        dictionarySegments: List<ResultSegment>?,
    ): List<ResultSegment> {
        val output = when (val result = runCatching { model.convert(reading, leftContext) }
            .getOrElse { NeuralModelOutput.Failed(NeuralFailure.ERROR) }) {
            NeuralModelOutput.Cancelled -> throw NeuralRequestCancelled()
            is NeuralModelOutput.Failed -> {
                observer.onModelFailed(result.reason)
                null
            }
            is NeuralModelOutput.Completed -> result
        }
        var cuts: Map<Int, Int>? = null
        if (output != null) {
            val verdict = when {
                !isAcceptableOutput(output.text) -> NeuralVerdict.CHECK1_INVALID_TEXT
                output.truncated -> NeuralVerdict.CHECK2_TRUNCATED
                else -> when (val aligned = alignToReading(reading, output.text)) {
                    null -> NeuralVerdict.CHECK3_KANA_MISMATCH
                    else -> if (!checkDigitsWithDictionary ||
                        hasDictionaryBackedDigitsAndLetters(output.text, aligned, dictionarySegments)
                    ) {
                        cuts = aligned
                        NeuralVerdict.ACCEPTED
                    } else {
                        NeuralVerdict.CHECK4_UNBACKED_DIGITS
                    }
                }
            }
            observer.onModelOutput(reading, output.text, verdict)
        }
        val accepted = cuts
        return when {
            output != null && accepted != null -> splitByDictionary(reading, output.text, accepted, dictionarySegments)
            dictionarySegments != null -> dictionarySegments.also { observer.onFallback(usedReading = false) }
            else -> listOf(ResultSegment(reading, reading)).also { observer.onFallback(usedReading = true) }
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

/** 数字（全角を含む）または英字（全角を含む）の書記素か。モデルがかなの読みから作ってよいかを辞書で確かめる対象を表す。 */
private fun isDigitOrLetterCluster(cluster: String): Boolean =
    cluster.codePoints().anyMatch {
        Character.isDigit(it) ||
            it in 'A'.code..'Z'.code ||
            it in 'a'.code..'z'.code ||
            it in 0xFF21..0xFF3A ||
            it in 0xFF41..0xFF5A
    }

// 数字・英字の検査で、辞書の文節の候補を連結して作る組み合わせの上限。長い読みで計算が膨らむのを防ぐ。
// 上限で切った組み合わせに一致しなければ捨てる側に倒れる。
private const val MAX_CANDIDATE_COMBINATIONS = 256

/**
 * 読みに対応づけた出力のうち、数字・英字を含む連なりが、同じ読みの範囲をちょうど覆う辞書の文節の候補
 * （各文節の候補を一つずつ連結したもの）のどれかと完全に一致するかを確かめる。
 * 部分文字列の一致では「10時」に対する「0時」のような数字の頭の欠けが通るため、完全一致だけを認める。
 * 辞書の文節が読みの範囲の境界をまたぐ（一部だけ重なる）場合と、辞書が使えない場合は確かめられないため捨てる。
 * かなの位置の照合だけでは、「じゅうじ」から「11時」のような読みに合わない数字を捨てられないため必要になる。
 * cutsは`alignToReading`の返り値で、隣り合う二点の間が出力の一つの連なりと、それに対応する読みの範囲を表す。
 */
private fun hasDictionaryBackedDigitsAndLetters(
    output: String,
    cuts: Map<Int, Int>,
    dictionarySegments: List<ResultSegment>?,
): Boolean {
    val outputClusters = GraphemeClusters.split(output)
    if (outputClusters.none(::isDigitOrLetterCluster)) return true
    if (dictionarySegments == null) return false
    // 辞書の各文節の読みの範囲（書記素位置の[start, end)）。
    val ranges = mutableListOf<IntRange>()
    var position = 0
    for (segment in dictionarySegments) {
        val length = GraphemeClusters.split(segment.reading).size
        ranges += position until position + length
        position += length
    }
    val points = cuts.entries.sortedBy { it.key }
    return points.zipWithNext().all { (start, end) ->
        val piece = outputClusters.subList(start.value, end.value).joinToString(separator = "")
        if (GraphemeClusters.split(piece).none(::isDigitOrLetterCluster)) return@all true
        val overlapping = dictionarySegments.indices.filter { ranges[it].first < end.key && ranges[it].last + 1 > start.key }
        // 重なる文節がすべて読みの範囲の内側にあり、範囲の両端が文節の境界に一致するときだけ候補で確かめられる。
        val coversExactly = overlapping.isNotEmpty() &&
            ranges[overlapping.first()].first == start.key &&
            ranges[overlapping.last()].last + 1 == end.key
        if (!coversExactly) return@all false
        var combinations = listOf("")
        for (index in overlapping) {
            val segment = dictionarySegments[index]
            val candidates = (listOf(segment.surface) + segment.candidates).distinct()
            combinations = combinations.flatMap { prefix -> candidates.map { prefix + it } }.take(MAX_CANDIDATE_COMBINATIONS)
        }
        piece in combinations
    }
}

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
