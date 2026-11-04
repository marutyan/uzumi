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
    fun spaceCursorDragMovesByDistanceAndReversesOneGraphemeAtATime() {
        val drag = SpaceCursorDragTracker(startThresholdPx = 18f, stepPx = 24f)
        drag.down(100f, 50f)

        assertEquals(0, drag.move(83f, 50f))
        assertEquals(-1, drag.move(82f, 50f))
        assertEquals(-1, drag.move(58f, 50f))
        assertEquals(-1, drag.move(34f, 50f))
        assertEquals(1, drag.move(58f, 50f))
        assertEquals(1, drag.move(82f, 50f))
        // 開始点まで戻っても一度ドラッグした事実は残り、空白・変換タップを送らない。
        assertEquals(1, drag.move(100f, 50f))
        assertTrue(drag.finish())
        assertFalse(drag.isTracking)
        assertFalse(shouldSendSpaceCursorKeyTap(isInside = true, dragWasActive = true))
    }

    @Test
    fun spaceCursorDragIgnoresVerticalOnlyMovementAndKeepsTap() {
        val drag = SpaceCursorDragTracker(startThresholdPx = 18f, stepPx = 24f)
        drag.down(50f, 50f)

        assertEquals(0, drag.move(52f, 80f))
        assertFalse(drag.finish())
        assertTrue(shouldSendSpaceCursorKeyTap(isInside = true, dragWasActive = false))
        assertFalse(shouldSendSpaceCursorKeyTap(isInside = false, dragWasActive = false))
    }

    @Test
    fun spaceCursorDragUsesUpPositionWhenMoveWasNotDeliveredAndResetCancelsTracking() {
        val drag = SpaceCursorDragTracker(startThresholdPx = 18f, stepPx = 24f)
        drag.down(100f, 50f)
        // ACTION_DOWNの後にACTION_UPだけ届いた場合も、UP座標をmoveとして扱える。
        assertEquals(-2, drag.move(58f, 50f))
        assertTrue(drag.finish())

        drag.down(100f, 50f)
        assertEquals(-1, drag.move(82f, 50f))
        drag.reset() // ACTION_CANCEL、別ポインタ、replaceSpec、detach
        assertEquals(0, drag.move(58f, 50f))
        assertFalse(drag.finish())
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
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.MoveEditorCursor(1)))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Enter))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Space))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Convert))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.TransformKana))
        assertFalse(KeyRepeatPolicy.isRepeatable(KeyboardAction.Text("あ")))
    }
    /**
     * 押下順の共有を、キーごとの押下中フラグと送った文字の列で再現する。
     * 押したときに古い文字キーを確定し、確定済みのキーは離しても何も送らない。
     */
    private class TwoFingerTyping(private val textKeys: Set<String>, private val modifiers: Set<String> = emptySet()) {
        private val order = KeyPressOrder<String>()
        private val pressed = mutableSetOf<String>()
        val sent = mutableListOf<String>()

        fun down(key: String) {
            order.press(key, isModifier = key in modifiers, isTextKey = { it in pressed && it in textKeys })
                .forEach(::commit)
            pressed += key
        }

        fun up(key: String) {
            order.release(key)
            commit(key)
        }

        private fun commit(key: String) {
            if (pressed.remove(key)) sent += key
        }
    }

    @Test
    fun overlappingTapsKeepPressOrderWhenNewerFingerReleasesFirst() {
        // aを押したままbを押し、bを先に離しても「ab」の順になる
        val typing = TwoFingerTyping(textKeys = setOf("a", "b"))
        typing.down("a")
        typing.down("b")
        assertEquals(listOf("a"), typing.sent)
        typing.up("b")
        typing.up("a")
        assertEquals(listOf("a", "b"), typing.sent)
    }

    @Test
    fun overlappingTapsReleasedInPressOrderAreUnchanged() {
        val typing = TwoFingerTyping(textKeys = setOf("a", "b", "c"))
        typing.down("a")
        typing.down("b")
        typing.down("c")
        typing.up("a")
        typing.up("c")
        typing.up("b")
        assertEquals(listOf("a", "b", "c"), typing.sent)
    }

    @Test
    fun olderTextKeyCommitsBeforeSpaceOrDeleteButNotBeforeShift() {
        // 空白や削除の前に、押したままの文字を先に入力する
        val typing = TwoFingerTyping(textKeys = setOf("o"), modifiers = setOf("shift"))
        typing.down("o")
        typing.down("space")
        assertEquals(listOf("o"), typing.sent)
        typing.up("space")
        assertEquals(listOf("o", "space"), typing.sent)

        // Shiftは後の文字に作用するため、押しても古い文字を確定しない
        val shifted = TwoFingerTyping(textKeys = setOf("a"), modifiers = setOf("shift"))
        shifted.down("a")
        shifted.down("shift")
        assertTrue(shifted.sent.isEmpty())
        shifted.up("shift")
        shifted.up("a")
        assertEquals(listOf("shift", "a"), shifted.sent)
    }

    @Test
    fun heldFunctionKeyIsNotCommittedByNewerKey() {
        // 削除の長押しや空白の横ドラッグなど、文字キーでない押下は後のキーで打ち切らない
        val order = KeyPressOrder<String>()
        assertTrue(order.press("delete", isModifier = false, isTextKey = { false }).isEmpty())
        assertTrue(order.press("a", isModifier = false, isTextKey = { it == "a" }).isEmpty())
        // 離したキーは一覧から外れ、後の押下で確定対象に出ない
        order.release("a")
        assertTrue(order.press("b", isModifier = false, isTextKey = { it == "a" }).isEmpty())
    }

    @Test
    fun flickingKanaKeyCommitsLastDirectionWhenNewerKeyIsPressed() {
        // フリック中に別のキーを押したら、その時点の指の位置の向きで確定する
        val gesture = KeyGestureState(slopPx = 8f)
        gesture.down(50f, 50f)
        gesture.move(50f, 20f)
        assertEquals(50f, gesture.lastX)
        assertEquals(20f, gesture.lastY)
        assertEquals(
            FlickDirection.UP,
            determineFlickDirection(gesture.lastX - gesture.startX, gesture.lastY - gesture.startY, threshold),
        )
        // MOVEが届く前ならタップ（中心）として確定する
        gesture.down(10f, 10f)
        assertEquals(
            FlickDirection.CENTER,
            determineFlickDirection(gesture.lastX - gesture.startX, gesture.lastY - gesture.startY, threshold),
        )
    }

    @Test
    fun lastPositionKeepsUpdatingAfterLongPress() {
        // 長押し成立後もキー外へ動かしたかを判定できるよう、最後の位置は更新し続ける
        val gesture = KeyGestureState(slopPx = 8f)
        gesture.down(10f, 10f)
        assertTrue(gesture.longPressTimeout())
        gesture.move(200f, 10f)
        assertEquals(200f, gesture.lastX)
        assertTrue(gesture.isLongPressActive)
    }

    /** 縦と横それぞれの判定では、縦横どちらも閾値以下なら中心で、斜めに動かしても円より遅れて方向が決まる。 */
    @Test
    fun axisJudgeUsesEachAxisSeparately() {
        val judge = FlickJudge.AXIS
        // 閾値ちょうどは中心（円の判定では20未満が中心なので、閾値ちょうどは方向になる）
        assertEquals(FlickDirection.CENTER, determineFlickDirection(20f, 0f, threshold, judge))
        assertEquals(FlickDirection.RIGHT, determineFlickDirection(20f, 0f, threshold))
        // 斜め(15, 15)は距離21で円では方向になるが、縦横それぞれでは中心のまま
        assertEquals(FlickDirection.DOWN, determineFlickDirection(15f, 15f, threshold))
        assertEquals(FlickDirection.CENTER, determineFlickDirection(15f, 15f, threshold, judge))
        // どちらかの軸が閾値を超えたら、大きい方の軸で方向を決める
        assertEquals(FlickDirection.LEFT, determineFlickDirection(-21f, 5f, threshold, judge))
        assertEquals(FlickDirection.UP, determineFlickDirection(19f, -21f, threshold, judge))
        assertEquals(FlickDirection.DOWN, determineFlickDirection(0f, 25f, threshold, judge))
        // 閾値0以下は常に中心
        assertEquals(FlickDirection.CENTER, determineFlickDirection(50f, 0f, 0f, judge))
    }

    /** 既定の入力の操作は、これまでの判定（円・半径20dp、端末の長押し、トグル1秒）と同じ。Simejiに近い設定は縦横12dp。 */
    @Test
    fun inputTuningDefaultsKeepPreviousBehavior() {
        val defaults = InputTuning()
        assertEquals(20, defaults.flickThresholdDp)
        assertEquals(FlickJudge.CIRCLE, defaults.flickJudge)
        assertEquals(0, defaults.longPressMillis)
        assertEquals(dev.uzumi.ime.editor.KanaKeyToggle.WINDOW_MILLIS, defaults.toggleWindowMillis.toLong())
        assertEquals(defaults, InputTuning.fromStorage(emptyMap()))
        val simeji = defaults.simejiLike()
        assertEquals(12, simeji.flickThresholdDp)
        assertEquals(FlickJudge.AXIS, simeji.flickJudge)
        assertEquals(defaults.longPressMillis, simeji.longPressMillis)
        assertEquals(defaults.toggleWindowMillis, simeji.toggleWindowMillis)
        // 保存値の往復と、範囲外・読めない値の扱い
        val tuned = InputTuning(flickThresholdDp = 8, flickJudge = FlickJudge.AXIS, longPressMillis = 350, toggleWindowMillis = 1500)
        assertEquals(tuned, InputTuning.fromStorage(tuned.toStorage()))
        val clamped = InputTuning.fromStorage(
            mapOf(
                InputTuning.KEY_FLICK_THRESHOLD to "1",
                InputTuning.KEY_FLICK_JUDGE to "FUTURE",
                InputTuning.KEY_LONG_PRESS to "5000",
                InputTuning.KEY_TOGGLE_WINDOW to "abc",
            ),
        )
        assertEquals(InputTuning(flickThresholdDp = 6, longPressMillis = 800), clamped)
    }
}
