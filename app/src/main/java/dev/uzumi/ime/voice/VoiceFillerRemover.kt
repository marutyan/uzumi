package dev.uzumi.ime.voice

/**
 * 端末内音声認識の確定テキストから、境界が明確なフィラーだけを取り除く。
 * 言い直しの反映や、意味のある語と区別できない「あの」「まあ」「なんか」の除去は行わない。
 */
object VoiceFillerRemover {
    /** 除去対象のフィラー。最長一致させるため長い順に並べる。 */
    private val FILLERS = listOf(
        "えーっと",
        "ええっと",
        "えっと",
        "えーと",
        "あのー",
        "あのう",
        "えと",
        "えー",
        "え",
    )

    /** フィラーそのものを話題にしている後ろの語。直後に続く場合はフィラーを消さない。 */
    private val TOPIC_SUFFIXES = listOf(
        "とか",
        "みたいな",
        "っていう",
        "という",
        "って",
        "と言",
        "と呼",
    )

    /** 読点を一つも残さずに消してよい直前文字（句読点・開き括弧・空白）。 */
    private val CLAUSE_BOUNDARY_CHARS = setOf(
        '、', '。', '，', '．', ',', '.', '？', '！', '?', '!', '：', ':', '；', ';',
        '「', '『', '（', '(', '【', '［', '[', '・', '…', '―',
        '\n', '\r', '\t', ' ', '　',
    )

    /**
     * 入力文[text]から規則に合うフィラーを取り除いた文字列を返す。
     */
    fun removeFillers(text: String): String {
        if (text.isEmpty()) return text
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val matched = findFillerAt(text, index)
            if (matched == null) {
                result.append(text[index])
                index++
                continue
            }
            var nextIndex = index + matched.length
            var hasTrailingComma = false
            while (nextIndex < text.length && text[nextIndex] == '、') {
                hasTrailingComma = true
                nextIndex++
            }
            if (hasTrailingComma && shouldKeepSeparatorComma(result)) {
                result.append('、')
            }
            index = nextIndex
        }
        return result.toString()
    }

    /**
     * [index]から始まる除去対象フィラーを返す。話題としてのフィラーや語の一部の場合はnullを返す。
     */
    private fun findFillerAt(text: String, index: Int): String? {
        // 例：「えとか」で「えと」が先に一致する誤除去を防ぐため、どのフィラー候補でも話題接尾辞が続くなら残す。
        if (startsWithTopicMention(text, index)) return null
        for (filler in FILLERS) {
            if (!text.startsWith(filler, index)) continue
            if (isValidFillerBoundary(text, index, filler)) return filler
        }
        return null
    }

    /** [index]の位置が「フィラー語＋話題の接尾辞」の並びで始まるかを返す。 */
    private fun startsWithTopicMention(text: String, index: Int): Boolean {
        for (filler in FILLERS) {
            if (!text.startsWith(filler, index)) continue
            val after = index + filler.length
            if (TOPIC_SUFFIXES.any { suffix -> text.startsWith(suffix, after) }) {
                return true
            }
        }
        return false
    }

    /**
     * フィラーの直前に来てもよいひらがな。認識結果で「え」の前に現れる助詞と語尾に限る。
     * これ以外のひらがなの後ろの「え」は、「いえ」「まえ」「さえ」「ねえ」「へえ」のように語の一部とみなして残す。
     */
    private val HIRAGANA_BEFORE_FILLER = setOf(
        'と', 'は', 'が', 'を', 'に', 'で', 'も', 'の', 'ん', 'す', 'て', 'た', 'よ', 'ど', 'ば',
    )

    /** ひらがなで終わり、後ろのひらがなと続けて一語になりうるフィラー（「まえに」「えとく」「あのうどん」）。 */
    private val OPEN_ENDED_FILLERS = setOf("え", "えと", "あのう")

    /**
     * [start]から始まる[filler]が、語の一部や相づちではなくフィラーの境界にあるかを判定する。
     * 判別できない並びは残す（誤って消すと、利用者が気づかないまま文が壊れて入力されるため）。
     */
    private fun isValidFillerBoundary(text: String, start: Int, filler: String): Boolean {
        val end = start + filler.length
        val prev = if (start > 0) text[start - 1] else null
        val next = if (end < text.length) text[end] else null
        // 長音や促音が続く場合は語の一部とみなし切り取らない。
        if (next == 'ー' || next == 'っ') return false
        // 「「えっと」を消す」のように括弧で囲まれた語は、言葉そのものを書いている。
        if (next == '」' || next == '』') return false
        if (filler in OPEN_ENDED_FILLERS && next != null && isHiragana(next)) return false
        if (filler.startsWith('え') && prev != null) {
            // 直前が漢字なら「考え」「答えと」などの送り仮名として残す。
            if (isKanji(prev)) return false
            if (isHiragana(prev) && prev !in HIRAGANA_BEFORE_FILLER) return false
        }
        return true
    }

    /**
     * フィラー直後の読点を1つ残すべきか（文の途中で直前に句読点でない文字があるか）を返す。
     */
    private fun shouldKeepSeparatorComma(preceding: CharSequence): Boolean {
        if (preceding.isEmpty()) return false
        return preceding[preceding.length - 1] !in CLAUSE_BOUNDARY_CHARS
    }

    /** ひらがなかどうかを返す。長音記号「ー」は含めない。 */
    private fun isHiragana(c: Char): Boolean =
        c in '\u3041'..'\u3096' || c == '\u309D' || c == '\u309E'

    /** 漢字（CJK統合漢字・拡張A・踊り字「々」）かどうかを返す。 */
    private fun isKanji(c: Char): Boolean =
        c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c == '\u3005'
}
