package dev.uzumi.ime.voice

/**
 * 音声で入れる文の前に空白が要るかを決める。英訳を続けて入れると「...later.Tomorrow's」のように
 * 前の文とつながってしまうため、英数字で始まる文を、空白でない英数字や英文の句読点の直後へ入れるときだけ空白を足す。
 * 日本語の文や、前が日本語・空白・行頭のときは何も足さない。
 */
object VoiceTextSpacing {
    /** 英文の句読点。この直後に英数字の文を入れるときは空白を足す。 */
    private const val ENGLISH_PUNCTUATION = ".,!?;:)\"'"

    /** [previous]（カーソル直前の1文字、無ければnull）の後ろへ[text]を入れるときに前へ足す文字列を返す。 */
    fun leadingSeparator(previous: Char?, text: String): String {
        val first = text.firstOrNull() ?: return ""
        if (previous == null || !isAsciiAlphanumeric(first)) return ""
        return if (isAsciiAlphanumeric(previous) || previous in ENGLISH_PUNCTUATION) " " else ""
    }

    private fun isAsciiAlphanumeric(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}
