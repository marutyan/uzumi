package dev.uzumi.ime.editor

/**
 * 「小゛゜」キーで使う、かな一文字の小文字・濁点・半濁点変換を提供する。
 */
object KanaModifier {
    // 一つのキーを繰り返し押したときに巡回するひらがなの組を定義する。
    private val hiraganaCycles = listOf(
        listOf("あ", "ぁ"),
        listOf("い", "ぃ"),
        listOf("う", "ぅ", "ゔ"),
        listOf("え", "ぇ"),
        listOf("お", "ぉ"),
        listOf("か", "が"),
        listOf("き", "ぎ"),
        listOf("く", "ぐ"),
        listOf("け", "げ"),
        listOf("こ", "ご"),
        listOf("さ", "ざ"),
        listOf("し", "じ"),
        listOf("す", "ず"),
        listOf("せ", "ぜ"),
        listOf("そ", "ぞ"),
        listOf("た", "だ"),
        listOf("ち", "ぢ"),
        listOf("つ", "っ", "づ"),
        listOf("て", "で"),
        listOf("と", "ど"),
        listOf("は", "ば", "ぱ"),
        listOf("ひ", "び", "ぴ"),
        listOf("ふ", "ぶ", "ぷ"),
        listOf("へ", "べ", "ぺ"),
        listOf("ほ", "ぼ", "ぽ"),
        listOf("や", "ゃ"),
        listOf("ゆ", "ゅ"),
        listOf("よ", "ょ"),
        listOf("わ", "ゎ"),
    )

    // ひらがなの組と、対応するカタカナの組。
    private val allCycles = hiraganaCycles + hiraganaCycles.map { cycle ->
        cycle.map(BasicCandidateProvider::toKatakana)
    }

    // ひらがなと対応するカタカナの両方を次の表記へ写像する。
    private val replacements = buildMap {
        allCycles.forEach { cycle ->
            cycle.forEachIndexed { index, value ->
                put(value, cycle[(index + 1) % cycle.size])
            }
        }
    }

    // 組の各かなを、組の先頭（小書き・濁点・半濁点の付かないかな）へ写像する。どのかなも1文字（1 UTF-16単位）である。
    private val bases: Map<Char, Char> = buildMap {
        allCycles.forEach { cycle -> cycle.forEach { value -> put(value.single(), cycle.first().single()) } }
    }

    /** 対応する次のかなを返し、対象外の文字ではnullを返す。 */
    fun transform(value: String): String? = replacements[value]

    /** 小書き・濁点・半濁点の違いを除いた表記へ揃える。打ち分けの違う読み同士を照合するために使う。 */
    fun stripModifiers(text: String): String = buildString(text.length) {
        text.forEach { append(bases[it] ?: it) }
    }
}
