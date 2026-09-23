package dev.uzumi.ime.live

/**
 * ライブ変換の境界と安定化の仮値を一か所に置く。
 * 値はUX試験で決める仮値であり、信頼度の校正済み推定ではない。
 */
object LiveConversionRules {
    /** stableへ昇格するために、同じ表記・読み範囲を観測する必要がある読みrevisionの数。 */
    const val STABLE_OBSERVATION_COUNT = 3

    /** 入力すると、この文字を含む現在の表示を確定する句読点。 */
    val TERMINAL_PUNCTUATION: Set<String> = setOf("。", "！", "？")

    /** 入力すると、直前までを保護するがcompositionは残す読点。 */
    const val SOFT_BOUNDARY = "、"
}
