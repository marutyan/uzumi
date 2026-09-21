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
 * 移動変位 (dx, dy) と閾値からフリック方向を判定する純粋ロジック。
 *
 * 斜めの境界は45度（|dx| と |dy| の比較）で決定され、
 * |dx| == |dy| の境界では垂直方向（UP または DOWN）として一意に判定する。
 *
 * @param dx X方向の変位量（右が正、左が負）
 * @param dy Y方向の変位量（画面座標系のため下が正、上が負）
 * @param thresholdPx フリックとみなす最小移動距離（ピクセル単位）
 * @return 判定された [FlickDirection]
 */
fun determineFlickDirection(dx: Float, dy: Float, thresholdPx: Float): FlickDirection {
    if (thresholdPx <= 0f) {
        return FlickDirection.CENTER
    }
    val distanceSq = dx * dx + dy * dy
    if (distanceSq < thresholdPx * thresholdPx) {
        return FlickDirection.CENTER
    }

    val absX = abs(dx)
    val absY = abs(dy)

    return if (absX > absY) {
        if (dx > 0f) FlickDirection.RIGHT else FlickDirection.LEFT
    } else {
        if (dy > 0f) FlickDirection.DOWN else FlickDirection.UP
    }
}
