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
 * 空白・変換キー上の横ドラッグを、書記素単位のカーソル移動へ変換する。
 * 最初の移動距離を越えた後は、開始点からの距離に応じて方向反転も差分として返す。
 */
class SpaceCursorDragTracker(
    private val startThresholdPx: Float,
    private val stepPx: Float,
) {
    private var startX = 0f
    private var startY = 0f
    private var lastSteps = 0

    /** DOWNからの横ドラッグが開始したか。開始後に指を戻してもtrueを保つ。 */
    var hasDragged = false
        private set

    /** 押下とドラッグ追跡中か。 */
    var isTracking = false
        private set

    /** 押下開始位置を記録する。 */
    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        lastSteps = 0
        hasDragged = false
        isTracking = true
    }

    /** 移動イベントの差分を符号付きの書記素数で返す。縦優位の動きでは開始しない。 */
    fun move(x: Float, y: Float): Int {
        if (!isTracking) return 0
        val dx = x - startX
        val dy = y - startY
        if (!hasDragged) {
            if (kotlin.math.abs(dx) < startThresholdPx || kotlin.math.abs(dx) <= kotlin.math.abs(dy)) return 0
            hasDragged = true
        }

        val distance = kotlin.math.abs(dx)
        val steps = if (distance < startThresholdPx) {
            0
        } else {
            1 + ((distance - startThresholdPx) / stepPx).toInt()
        }
        val signedSteps = if (dx < 0f) -steps else steps
        val delta = signedSteps - lastSteps
        lastSteps = signedSteps
        return delta
    }

    /** UP時にドラッグだったかを返し、次の入力のために状態を消す。 */
    fun finish(): Boolean {
        val dragged = hasDragged
        reset()
        return dragged
    }

    /** CANCEL、ポインタ交代、キー定義の変更で追跡状態を消す。 */
    fun reset() {
        hasDragged = false
        isTracking = false
        lastSteps = 0
    }
}

/** 横ドラッグが始まらず、キー内で離したときだけ従来の空白・変換タップを送る。 */
fun shouldSendSpaceCursorKeyTap(isInside: Boolean, dragWasActive: Boolean): Boolean = isInside && !dragWasActive

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
     * 長押しの別操作（文節の伸縮）を繰り返す間隔（ミリ秒）。一回ごとに変換し直すため、
     * カーソル移動より遅くして一文字ずつ見て止められるようにする。
     */
    const val LONG_PRESS_ACTION_INTERVAL_MS = 300L

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

/**
 * 削除キーを左へドラッグしたときの見た目の状態。キーボードは状態に応じて削除キーの左に案内を出す。
 */
enum class DeleteDragState {
    /** ドラッグしていない。案内を出さない。 */
    NONE,

    /** 左へ動かしているが閾値の手前。離すと1文字だけ消える。 */
    DRAGGING,

    /** 閾値を超えた。離すとカーソルから行頭までを消す。 */
    ARMED,

    /** キーボードの上へ外した、または閾値を超えた後に戻した。離しても何も消さない。 */
    CANCELED,
}

/**
 * 削除キーを離したときに行う操作。
 */
enum class DeleteRelease {
    /** ドラッグしなかった。通常の削除として扱う（長押しの連続削除が始まっていれば何もしない）。 */
    TAP,

    /** 閾値の手前で離した。1文字だけ消す。 */
    SINGLE,

    /** 閾値を超えて離した。カーソルから行頭までを消す。 */
    LINE,

    /** 取り消した。何も消さない。 */
    CANCEL,
}

/**
 * 削除キーの左ドラッグを判定する。Simejiの実機での挙動（約70dpで行削除、元の位置へ戻す・上へ外すと取り消し）に合わせ、
 * Viewから切り離してJVMテストで守る。
 *
 * @param slopPx この距離より左へ動いたらドラッグとみなす（ピクセル）
 * @param thresholdPx この距離以上左で離すと行頭まで消す（ピクセル）
 * @param escapePx 押した位置からこの距離以上上へ動かしたら取り消す（ピクセル）
 */
class DeleteDragTracker(
    private val slopPx: Float,
    private val thresholdPx: Float,
    private val escapePx: Float,
) {
    /** 左へのドラッグが始まったか。始まると長押しの連続削除は行わない。 */
    var isDragging = false
        private set

    // 一度でも閾値を超えたか。超えた後に閾値の手前へ戻して離した場合は取り消しとする。
    private var wasArmed = false

    /** 現在の見た目の状態。 */
    var state = DeleteDragState.NONE
        private set

    /** 押下開始で状態を捨てる。 */
    fun reset() {
        isDragging = false
        wasArmed = false
        state = DeleteDragState.NONE
    }

    /**
     * 押した位置からの移動量を渡す。[leftPx]は左向きを正、[upPx]は上向きを正とする。状態が変わったらtrueを返す。
     */
    fun move(leftPx: Float, upPx: Float): Boolean {
        if (!isDragging && leftPx > slopPx) isDragging = true
        if (!isDragging) return false
        val next = when {
            upPx >= escapePx -> DeleteDragState.CANCELED
            leftPx >= thresholdPx -> DeleteDragState.ARMED
            wasArmed -> DeleteDragState.CANCELED
            else -> DeleteDragState.DRAGGING
        }
        if (next == DeleteDragState.ARMED) wasArmed = true
        val changed = next != state
        state = next
        return changed
    }

    /** 離したときの操作を返す。 */
    fun release(): DeleteRelease = when {
        !isDragging -> DeleteRelease.TAP
        state == DeleteDragState.ARMED -> DeleteRelease.LINE
        state == DeleteDragState.DRAGGING -> DeleteRelease.SINGLE
        else -> DeleteRelease.CANCEL
    }
}
