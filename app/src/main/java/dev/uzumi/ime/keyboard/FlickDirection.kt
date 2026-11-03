package dev.uzumi.ime.keyboard

import kotlin.math.abs

/**
 * フリック操作の方向を表す列挙型。
 */
enum class FlickDirection {
    /** 移動量が閾値未満（中心タップ） */
    CENTER,
    /** 左方向フリック */
    LEFT,
    /** 上方向フリック */
    UP,
    /** 右方向フリック */
    RIGHT,
    /** 下方向フリック */
    DOWN,
}

/**
 * フリックを中心タップと区別する範囲の形。円は今までのUzumiの判定、縦横はSimejiと同じく縦と横を別々に見る判定。
 */
enum class FlickJudge {
    /** 押した位置からの距離が閾値未満なら中心とみなす（既定）。 */
    CIRCLE,

    /** 縦と横の移動量がどちらも閾値以下なら中心とみなす。斜めに動かしたときは円より早く方向が決まらない。 */
    AXIS,
}

/**
 * 移動変位 (dx, dy) と閾値からフリック方向を判定する純粋ロジック。
 *
 * 斜めの境界は45度（|dx| と |dy| の比較）で決定され、
 * |dx| == |dy| の境界では垂直方向（UP または DOWN）として一意に判定する。
 *
 * @param dx X方向の変位量（右が正、左が負）
 * @param dy Y方向の変位量（画面座標系のため下が正、上が負）
 * @param thresholdPx フリックとみなす最小移動距離（ピクセル単位）
 * @param judge 中心とみなす範囲の形。既定は円
 * @return 判定された [FlickDirection]
 */
fun determineFlickDirection(
    dx: Float,
    dy: Float,
    thresholdPx: Float,
    judge: FlickJudge = FlickJudge.CIRCLE,
): FlickDirection {
    if (thresholdPx <= 0f) {
        return FlickDirection.CENTER
    }
    val absX = abs(dx)
    val absY = abs(dy)
    val isCenter = when (judge) {
        FlickJudge.CIRCLE -> dx * dx + dy * dy < thresholdPx * thresholdPx
        FlickJudge.AXIS -> absX <= thresholdPx && absY <= thresholdPx
    }
    if (isCenter) {
        return FlickDirection.CENTER
    }

    return if (absX > absY) {
        if (dx > 0f) FlickDirection.RIGHT else FlickDirection.LEFT
    } else {
        if (dy > 0f) FlickDirection.DOWN else FlickDirection.UP
    }
}
