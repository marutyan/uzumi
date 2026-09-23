package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun longPressIsAcceptedOnlyWhileHeldWithoutMoving() {
        val gesture = KeyGestureState(slopPx = 8f)
        // 押下前の時間経過では成立しない
        assertFalse(gesture.longPressTimeout())

        gesture.down(50f, 50f)
        gesture.move(53f, 54f) // 距離5はslop未満
        assertTrue(gesture.longPressTimeout())
        assertTrue(gesture.isLongPressActive)
        // 二度目の通知では再成立しない
        assertFalse(gesture.longPressTimeout())

        // 長押し成立後は指がずれても取り消さない
        gesture.move(90f, 90f)
        assertTrue(gesture.isLongPressActive)
    }

    @Test
    fun movingBeyondSlopCancelsLongPressEvenIfFingerReturns() {
        val gesture = KeyGestureState(slopPx = 8f)
        gesture.down(50f, 50f)
        gesture.move(50f, 30f) // フリックのように上へ動かす
        gesture.move(50f, 50f) // 元の位置へ戻す
        assertTrue(gesture.hasMovedBeyondSlop)
        assertFalse(gesture.longPressTimeout())
        assertFalse(gesture.isLongPressActive)
    }

    @Test
    fun newPressAndResetClearPreviousLongPress() {
        val gesture = KeyGestureState(slopPx = 8f)
        gesture.down(0f, 0f)
        assertTrue(gesture.longPressTimeout())
        gesture.down(10f, 10f)
        assertFalse(gesture.isLongPressActive)
        assertEquals(10f, gesture.startX)
        assertEquals(10f, gesture.startY)

        gesture.reset()
        assertFalse(gesture.isPressed)
        assertFalse(gesture.longPressTimeout())
    }

    @Test
    fun textKeyReleaseSeparatesTapLongPressAndOutsideRelease() {
        assertEquals("q", resolveTextKeyRelease("q", "1", isInside = true, isLongPressActive = false))
        assertEquals("1", resolveTextKeyRelease("q", "1", isInside = true, isLongPressActive = true))
        // キー外で離すと、長押し成立後でも入力しない（取り消し手段）
        assertNull(resolveTextKeyRelease("q", "1", isInside = false, isLongPressActive = true))
        assertNull(resolveTextKeyRelease("q", "1", isInside = false, isLongPressActive = false))
        // 長押し文字を持たないキーは長押し後も通常の文字を入力する
        assertEquals(",", resolveTextKeyRelease(",", null, isInside = true, isLongPressActive = true))
    }

    @Test
    fun repeatStartsAfterInitialDelayAndAcceleratesModestly() {
        assertEquals(400L, KeyRepeatPolicy.INITIAL_DELAY_MS)
        assertEquals(KeyRepeatPolicy.START_INTERVAL_MS, KeyRepeatPolicy.intervalAfter(0))
        assertEquals(100L, KeyRepeatPolicy.intervalAfter(0))
        assertEquals(75L, KeyRepeatPolicy.intervalAfter(10))
        assertEquals(50L, KeyRepeatPolicy.intervalAfter(20))
        assertEquals(50L, KeyRepeatPolicy.intervalAfter(1000))
        // 間隔は回数とともに短くなるだけで、逆転しない
        (0 until 30).forEach { count ->
            assertTrue(KeyRepeatPolicy.intervalAfter(count + 1) <= KeyRepeatPolicy.intervalAfter(count))
        }
        // 押し始めから約1秒で削除される回数（初回を含む）が過大にならないことを確かめる
        var elapsed = KeyRepeatPolicy.INITIAL_DELAY_MS
        var deletions = 1
        while (elapsed <= 1000L) {
            deletions += 1
            elapsed += KeyRepeatPolicy.intervalAfter(deletions - 1)
        }
        assertTrue("deletions=$deletions", deletions in 6..9)
    }

    @Test
    fun onlyDeleteAndCursorKeysRepeat() {
        assertTrue(KeyRepeatPolicy.isRepeatable(KeyboardAction.Delete))
        assertTrue(KeyRepeatPolicy.isRepeatable(KeyboardAction.MoveCursor(-1)))
        assertTrue(KeyRepeatPolicy.isRepeatable(KeyboardAction.MoveCursor(1)))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Enter))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Space))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Convert))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.TransformKana))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Text("あ")))
    }
}
