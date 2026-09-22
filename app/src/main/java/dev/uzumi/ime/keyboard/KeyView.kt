package dev.uzumi.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * キーの種別と表示・動作定義を表すシールドインターフェース。
 */
sealed interface KeySpec {
    /** 12キーかなフリックキー */
    data class Kana(val type: KanaKeyType) : KeySpec

    /** 単純な文字キー（QWERTYや数字など） */
    data class SimpleText(val text: String, val shiftText: String? = null) : KeySpec

    /** 各種機能アクションキー */
    data class Action(
        val action: KeyboardAction,
        val label: String,
        val isAccent: Boolean = false,
    ) : KeySpec

    /** キーボードモード切替キー */
    data class ModeSwitch(val label: String, val targetMode: KeyboardMode) : KeySpec

    /** 英語配列のShiftキー */
    data class Shift(val label: String) : KeySpec
}

/**
 * ソフトウェアキーボードの個別キーを描画およびタッチ処理するカスタムView。
 *
 * 単一・マルチタッチ追跡（active pointer制御）、フリック判定（DOWN→UP直結対応）、
 * 可視ガイド描画、触覚フィードバック、削除リピート処理（初回二重送信防止）、
 * TalkBack等のアクセシビリティ（全フリック候補のAccessibilityAction対応）を備える。
 */
