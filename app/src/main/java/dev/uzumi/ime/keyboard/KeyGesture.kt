package dev.uzumi.ime.keyboard

/**
 * 一つのキー押下について、長押しの成立と取り消しをAndroidのViewから切り離して判定する。
 * 指のずれやフリックを長押しと誤認しない規則を一か所に置き、JVMテストで守るために使う。
 *
 * @param slopPx 押下開始点からこの距離以上動いたら長押しを成立させない閾値（ピクセル単位）
 */
class KeyGestureState(private val slopPx: Float) {
    /** 押下開始時のX座標。フリック方向の基準に使う。 */
    var startX = 0f
        private set

    /** 押下開始時のY座標。フリック方向の基準に使う。 */
    var startY = 0f
        private set

    /** キーが押下中かどうか。 */
    var isPressed = false
        private set

    // 押下開始点からslop以上動いたことが一度でもあるか。指が戻っても長押しは成立させない。
    var hasMovedBeyondSlop = false
        private set

    /** 長押しが成立し、離したときに長押し側の入力を行う状態かどうか。 */
    var isLongPressActive = false
        private set

    /** 押下開始を記録し、前回の押下状態を捨てる。 */
    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        isPressed = true
        hasMovedBeyondSlop = false
        isLongPressActive = false
    }

    /** 指の移動を記録する。長押し成立後の移動は長押しを取り消さない。 */
    fun move(x: Float, y: Float) {
        if (!isPressed || isLongPressActive) return
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy >= slopPx * slopPx) {
            hasMovedBeyondSlop = true
        }
    }

    /**
     * 長押し時間の経過を通知する。押下中でslopを超えて動いていない場合だけ長押しを成立させ、trueを返す。
     */
    fun longPressTimeout(): Boolean {
        if (!isPressed || hasMovedBeyondSlop || isLongPressActive) return false
        isLongPressActive = true
        return true
    }

    /** 押下状態をすべて解除する。離す、取り消し、モード切替のいずれでも使う。 */
    fun reset() {
        isPressed = false
        hasMovedBeyondSlop = false
        isLongPressActive = false
    }
}

/**
 * 文字キーを離したときに入力する文字列を決める。キー外で離した場合は入力しない。
 * 長押しが成立していて長押し文字がある場合だけ長押し文字を返し、通常のタップと区別する。
 */
fun resolveTextKeyRelease(
    text: String,
    longPressText: String?,
    isInside: Boolean,
    isLongPressActive: Boolean,
): String? {
    if (!isInside) return null
    return if (isLongPressActive && longPressText != null) longPressText else text
}

/**
 * 削除・カーソルキーの連続実行の時間を決める。押下直後に一回実行し、一定時間後から繰り返す。
 * 加速は控えめにし、指を止める前に削除しすぎないよう最短間隔を設ける。
 */
object KeyRepeatPolicy {
    /** 押下から最初の繰り返しまでの待ち時間（ミリ秒）。 */
    const val INITIAL_DELAY_MS = 400L

    /** 繰り返し開始直後の間隔（ミリ秒）。 */
    const val START_INTERVAL_MS = 100L

    /** 加速後の最短間隔（ミリ秒）。 */
    const val MIN_INTERVAL_MS = 50L

    /** 開始間隔から最短間隔へ到達するまでの繰り返し回数。 */
    const val ACCELERATION_STEPS = 20

    /**
     * 押下中に連続実行するアクションかどうかを返す。
     */
    fun isRepeatable(action: KeyboardAction): Boolean {
        return action is KeyboardAction.Delete || action is KeyboardAction.MoveCursor
    }

    /**
     * 繰り返しを[repeatCount]回実行した後、次の実行までの間隔を返す。
     * 開始間隔から最短間隔まで回数に比例して短くし、その後は最短間隔を保つ。
     */
    fun intervalAfter(repeatCount: Int): Long {
        val step = repeatCount.coerceIn(0, ACCELERATION_STEPS)
        val reduction = (START_INTERVAL_MS - MIN_INTERVAL_MS) * step / ACCELERATION_STEPS
        return START_INTERVAL_MS - reduction
    }
}
