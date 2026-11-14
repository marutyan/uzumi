package dev.uzumi.ime.voice

/**
 * 音声翻訳で英語へ訳した文から、独立した語の「Um」「Umm」「Uh」「Uhh」だけを取り除く。
 * 端末内の翻訳は話し手の言いよどみを英語のフィラーとして訳文へ残すため、入力欄へ入れる前に消す。
 * 「Umbrella」「drum」「Uh-oh」のように語の一部になっているものや、「you know」などの意味を持ちうる語は消さない。
 */
object EnglishFillerRemover {
    /**
     * 消すフィラーと、その前に付いた読点・空白、直後の読点をまとめて捉える。
     * 前後が文字・数字・アポストロフィ・ハイフンなら語の一部とみなして対象にしない（「Uh-oh」「drum」を守る）。
     * 直前の読点ごと消すのは、「is, um, about」を「is about」へ戻し、読点が二つ残るのを避けるため。
     */
    private val FILLER = Regex(
        """(?:\s*,)?\s*(?<![\p{L}\p{N}'’-])(?:umm?|uhh?)(?![\p{L}\p{N}'’-]),?""",
        RegexOption.IGNORE_CASE,
    )

    /** 文の終わりを示す記号。フィラーがこの直後（または文頭）にあった場合、残った文の先頭を大文字にする。 */
    private val SENTENCE_END_CHARS = setOf('.', '!', '?')

    /** [text]からフィラーを取り除いた文字列を返す。フィラーが無ければ[text]をそのまま返す。 */
    fun removeFillers(text: String): String {
        if (FILLER.find(text) == null) return text
        val result = StringBuilder()
        var cursor = 0
        // 直前に消したフィラーが文頭にあったか。次に残す文字列の最初の英字を大文字にするために持つ。
        var capitalizeNext = false
        for (match in FILLER.findAll(text)) {
            capitalizeNext = appendKept(result, text.substring(cursor, match.range.first), capitalizeNext)
            capitalizeNext = capitalizeNext || isSentenceStart(result)
            cursor = match.range.last + 1
        }
        appendKept(result, text.substring(cursor), capitalizeNext)
        return result.toString().trim()
    }

    /**
     * 残す部分[kept]を[out]へ足す。[capitalize]なら最初の英字を大文字にし、足し終えた時点でまだ大文字化が
     * 済んでいない（英字が無かった）かを返す。文頭なら、フィラーの後に残った先頭の空白も落とす。
     */
    private fun appendKept(out: StringBuilder, kept: String, capitalize: Boolean): Boolean {
        val body = if (out.isEmpty()) kept.trimStart() else kept
        if (!capitalize) {
            out.append(body)
            return false
        }
        val index = body.indexOfFirst(Char::isLetter)
        if (index < 0) {
            out.append(body)
            return true
        }
        out.append(body, 0, index).append(body[index].uppercaseChar()).append(body, index + 1, body.length)
        return false
    }

    /** ここまでに残した文字列[kept]の末尾が文頭（空、または文末記号の後）にあたるかを返す。 */
    private fun isSentenceStart(kept: CharSequence): Boolean {
        val last = kept.trimEnd().lastOrNull() ?: return true
        return last in SENTENCE_END_CHARS
    }
}
