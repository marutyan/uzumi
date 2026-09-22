package dev.uzumi.ime.editor

/**
 * 画面上で一つに扱う文字列のまとまりを、簡易的な書記素単位へ分割する。
 * AndroidのUTF-16 offsetと入力欄の削除範囲を混同しないために使う。
 */
object GraphemeClusters {
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val CARRIAGE_RETURN = 0x000D
    private const val LINE_FEED = 0x000A

    /**
     * 結合文字、異体字セレクタ、絵文字のZWJ列などを壊さずに分割する。
     */
    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var previousCodePoint = -1
        var regionalIndicatorCount = 0

        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val joinsCurrent = current.isNotEmpty() && (
                isCombining(codePoint) ||
                    isVariationSelector(codePoint) ||
                    isEmojiModifier(codePoint) ||
                    codePoint == ZERO_WIDTH_JOINER ||
                    previousCodePoint == ZERO_WIDTH_JOINER ||
                    (previousCodePoint == CARRIAGE_RETURN && codePoint == LINE_FEED) ||
                    (isRegionalIndicator(previousCodePoint) &&
                        isRegionalIndicator(codePoint) &&
                        regionalIndicatorCount % 2 == 1)
                )

            if (!joinsCurrent) {
                if (current.isNotEmpty()) result += current.toString()
                current.clear()
                regionalIndicatorCount = 0
            }

            current.appendCodePoint(codePoint)
            if (isRegionalIndicator(codePoint)) {
                regionalIndicatorCount += 1
            } else if (codePoint != ZERO_WIDTH_JOINER) {
                regionalIndicatorCount = 0
            }
            previousCodePoint = codePoint
            index += Character.charCount(codePoint)
        }

        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /**
     * 文字列末尾の書記素を削除するために必要なUTF-16幅を返す。
     */
    fun previousUtf16Length(text: CharSequence): Int {
        val clusters = split(text.toString())
        return clusters.lastOrNull()?.length ?: 0
    }

    /**
     * 書記素の個数から、対応するUTF-16 offsetを求める。
     */
    fun utf16Offset(clusters: List<String>, clusterCount: Int): Int {
        return clusters
            .take(clusterCount.coerceIn(0, clusters.size))
            .sumOf(String::length)
    }

    /**
     * UTF-16 offsetを、書記素境界上のカーソル位置へ変換する。
     */
    fun clusterIndexAtUtf16(text: String, offset: Int): Int? {
        if (offset !in 0..text.length) return null
        val clusters = split(text)
        var currentOffset = 0
        for ((index, cluster) in clusters.withIndex()) {
            if (offset == currentOffset) return index
            currentOffset += cluster.length
            if (offset < currentOffset) return null
            if (offset == currentOffset) return index + 1
        }
        return if (offset == currentOffset) clusters.size else null
    }

    private fun isCombining(codePoint: Int): Boolean {
        return when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true

            else -> false
        }
    }

    private fun isVariationSelector(codePoint: Int): Boolean {
        return codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF
    }

    private fun isEmojiModifier(codePoint: Int): Boolean {
        return codePoint in 0x1F3FB..0x1F3FF
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean {
        return codePoint in 0x1F1E6..0x1F1FF
    }
}
