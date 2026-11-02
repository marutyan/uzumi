package dev.uzumi.ime.editor

/**
 * 12キーの同じかなキーを続けてタップしたとき、直前に入れた一文字を同じ行の次の文字へ差し替えるかを決める
 * （あ→い→う→え→お→あ）。時間が空く、別のキーや操作が入る、入力位置が変わると、次のタップは新しい文字になる。
 * 時刻は呼び出し側から受け取り、JVMテストで区切りを確かめられるようにする。
 */
class KanaKeyToggle {
    // 直前のタップのキーの文字列（同じキーかどうかの照合に使う）、選んだ位置、時刻、入力後の入力位置。
    private var lastValues: List<String>? = null
    private var lastIndex = 0
    private var lastAtMillis = 0L
    private var lastCursor = -1

    /**
     * 今回のタップで入れる文字を決める。続きなら[KanaToggleStep.previous]に差し替える前の文字が入る。
     * [cursor]は入力前のcomposition上の入力位置で、直前のタップ後の位置と違えば続きにしない。
     */
    fun next(values: List<String>, nowMillis: Long, cursor: Int): KanaToggleStep {
        val previous = lastValues
        val continues = previous == values && values.size > 1 && cursor == lastCursor && isWithinWindow(nowMillis)
        if (!continues) return KanaToggleStep(previous = null, value = values.first(), index = 0)
        val index = (lastIndex + 1) % values.size
        return KanaToggleStep(previous = values[lastIndex], value = values[index], index = index)
    }

    /** 入力が反映された後に呼び、次のタップの照合に使う状態を記録する。[cursorAfter]は反映後の入力位置。 */
    fun accept(values: List<String>, step: KanaToggleStep, nowMillis: Long, cursorAfter: Int) {
        lastValues = values
        lastIndex = step.index
        lastAtMillis = nowMillis
        lastCursor = cursorAfter
    }

    /** 次のタップが続きになりうる状態か。→キーを「次の文字へ進む」として使うかの判定に使う。 */
    fun isActive(nowMillis: Long): Boolean = lastValues != null && isWithinWindow(nowMillis)

    /** 続きとして扱わないよう状態を消す。別のキーや操作が入ったときに呼ぶ。 */
    fun reset() {
        lastValues = null
        lastIndex = 0
        lastAtMillis = 0L
        lastCursor = -1
    }

    /** 直前のタップから猶予時間内か。時計が戻った場合は続きにしない。 */
    private fun isWithinWindow(nowMillis: Long): Boolean =
        nowMillis >= lastAtMillis && nowMillis - lastAtMillis <= WINDOW_MILLIS

    companion object {
        /** 同じキーのタップを同じ文字の切り替えとして扱う猶予（ミリ秒）。Gboardなどと同程度の約1秒にする。 */
        const val WINDOW_MILLIS = 1_000L
    }
}

/** 12キーの一回のタップで入れる文字。[previous]がnullなら新しい文字として追加し、そうでなければ差し替える。 */
data class KanaToggleStep(val previous: String?, val value: String, val index: Int)
