package dev.uzumi.ime.evaluation

import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.ResultSegment
import java.text.Normalizer

/**
 * Phase 3aの評価条件の「H3の数え方」と「正→誤の遷移の数え方」を、変換器とは別に実装した判定。
 * 変換器の検査4（`NeuralRangeConverter`の中の関数）は呼ばず、照合に使うMozcの文節も呼び出し側が
 * Mozcから直接取り出して渡す。検査が壊れていても、ここで違反を数えられるようにするためである。
 * どの関数も件数か真偽だけを返し、文字列を残さない。
 */
object NeuralAudit {
    // 候補の連結を作る組み合わせの上限。上限で切った場合は裏付けが無い側（違反を数える側）に倒れる。
    private const val MAX_COMBINATIONS = 256

    /**
     * 生成件数：モデルの出力[output]に含まれる数字・英字の連なりのうち、同じ読みの範囲をちょうど覆うMozcの文節の
     * 候補の連結と完全一致する連なりを持たないものの数。出力を読みへ割り当てる方法をすべて調べ、裏付けの無い連なりが
     * 最も少ない割り当てで数える。割り当てが無い（かなの並びが読みと合わない）場合と、Mozcが使えない場合は、
     * 範囲を決められないためすべての連なりを数える。
     */
    fun generatedDigitLetterRuns(reading: String, output: String, mozc: List<ResultSegment>?): Int {
        val outputClusters = GraphemeClusters.split(output)
        val totalRuns = digitLetterRuns(outputClusters).size
        if (totalRuns == 0) return 0
        if (mozc == null || mozc.joinToString(separator = "") { it.reading } != reading) return totalRuns
        val readingClusters = GraphemeClusters.split(reading).map(::hiragana)
        val units = outputUnits(outputClusters)
        // Mozcの各文節の読みの範囲（書記素位置の[start, end)）。
        val bounds = mutableListOf<Pair<Int, Int>>()
        var position = 0
        for (segment in mozc) {
            val length = GraphemeClusters.split(segment.reading).size
            bounds += position to position + length
            position += length
        }
        val n = readingClusters.size
        val impossible = Int.MAX_VALUE / 2
        // best[u][j]：単位u以降を読みのj文字目以降へ割り当てたときの、裏付けの無い連なりの最小の数。
        val best = Array(units.size + 1) { IntArray(n + 1) { impossible } }
        best[units.size][n] = 0
        for (u in units.indices.reversed()) {
            val text = units[u]
            val runs = digitLetterRuns(GraphemeClusters.split(text)).size
            for (j in 0..n) {
                best[u][j] = if (isKana(text)) {
                    if (j < n && readingClusters[j] == hiragana(text)) best[u + 1][j + 1] else impossible
                } else {
                    (j + 1..n).minOfOrNull { end ->
                        val rest = best[u + 1][end]
                        if (rest >= impossible) {
                            impossible
                        } else {
                            rest + if (runs == 0 || backedByMozc(text, j, end, mozc, bounds)) 0 else runs
                        }
                    } ?: impossible
                }
            }
        }
        return best[0][0].takeIf { it < impossible } ?: totalRuns
    }

    /**
     * 適用後の違反件数：表示へ適用したsegmentのうち、読みに無い数字・英字（かなの読みから作られたもの）を含み、
     * その表記がsegmentの読みを直接Mozcで変換した文節の候補の連結のどれとも一致しないものの数。
     * [mozc]は読みをMozcで直接変換する関数で、変換器を通さない。
     */
    fun appliedViolations(segments: List<ResultSegment>, mozc: (String) -> List<ResultSegment>?): Int =
        segments.count { segment ->
            val generated = generatedRuns(segment.reading, segment.surface) ?: return@count false
            generated.isNotEmpty() && !surfaceIsMozcCandidate(segment.reading, segment.surface, mozc(segment.reading))
        }

    /**
     * 入力した数字・英字の改変：segmentの読みに含まれる数字・英字の連なり（全角は半角へ直す）が、同じ順で表記に
     * 残っていないsegmentの数。
     */
    fun inputModifications(segments: List<ResultSegment>): Int =
        segments.count { generatedRuns(it.reading, it.surface) == null }

    /**
     * 最終文の数字の並びの違反：最終文と各許容表記から数字の連なり（全角は半角へ直す）を順に取り出した列を作り、
     * 最終文の列がどの許容表記の列とも一致しなければtrue。許容表記が無ければ判定しない（false）。
     */
    fun digitSequenceViolation(finalText: String, accepted: List<String>): Boolean {
        if (accepted.isEmpty()) return false
        val actual = digitRuns(finalText)
        return accepted.none { digitRuns(it) == actual }
    }

    /**
     * 表示[text]が、許容表記[accepted]のいずれかと先頭から一致する最長の長さ（書記素の数）。
     */
    fun correctPrefixLength(text: String, accepted: List<String>): Int {
        val clusters = GraphemeClusters.split(text)
        return accepted.maxOfOrNull { form ->
            val target = GraphemeClusters.split(form)
            var length = 0
            while (length < clusters.size && length < target.size && clusters[length] == target[length]) length += 1
            length
        } ?: 0
    }