class KeyView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val thresholdPx = 20f * density
    private val cornerRadius = 6f * density

    private var spec: KeySpec? = null
    private var onAction: ((KeyboardAction) -> Unit)? = null
    private var onModeSwitch: ((KeyboardMode) -> Unit)? = null
    private var onShiftToggle: (() -> Unit)? = null

    private var isKeyPressed = false
    private var isShifted = false
    private var isCapsLock = false
    private var currentDirection = FlickDirection.CENTER
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var startX = 0f
    private var startY = 0f
    private var customActionLabel: String? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            onAction?.invoke(KeyboardAction.Delete)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val guideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val rectF = RectF()

    init {
        isFocusable = true
        isClickable = true
    }

    /**
     * キーの定義と各種コールバックを設定する。
     */
    fun setKeySpec(
        spec: KeySpec,
        onAction: (KeyboardAction) -> Unit,
        onModeSwitch: ((KeyboardMode) -> Unit)? = null,
        onShiftToggle: (() -> Unit)? = null,
    ) {
        this.spec = spec
        this.onAction = onAction
        this.onModeSwitch = onModeSwitch
        this.onShiftToggle = onShiftToggle
        updateContentDescription()
        invalidate()
    }

    /**
     * アクションキーの表示ラベルを更新する（Enterキー等の動的ラベルに対応）。
     */
    fun updateActionLabel(label: String) {
        this.customActionLabel = label
        updateContentDescription()
        invalidate()
    }

    /**
     * モード切替キーのラベルと遷移先モードを動的に更新する。
     */
    fun updateModeSwitch(label: String, targetMode: KeyboardMode) {
        val currentSpec = spec
        if (currentSpec is KeySpec.ModeSwitch) {
            this.spec = currentSpec.copy(label = label, targetMode = targetMode)
            updateContentDescription()
            invalidate()
        }
    }

    /**
     * Shift状態を更新する。
     */
    fun updateShiftState(isShifted: Boolean, isCapsLock: Boolean) {
        this.isShifted = isShifted
        this.isCapsLock = isCapsLock
        updateContentDescription()
        invalidate()
    }

    /**
     * 保留中のタッチ入力やリピート処理を直ちに中断する。
     */
    fun cancelPendingInput() {
        repeatHandler.removeCallbacksAndMessages(null)
        if (isKeyPressed || activePointerId != MotionEvent.INVALID_POINTER_ID) {
            isKeyPressed = false
            currentDirection = FlickDirection.CENTER
            activePointerId = MotionEvent.INVALID_POINTER_ID
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        cancelPendingInput()
        super.onDetachedFromWindow()
    }

    /** フリック確定とアクセシビリティのperformClickを別経路で重複なく処理する。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                startX = event.x
                startY = event.y
                isKeyPressed = true
                currentDirection = FlickDirection.CENTER
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                val currentSpec = spec
                if (currentSpec is KeySpec.Action && currentSpec.action is KeyboardAction.Delete) {
                    // 削除キー押下時は初回に1文字即時削除し、長押し時にリピートを開始（初回二重送信防止）
                    onAction?.invoke(KeyboardAction.Delete)
                    repeatHandler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY_MS)
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // すでにアクティブポインタを追跡中の場合は別の指を無視する
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return false

                val curX = event.getX(pointerIndex)
                val curY = event.getY(pointerIndex)
                val dx = curX - startX
                val dy = curY - startY

                val currentSpec = spec
                if (currentSpec is KeySpec.Kana) {
                    val newDirection = determineFlickDirection(dx, dy, thresholdPx)
                    if (newDirection != currentDirection) {
                        currentDirection = newDirection
                        invalidate()
                    }
                } else if (currentSpec is KeySpec.Action && currentSpec.action is KeyboardAction.Delete) {
                    // 指がキー領域から大きく外れた場合はリピート停止
                    if (curX < -thresholdPx || curX > width + thresholdPx ||
                        curY < -thresholdPx || curY > height + thresholdPx
                    ) {
                        repeatHandler.removeCallbacksAndMessages(null)
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                if (pointerId == activePointerId) {
                    // アクティブポインタが離脱した場合、そのポインタの座標で入力を確定
                    handleActivePointerUp(event, actionIndex)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                if (pointerId == activePointerId) {
                    handleActivePointerUp(event, actionIndex)
                } else {
                    // 別ポインタのUPでは確定せず、安全にリセット
                    cancelPendingInput()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingInput()
                return true
            }

            else -> return super.onTouchEvent(event)
        }
    }

    /**
     * アクティブポインタの離脱時に文字確定またはアクション実行を行う。
     *
     * 高速なDOWN→UPでMOVEイベントが届かない場合でもUP座標から方向を再計算し、
     * タッチ経路ではperformClickによる重複送信を防ぎつつAccessibilityEventを通知する。
     */
    private fun handleActivePointerUp(event: MotionEvent, pointerIndex: Int) {
        repeatHandler.removeCallbacksAndMessages(null)
        val wasPressed = isKeyPressed
        val finalSpec = spec

        isKeyPressed = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        currentDirection = FlickDirection.CENTER
        invalidate()

        if (!wasPressed || finalSpec == null) return

        val upX = event.getX(pointerIndex)
        val upY = event.getY(pointerIndex)
        val isInside = upX in 0f..width.toFloat() && upY in 0f..height.toFloat()

        when (finalSpec) {
            is KeySpec.Kana -> {
                // MOVEが届かない速いフリックでもUP座標から正確にフリック方向を再計算する
                val dx = upX - startX
                val dy = upY - startY
                val finalDirection = determineFlickDirection(dx, dy, thresholdPx)
                val char = KeyboardLayoutData.getKanaChar(finalSpec.type, finalDirection)
                if (char.isNotEmpty()) {
                    onAction?.invoke(KeyboardAction.Text(char))
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.SimpleText -> {
                if (isInside) {
                    val text = if (isShifted || isCapsLock) {
                        finalSpec.shiftText ?: finalSpec.text.uppercase()
                    } else {
                        finalSpec.text
                    }
                    onAction?.invoke(KeyboardAction.Text(text))
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.Action -> {
                if (isInside) {
                    if (finalSpec.action !is KeyboardAction.Delete) {
                        onAction?.invoke(finalSpec.action)
                    }
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.ModeSwitch -> {
                if (isInside) {
                    onModeSwitch?.invoke(finalSpec.targetMode)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.Shift -> {
                if (isInside) {
                    onShiftToggle?.invoke()
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        // 外部アクセシビリティサービス等から明示的にクリックされた場合の入力処理
        when (val currentSpec = spec) {
            is KeySpec.Kana -> {
                val char = KeyboardLayoutData.getKanaChar(currentSpec.type, FlickDirection.CENTER)
                if (char.isNotEmpty()) {
                    onAction?.invoke(KeyboardAction.Text(char))
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }

            is KeySpec.SimpleText -> {
                val text = if (isShifted || isCapsLock) {
                    currentSpec.shiftText ?: currentSpec.text.uppercase()
                } else {
                    currentSpec.text
                }
                onAction?.invoke(KeyboardAction.Text(text))
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            is KeySpec.Action -> {
                onAction?.invoke(currentSpec.action)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            is KeySpec.ModeSwitch -> {
                onModeSwitch?.invoke(currentSpec.targetMode)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            is KeySpec.Shift -> {
                onShiftToggle?.invoke()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            null -> Unit
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val currentSpec = spec ?: return
        if (currentSpec is KeySpec.Kana) {
            val map = KeyboardLayoutData.getKanaDirections(currentSpec.type)
            // TalkBackのカスタムアクションメニューから全フリック文字にアクセス可能にする
            map[FlickDirection.CENTER]?.let { char ->
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_INPUT_CENTER, "「$char」を入力"))
            }
            map[FlickDirection.LEFT]?.let { char ->
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_INPUT_LEFT, "「$char」を入力（左フリック）"))
            }
            map[FlickDirection.UP]?.let { char ->
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_INPUT_UP, "「$char」を入力（上フリック）"))
            }
            map[FlickDirection.RIGHT]?.let { char ->
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_INPUT_RIGHT, "「$char」を入力（右フリック）"))
            }
            map[FlickDirection.DOWN]?.let { char ->
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_INPUT_DOWN, "「$char」を入力（下フリック）"))
            }
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val currentSpec = spec
        if (currentSpec is KeySpec.Kana) {
            val direction = when (action) {
                ACTION_INPUT_CENTER -> FlickDirection.CENTER
                ACTION_INPUT_LEFT -> FlickDirection.LEFT
                ACTION_INPUT_UP -> FlickDirection.UP
                ACTION_INPUT_RIGHT -> FlickDirection.RIGHT
                ACTION_INPUT_DOWN -> FlickDirection.DOWN
                else -> null
            }
            if (direction != null) {
                val char = KeyboardLayoutData.getKanaChar(currentSpec.type, direction)
                if (char.isNotEmpty()) {
                    onAction?.invoke(KeyboardAction.Text(char))
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                    return true
                }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 2f * density
        rectF.set(pad, pad, w - pad, h - pad)

        val currentSpec = spec ?: return
        val isAccent = when (currentSpec) {
            is KeySpec.Action -> currentSpec.isAccent
            else -> false
        }
        val isFunctionKey = when (currentSpec) {
            is KeySpec.Action -> !currentSpec.isAccent
            is KeySpec.ModeSwitch, is KeySpec.Shift -> true
            else -> false
        }

        // 背景色の決定
        val bgColor = when {
            isAccent -> if (isKeyPressed) 0xFF1565C0.toInt() else 0xFF1976D2.toInt()
            currentSpec is KeySpec.Shift && (isShifted || isCapsLock) -> {
                if (isKeyPressed) 0xFF90CAF9.toInt() else 0xFFBBDEFB.toInt()
            }
            isFunctionKey -> if (isKeyPressed) 0xFFB0BEC5.toInt() else 0xFFCFD8DC.toInt()
            else -> if (isKeyPressed) 0xFFB0BEC5.toInt() else 0xFFFFFFFF.toInt()
        }

        backgroundPaint.color = bgColor
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, backgroundPaint)

        // テキスト色とサイズの決定
        val textColor = when {
            isAccent -> 0xFFFFFFFF.toInt()
            isFunctionKey -> 0xFF37474F.toInt()
            else -> 0xFF212121.toInt()
        }

        val centerX = rectF.centerX()
        val centerY = rectF.centerY()

        when (currentSpec) {
            is KeySpec.Kana -> {
                drawKanaKey(canvas, currentSpec.type, centerX, centerY, rectF)
            }

            is KeySpec.SimpleText -> {
                val text = if (isShifted || isCapsLock) {
                    currentSpec.shiftText ?: currentSpec.text.uppercase()
                } else {
                    currentSpec.text
                }
                mainTextPaint.color = textColor
                mainTextPaint.textSize = 20f * density
                val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
                canvas.drawText(text, centerX, textY, mainTextPaint)
            }

            is KeySpec.Action -> {
                val label = customActionLabel ?: currentSpec.label
                mainTextPaint.color = textColor
                mainTextPaint.textSize = 15f * density
                val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
                canvas.drawText(label, centerX, textY, mainTextPaint)
            }

            is KeySpec.ModeSwitch -> {
                mainTextPaint.color = textColor
                mainTextPaint.textSize = 14f * density
                val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
                canvas.drawText(currentSpec.label, centerX, textY, mainTextPaint)
            }

            is KeySpec.Shift -> {
                val label = when {
                    isCapsLock -> "Caps"
                    isShifted -> "▲"
                    else -> "⇧"
                }
                mainTextPaint.color = textColor
                mainTextPaint.textSize = 16f * density
                val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
                canvas.drawText(label, centerX, textY, mainTextPaint)
            }
        }
    }

    /**
     * 12キーかなキーのメイン文字およびフリックガイドを描画する。
     */
    private fun drawKanaKey(
        canvas: Canvas,
        type: KanaKeyType,
        centerX: Float,
        centerY: Float,
        rect: RectF,
    ) {
        val map = KeyboardLayoutData.getKanaDirections(type)
        val selectedChar = map[currentDirection] ?: map[FlickDirection.CENTER] ?: ""

        // メイン文字の描画（フリック中は選択中文字を表示）
        mainTextPaint.color = if (currentDirection != FlickDirection.CENTER) 0xFF1976D2.toInt() else 0xFF212121.toInt()
        mainTextPaint.textSize = if (currentDirection != FlickDirection.CENTER) 24f * density else 20f * density
        mainTextPaint.typeface = if (currentDirection != FlickDirection.CENTER) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
        canvas.drawText(selectedChar, centerX, textY, mainTextPaint)

        // ガイド文字（非押下中またはタップ時に薄く表示）
        guideTextPaint.textSize = 11f * density

        val leftChar = map[FlickDirection.LEFT]
        if (!leftChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.LEFT) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt()
            val gy = centerY - (guideTextPaint.descent() + guideTextPaint.ascent()) / 2f
            canvas.drawText(leftChar, rect.left + 10f * density, gy, guideTextPaint)
        }

        val upChar = map[FlickDirection.UP]
        if (!upChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.UP) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt()
            val gy = rect.top + 13f * density
            canvas.drawText(upChar, centerX, gy, guideTextPaint)
        }

        val rightChar = map[FlickDirection.RIGHT]
        if (!rightChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.RIGHT) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt()
            val gy = centerY - (guideTextPaint.descent() + guideTextPaint.ascent()) / 2f
            canvas.drawText(rightChar, rect.right - 10f * density, gy, guideTextPaint)
        }

        val downChar = map[FlickDirection.DOWN]
        if (!downChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.DOWN) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt()
            val gy = rect.bottom - 5f * density
            canvas.drawText(downChar, centerX, gy, guideTextPaint)
        }
    }

    /**
     * TalkBack用の日本語コンテンツ説明文を更新する。
     * 各フリック候補文字を含めて説明する。
     */
    private fun updateContentDescription() {
        val desc = when (val currentSpec = spec) {
            is KeySpec.Kana -> {
                val map = KeyboardLayoutData.getKanaDirections(currentSpec.type)
                val c = map[FlickDirection.CENTER] ?: ""
                val l = map[FlickDirection.LEFT] ?: ""
                val u = map[FlickDirection.UP] ?: ""
                val r = map[FlickDirection.RIGHT] ?: ""
                val d = map[FlickDirection.DOWN] ?: ""
                val sb = StringBuilder("$c 行、タップで $c")
                if (l.isNotEmpty()) sb.append("、左で $l")
                if (u.isNotEmpty()) sb.append("、上で $u")
                if (r.isNotEmpty()) sb.append("、右で $r")
                if (d.isNotEmpty()) sb.append("、下で $d")
                sb.toString()
            }

            is KeySpec.SimpleText -> {
                val text = if (isShifted || isCapsLock) {
                    currentSpec.shiftText ?: currentSpec.text.uppercase()
                } else {
                    currentSpec.text
                }
                text
            }

            is KeySpec.Action -> {
                customActionLabel ?: when (currentSpec.action) {
                    is KeyboardAction.Text -> currentSpec.action.value
                    is KeyboardAction.Delete -> "削除"
                    is KeyboardAction.Enter -> "確定"
                    is KeyboardAction.Space -> "空白"
                    is KeyboardAction.Convert -> "変換"
                    is KeyboardAction.TransformKana -> "濁点、半濁点、小文字"
                    is KeyboardAction.MoveCursor -> if (currentSpec.action.delta < 0) "カーソルを左へ移動" else "カーソルを右へ移動"
                }
            }

            is KeySpec.ModeSwitch -> "${currentSpec.label} キー"
            is KeySpec.Shift -> when {
                isCapsLock -> "キャップスロック有効"
                isShifted -> "シフト有効"
                else -> "シフト"
            }

            null -> ""
        }
        contentDescription = desc
    }

    companion object {
        private const val INITIAL_REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 60L

        // TalkBack用カスタムAccessibilityAction ID（0x01000000番台）
        const val ACTION_INPUT_CENTER = 0x01000001
        const val ACTION_INPUT_LEFT = 0x01000002
        const val ACTION_INPUT_UP = 0x01000003
        const val ACTION_INPUT_RIGHT = 0x01000004
        const val ACTION_INPUT_DOWN = 0x01000005
    }
}
