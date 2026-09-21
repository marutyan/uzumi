package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * フリック方向判定ロジック [determineFlickDirection] のユニットテスト。
 */
class FlickDirectionTest {

    private val threshold = 20f

    @Test
    fun testCenterWhenWithinThreshold() {
        // 移動量0の場合はCENTER
        assertEquals(FlickDirection.CENTER, determineFlickDirection(0f, 0f, threshold))

        // 閾値未満の微小移動はCENTER
        assertEquals(FlickDirection.CENTER, determineFlickDirection(10f, 0f, threshold))
        assertEquals(FlickDirection.CENTER, determineFlickDirection(-10f, 0f, threshold))
        assertEquals(FlickDirection.CENTER, determineFlickDirection(0f, 10f, threshold))
        assertEquals(FlickDirection.CENTER, determineFlickDirection(0f, -10f, threshold))

        // 斜め移動で距離が閾値未満の場合
        assertEquals(FlickDirection.CENTER, determineFlickDirection(10f, 10f, threshold)) // distance = sqrt(200) ≈ 14.14 < 20
    }

    @Test
    fun testHorizontalDirections() {
        // 右方向フリック（dx > 0, |dx| > |dy|）
        assertEquals(FlickDirection.RIGHT, determineFlickDirection(25f, 0f, threshold))
        assertEquals(FlickDirection.RIGHT, determineFlickDirection(30f, 10f, threshold))
        assertEquals(FlickDirection.RIGHT, determineFlickDirection(30f, -10f, threshold))

        // 左方向フリック（dx < 0, |dx| > |dy|）
        assertEquals(FlickDirection.LEFT, determineFlickDirection(-25f, 0f, threshold))
        assertEquals(FlickDirection.LEFT, determineFlickDirection(-30f, 10f, threshold))
        assertEquals(FlickDirection.LEFT, determineFlickDirection(-30f, -10f, threshold))
    }

    @Test
    fun testVerticalDirections() {
        // 下方向フリック（dy > 0, |dy| >= |dx|）
        assertEquals(FlickDirection.DOWN, determineFlickDirection(0f, 25f, threshold))
        assertEquals(FlickDirection.DOWN, determineFlickDirection(10f, 30f, threshold))
        assertEquals(FlickDirection.DOWN, determineFlickDirection(-10f, 30f, threshold))

        // 上方向フリック（dy < 0, |dy| >= |dx|）
        assertEquals(FlickDirection.UP, determineFlickDirection(0f, -25f, threshold))
        assertEquals(FlickDirection.UP, determineFlickDirection(10f, -30f, threshold))
        assertEquals(FlickDirection.UP, determineFlickDirection(-10f, -30f, threshold))
    }

    @Test
    fun testDiagonalBoundaryCases() {
        // 45度境界線 (|dx| == |dy|かつ距離 >= threshold)
        // 設計ルール: |dx| <= |dy| は垂直方向優先（DOWN または UP）
        val valAt45 = 20f // distance = sqrt(800) ≈ 28.28 > 20

        // 右下 45度: DOWN
        assertEquals(FlickDirection.DOWN, determineFlickDirection(valAt45, valAt45, threshold))
        // 左下 45度: DOWN
        assertEquals(FlickDirection.DOWN, determineFlickDirection(-valAt45, valAt45, threshold))
        // 右上 45度: UP
        assertEquals(FlickDirection.UP, determineFlickDirection(valAt45, -valAt45, threshold))
        // 左上 45度: UP
        assertEquals(FlickDirection.UP, determineFlickDirection(-valAt45, -valAt45, threshold))

        // 45度からわずかにX方向が大きい場合: 水平判定
        assertEquals(FlickDirection.RIGHT, determineFlickDirection(valAt45 + 0.1f, valAt45, threshold))
        assertEquals(FlickDirection.LEFT, determineFlickDirection(-(valAt45 + 0.1f), valAt45, threshold))
    }

    @Test
    fun testInvalidOrZeroThreshold() {
        // 閾値が0または負の場合はCENTERを返す
        assertEquals(FlickDirection.CENTER, determineFlickDirection(10f, 10f, 0f))
        assertEquals(FlickDirection.CENTER, determineFlickDirection(10f, 10f, -5f))
    }
}