    /**
     * 正→誤の遷移：変換結果の適用で表示が[before]から[after]へ変わったとき、正しい接頭部が短くなり、
     * 失われた範囲に変更前に変換済みだったsegmentが含まれていればtrue。[prefix]はcompositionより前の欄の文字列。
     */
    fun isCorrectToWrong(prefix: String, before: List<LiveSegment>, after: List<LiveSegment>, accepted: List<String>): Boolean {
        if (accepted.isEmpty()) return false
        val beforeLength = correctPrefixLength(prefix + before.joinToString(separator = "") { it.surface }, accepted)
        val afterLength = correctPrefixLength(prefix + after.joinToString(separator = "") { it.surface }, accepted)
        if (afterLength >= beforeLength) return false
        // 失われた範囲[afterLength, beforeLength)（欄の先頭からの書記素位置）に掛かる、変更前のsegment。
        var offset = GraphemeClusters.split(prefix).size
        for (segment in before) {
            val start = offset
            offset += GraphemeClusters.split(segment.surface).size
            if (segment.converted && start < beforeLength && offset > afterLength) return true
        }
        return false
    }

    /**
     * segmentの表記に現れる数字・英字の連なりのうち、読みに無いもの（読みの連なりを順に除いた残り）を返す。
     * 読みの連なりが同じ順で表記に残っていなければnull（入力した数字・英字の改変）。
     */
    private fun generatedRuns(reading: String, surface: String): List<String>? {
        val readingRuns = digitLetterRuns(GraphemeClusters.split(reading)).map(::halfWidth)
        val surfaceRuns = digitLetterRuns(GraphemeClusters.split(surface)).map(::halfWidth)
        val rest = mutableListOf<String>()
        var next = 0
        for (run in surfaceRuns) {
            if (next < readingRuns.size && run == readingRuns[next]) next += 1 else rest += run
        }
        return if (next == readingRuns.size) rest else null
    }

    /** 表記が、読みをMozcで変換した全文節の候補の連結のどれかと一致するか。 */
    private fun surfaceIsMozcCandidate(reading: String, surface: String, mozc: List<ResultSegment>?): Boolean {
        if (mozc == null || mozc.joinToString(separator = "") { it.reading } != reading) return false
        return surface in combinations(mozc)
    }

    /** 読みの範囲[start, end)をMozcの文節がちょうど覆い、その候補の連結のどれかが[text]と一致するか。 */
    private fun backedByMozc(
        text: String,
        start: Int,
        end: Int,
        mozc: List<ResultSegment>,
        bounds: List<Pair<Int, Int>>,
    ): Boolean {
        val first = bounds.indexOfFirst { it.first == start }
        val last = bounds.indexOfFirst { it.second == end }
        if (first < 0 || last < first) return false
        return text in combinations(mozc.subList(first, last + 1))
    }

    /** 文節ごとに候補（表記を含む）を一つずつ選んで連結した文字列の集合。上限を超えた分は作らない。 */
    private fun combinations(segments: List<ResultSegment>): Set<String> {
        var result = listOf("")
        for (segment in segments) {
            val choices = (listOf(segment.surface) + segment.candidates).distinct()
            result = result.flatMap { prefix -> choices.map { prefix + it } }.take(MAX_COMBINATIONS)
        }
        return result.toSet()
    }

    /** 出力を、かな1文字ずつの単位と、かな以外の連なりの単位へ分ける。 */
    private fun outputUnits(clusters: List<String>): List<String> {
        val units = mutableListOf<String>()
        for (cluster in clusters) {
            if (!isKana(cluster) && units.isNotEmpty() && !isKana(units.last())) {
                units[units.lastIndex] = units.last() + cluster
            } else {
                units += cluster
            }
        }
        return units
    }

    /** 書記素の列から、数字・英字の連続する連なりを順に取り出す。 */
    private fun digitLetterRuns(clusters: List<String>): List<String> = runsOf(clusters, ::isDigitOrLetter)

    /** 文字列から数字の連なり（半角へ直したもの）を順に取り出す。 */
    private fun digitRuns(text: String): List<String> =
        runsOf(GraphemeClusters.split(text)) { cluster -> cluster.codePoints().allMatch(Character::isDigit) }.map(::halfWidth)

    /** 条件を満たす書記素の連続する連なりを取り出す。 */
    private fun runsOf(clusters: List<String>, matches: (String) -> Boolean): List<String> {
        val runs = mutableListOf<String>()
        var current = StringBuilder()
        for (cluster in clusters) {
            if (matches(cluster)) {
                current.append(cluster)
            } else if (current.isNotEmpty()) {
                runs += current.toString()
                current = StringBuilder()
            }
        }
        if (current.isNotEmpty()) runs += current.toString()
        return runs
    }

    /** 数字（全角・他の文字体系を含む）か、英字（半角・全角）だけの書記素か。 */
    private fun isDigitOrLetter(cluster: String): Boolean = cluster.isNotEmpty() && cluster.codePoints().allMatch {
        Character.isDigit(it) || it in 'A'.code..'Z'.code || it in 'a'.code..'z'.code ||
            it in 0xFF21..0xFF3A || it in 0xFF41..0xFF5A
    }

    /** かな（ひらがな、カタカナ、長音、繰り返し記号、結合用の濁点）だけの書記素か。変換器と同じ範囲を別に書く。 */
    private fun isKana(cluster: String): Boolean = cluster.isNotEmpty() && cluster.codePoints().allMatch {
        it in 0x3041..0x3096 || it in 0x3099..0x309F || it in 0x30A1..0x30FA || it in 0x30FC..0x30FF
    }

    /** カタカナをひらがなへそろえる。 */
    private fun hiragana(cluster: String): String = buildString {
        cluster.codePoints().forEach {
            appendCodePoint(if (it in 0x30A1..0x30F6 || it in 0x30FD..0x30FE) it - 0x60 else it)
        }
    }

    /** 全角の数字・英字を半角へ直す。 */
    private fun halfWidth(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
}
