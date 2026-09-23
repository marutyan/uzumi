package dev.uzumi.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * キーの種別と表示・動作定義を表すシールドインターフェース。
 */
sealed interface KeySpec {
    /** 12キーかなフリックキー */
    data class Kana(val type: KanaKeyType) : KeySpec

    /**
     * 単純な文字キー（QWERTYや数字、記号など）。
     *
     * @property longPressText 長押しで入力する文字。長押しを持たないキーではnull
     */
    data class SimpleText(
        val text: String,
        val shiftText: String? = null,
        val longPressText: String? = null,
    ) : KeySpec

    /** 各種機能アクションキー */
    data class Action(
        val action: KeyboardAction,
        val label: String,
        val isAccent: Boolean = false,
    ) : KeySpec

    /**
     * キーボードモード切替キー。
     *
     * @property longPressTarget 長押しで切り替える先のモード。長押しを持たないキーではnull
     * @property longPressLabel 長押し先を示す小さな表示
     */
    data class ModeSwitch(
        val label: String,
        val targetMode: KeyboardMode,
        val longPressTarget: KeyboardMode? = null,
        val longPressLabel: String? = null,
    ) : KeySpec

    /** 英語配列のShiftキー */
    data class Shift(val label: String) : KeySpec

    /**
     * 記号面のページ切替キー。モードは変えずに同じ記号面の次のページを表示する。
     *
     * @property description TalkBackで読み上げる説明
     */
    data class PageSwitch(val label: String, val description: String) : KeySpec
}

/**
 * ソフトウェアキーボードの個別キーを描画およびタッチ処理するカスタムView。
 *
 * 単一・マルチタッチ追跡（active pointer制御）、フリック判定（DOWN→UP直結対応）、
 * 可視ガイド描画、触覚フィードバック、削除・カーソルの連続実行、文字キーの長押し、
 * TalkBack等のアクセシビリティ（全フリック候補と長押し文字のAccessibilityAction対応）を備える。
 */
class KeyView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val thresholdPx = 20f * density
    private val cornerRadius = KeyboardDimens.KEY_CORNER_DP * density

    // 端末のライト／ダーク設定に合う配色。入力Viewは構成変更のたびに作り直されるため、生成時に一度読む。
    private val colors = KeyboardColors.from(context)

    private var spec: KeySpec? = null
    private var onAction: ((KeyboardAction) -> Unit)? = null
    private var onModeSwitch: ((KeyboardMode) -> Unit)? = null
    private var onShiftToggle: (() -> Unit)? = null
    private var onPageSwitch: (() -> Unit)? = null

    // 押下中の文字の拡大表示を出す・消すコールバック。文字がnullなら消す。拡大表示を使わない面ではnull。
    private var onPreview: ((View, String?) -> Unit)? = null

    // 長押しの取り消し距離は端末の標準touch slopに合わせる。フリック閾値とは別の値である。
    private val gesture = KeyGestureState(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var isShifted = false
    private var isCapsLock = false
    private var currentDirection = FlickDirection.CENTER
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var customActionLabel: String? = null

    // 現在の押下で連続実行した回数。間隔の加速に使う。
    private var repeatCount = 0

    // 削除キーの左ドラッグの判定。閾値はSimejiの実測（66〜73dp）に合わせて68dpとする。
    private val deleteDrag = DeleteDragTracker(
        slopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        thresholdPx = DELETE_DRAG_THRESHOLD_DP * density,
        escapePx = DELETE_DRAG_ESCAPE_DP * density,
    )

    // 設定画面で選んだ反応。振動・キー音・削除の左ドラッグを使うか。
    private var vibrationEnabled = true
    private var keySoundEnabled = false
    private var deleteDragEnabled = true

    // 削除キーのドラッグ中の状態を、キーボードへ知らせて案内を出すコールバック。
    private var onDeleteDrag: ((View, DeleteDragState) -> Unit)? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val action = repeatableAction() ?: return
            onAction?.invoke(action)
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            repeatCount += 1
            repeatHandler.postDelayed(this, KeyRepeatPolicy.intervalAfter(repeatCount))
        }
    }
    private val longPressRunnable = Runnable {
        if (isAttachedToWindow && gesture.longPressTimeout()) {
            haptic(HapticFeedbackConstants.LONG_PRESS)
            updatePreview()
            invalidate()
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
        onPageSwitch: (() -> Unit)? = null,
        onPreview: ((View, String?) -> Unit)? = null,
        onDeleteDrag: ((View, DeleteDragState) -> Unit)? = null,
    ) {
        this.onDeleteDrag = onDeleteDrag
        this.spec = spec
        this.onAction = onAction
        this.onModeSwitch = onModeSwitch
        this.onShiftToggle = onShiftToggle
        this.onPageSwitch = onPageSwitch
        this.onPreview = onPreview
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
     * 設定画面で選んだ振動・キー音・削除の左ドラッグの有無を反映する。
     */
    fun applyPreferences(preferences: KeyboardPreferences) {
        vibrationEnabled = preferences.vibration
        keySoundEnabled = preferences.keySound
        deleteDragEnabled = preferences.deleteDrag
    }

    /** 振動の設定がONのときだけ触覚フィードバックを返す。 */
    private fun haptic(feedbackConstant: Int) {
        if (vibrationEnabled) performHapticFeedback(feedbackConstant)
    }

    /** キー音の設定がONのとき、キーの種類に合う標準のキー音を鳴らす。 */
    private fun playKeySound() {
        if (!keySoundEnabled) return
        val effect = when ((spec as? KeySpec.Action)?.action) {
            KeyboardAction.Delete -> AudioManager.FX_KEYPRESS_DELETE
            KeyboardAction.Enter -> AudioManager.FX_KEYPRESS_RETURN
            KeyboardAction.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        context.getSystemService(AudioManager::class.java)?.playSoundEffect(effect, -1f)
    }

    /**
     * コールバックはそのままに、キーの定義だけを差し替える。入力中かどうかで役割が変わるキー
     * （「123」と「カナ」、「空白」と「変換」）に使い、差し替え前の押下や連続実行は捨てる。
     */
    fun replaceSpec(newSpec: KeySpec) {
        if (newSpec == spec) return
        cancelPendingInput()
        spec = newSpec
        updateContentDescription()
        invalidate()
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
     * 保留中のタッチ入力、長押し、連続実行を直ちに中断する。
     */
    fun cancelPendingInput() {
        repeatHandler.removeCallbacksAndMessages(null)
        removeCallbacks(longPressRunnable)
        if (gesture.isPressed) onPreview?.invoke(this, null)
        if (deleteDrag.state != DeleteDragState.NONE) onDeleteDrag?.invoke(this, DeleteDragState.NONE)
        deleteDrag.reset()
        if (gesture.isPressed || activePointerId != MotionEvent.INVALID_POINTER_ID) {
            gesture.reset()
            currentDirection = FlickDirection.CENTER
            activePointerId = MotionEvent.INVALID_POINTER_ID
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        cancelPendingInput()
        super.onDetachedFromWindow()
    }

    /**
     * 押下中に離すと入力される文字を返す。フリック中はその向きの文字、長押し成立後は長押し文字とする。
     * 文字を入力しないキー（切替・削除など）ではnullを返し、拡大表示を出さない。
     */
    private fun previewText(): String? = when (val currentSpec = spec) {
        is KeySpec.Kana -> KeyboardLayoutData.getKanaChar(currentSpec.type, currentDirection)
            .ifEmpty { KeyboardLayoutData.getKanaChar(currentSpec.type, FlickDirection.CENTER) }
        is KeySpec.SimpleText -> currentSpec.longPressText?.takeIf { gesture.isLongPressActive } ?: shiftedText(currentSpec)
        else -> null
    }

    /** 押下中なら拡大表示を今の文字へ更新する。 */
    private fun updatePreview() {
        val callback = onPreview ?: return
        val text = previewText() ?: return
        if (gesture.isPressed) callback(this, text)
    }

    /**
     * 削除キーかどうか。削除キーは左ドラッグを受け付けるため、押した瞬間ではなく離したときに1文字消す。
     */
    private fun isDeleteKey(): Boolean = (spec as? KeySpec.Action)?.action == KeyboardAction.Delete

    /** 押下中に連続実行するアクションキーであれば、そのアクションを返す。 */
    private fun repeatableAction(): KeyboardAction? {
        val currentSpec = spec as? KeySpec.Action ?: return null
        return currentSpec.action.takeIf(KeyRepeatPolicy::isRepeatable)
    }

    /** 長押しで別の入力を持つキーかどうかを返す。かなキーは長押しを持たず、フリックと衝突しない。 */
    private fun hasLongPress(): Boolean = when (val currentSpec = spec) {
        is KeySpec.SimpleText -> currentSpec.longPressText != null
        is KeySpec.ModeSwitch -> currentSpec.longPressTarget != null
        else -> false
    }

    /** Shift状態を反映した文字キーの入力文字列を返す。 */
    private fun shiftedText(textSpec: KeySpec.SimpleText): String {
        return if (isShifted || isCapsLock) {
            textSpec.shiftText ?: textSpec.text.uppercase()
        } else {
            textSpec.text
        }
    }

    /** フリック確定とアクセシビリティのperformClickを別経路で重複なく処理する。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                gesture.down(event.x, event.y)
                currentDirection = FlickDirection.CENTER
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                playKeySound()
                updatePreview()

                val repeatAction = repeatableAction()
                if (isDeleteKey()) {
                    // 削除は離したときに1文字消す。押し続けた場合は従来と同じ待ち時間の後に連続削除を始める。
                    deleteDrag.reset()
                    repeatCount = 0
                    repeatHandler.postDelayed(repeatRunnable, KeyRepeatPolicy.INITIAL_DELAY_MS)
                } else if (repeatAction != null) {
                    // 削除・カーソルは押下時に一回実行し、押し続けた場合だけ連続実行する（離したときは再送しない）
                    onAction?.invoke(repeatAction)
                    repeatCount = 0
                    repeatHandler.postDelayed(repeatRunnable, KeyRepeatPolicy.INITIAL_DELAY_MS)
                } else if (hasLongPress()) {
                    // 利用者が変更できる端末の長押し時間に従う
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
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
                gesture.move(curX, curY)
                if (gesture.hasMovedBeyondSlop) {
                    removeCallbacks(longPressRunnable)
                }

                if (spec is KeySpec.Kana) {
                    val newDirection = determineFlickDirection(curX - gesture.startX, curY - gesture.startY, thresholdPx)
                    if (newDirection != currentDirection) {
                        currentDirection = newDirection
                        updatePreview()
                        invalidate()
                    }
                } else if (isDeleteKey() && repeatCount == 0 && deleteDragEnabled) {
                    // 連続削除が始まる前に左へ動かしたら、左ドラッグとして扱い連続削除を止める
                    val changed = deleteDrag.move(gesture.startX - curX, gesture.startY - curY)
                    if (deleteDrag.isDragging) repeatHandler.removeCallbacksAndMessages(null)
                    if (changed) onDeleteDrag?.invoke(this, deleteDrag.state)
                } else if (repeatableAction() != null) {
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
        removeCallbacks(longPressRunnable)
        onPreview?.invoke(this, null)
        val wasPressed = gesture.isPressed
        val wasLongPressed = gesture.isLongPressActive
        val deleteRelease = deleteDrag.release()
        val deleteRepeated = repeatCount > 0
        if (deleteDrag.state != DeleteDragState.NONE) onDeleteDrag?.invoke(this, DeleteDragState.NONE)
        deleteDrag.reset()
        val startX = gesture.startX
        val startY = gesture.startY
        val finalSpec = spec

        gesture.reset()
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
                val finalDirection = determineFlickDirection(upX - startX, upY - startY, thresholdPx)
                val char = KeyboardLayoutData.getKanaChar(finalSpec.type, finalDirection)
                if (char.isNotEmpty()) {
                    onAction?.invoke(KeyboardAction.Text(char))
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.SimpleText -> {
                val text = resolveTextKeyRelease(
                    text = shiftedText(finalSpec),
                    longPressText = finalSpec.longPressText,
                    isInside = isInside,
                    isLongPressActive = wasLongPressed,
                )
                if (text != null) {
                    onAction?.invoke(KeyboardAction.Text(text))
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.Action -> if (finalSpec.action == KeyboardAction.Delete) {
                // 左ドラッグはキーの外で離すことが多いため、キー内かどうかより先に判定する
                val action = when (deleteRelease) {
                    DeleteRelease.TAP -> KeyboardAction.Delete.takeIf { isInside && !deleteRepeated }
                    DeleteRelease.SINGLE -> KeyboardAction.Delete
                    DeleteRelease.LINE -> KeyboardAction.DeleteToLineStart
                    DeleteRelease.CANCEL -> null
                }
                if (action != null) {
                    onAction?.invoke(action)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            } else {
                if (isInside) {
                    // 連続実行するキーは押下時に実行済みのため、離したときは送らない
                    if (!KeyRepeatPolicy.isRepeatable(finalSpec.action)) {
                        onAction?.invoke(finalSpec.action)
                    }
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.ModeSwitch -> {
                if (isInside) {
                    val target = if (wasLongPressed) finalSpec.longPressTarget ?: finalSpec.targetMode else finalSpec.targetMode
                    onModeSwitch?.invoke(target)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.Shift -> {
                if (isInside) {
                    onShiftToggle?.invoke()
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

            is KeySpec.PageSwitch -> {
                if (isInside) {
                    onPageSwitch?.invoke()
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }
        }
    }

    /**
     * 外部アクセシビリティサービスからのクリックを一回の入力として処理する。
     * TalkBackでは指を離したとき、またはダブルタップしたときにこの経路で入力されるため、独自のhover処理は持たない。
     */
    override fun performClick(): Boolean {
        super.performClick()
        when (val currentSpec = spec) {
            is KeySpec.Kana -> {
                val char = KeyboardLayoutData.getKanaChar(currentSpec.type, FlickDirection.CENTER)
                if (char.isNotEmpty()) {
                    onAction?.invoke(KeyboardAction.Text(char))
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }

            is KeySpec.SimpleText -> {
                onAction?.invoke(KeyboardAction.Text(shiftedText(currentSpec)))
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            is KeySpec.Action -> {
                onAction?.invoke(currentSpec.action)
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            is KeySpec.ModeSwitch -> {
                onModeSwitch?.invoke(currentSpec.targetMode)
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            is KeySpec.Shift -> {
                onShiftToggle?.invoke()
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            is KeySpec.PageSwitch -> {
                onPageSwitch?.invoke()
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            null -> Unit
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // TalkBackが「ボタン」として扱い、ダブルタップや指を離す操作でクリックできることを伝える
        info.className = Button::class.java.name
        val currentSpec = spec ?: return
        info.hintText = accessibilityHint(currentSpec)
        when (currentSpec) {
            is KeySpec.Kana -> {
                // TalkBackの操作メニューから全フリック文字にアクセス可能にする
                val map = KeyboardLayoutData.getKanaDirections(currentSpec.type)
                FLICK_ACTION_IDS.forEach { (direction, actionId) ->
                    map[direction]?.takeIf { it.isNotEmpty() }?.let { char ->
                        info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(actionId, KeySpeech.flickActionLabel(char, direction)),
                        )
                    }
                }
            }

            is KeySpec.SimpleText -> currentSpec.longPressText?.let { alt ->
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK,
                        "${KeySpeech.spokenText(alt)}を入力",
                    ),
                )
            }

            is KeySpec.Action -> if (currentSpec.action == KeyboardAction.Delete) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_DELETE_TO_LINE_START, "行頭まで削除"))
            }

            is KeySpec.ModeSwitch -> currentSpec.longPressTarget?.let { target ->
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK,
                        KeySpeech.modeSwitchDescription(target),
                    ),
                )
            }

            else -> Unit
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (val currentSpec = spec) {
            is KeySpec.Kana -> {
                val direction = FLICK_ACTION_IDS.firstOrNull { it.second == action }?.first
                if (direction != null) {
                    val char = KeyboardLayoutData.getKanaChar(currentSpec.type, direction)
                    if (char.isNotEmpty()) {
                        onAction?.invoke(KeyboardAction.Text(char))
                        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                        return true
                    }
                }
            }

            is KeySpec.SimpleText -> {
                val alt = currentSpec.longPressText
                if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && alt != null) {
                    onAction?.invoke(KeyboardAction.Text(alt))
                    haptic(HapticFeedbackConstants.LONG_PRESS)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                    return true
                }
            }

            is KeySpec.Action -> {
                if (action == ACTION_DELETE_TO_LINE_START && currentSpec.action == KeyboardAction.Delete) {
                    onAction?.invoke(KeyboardAction.DeleteToLineStart)
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                    return true
                }
            }

            is KeySpec.ModeSwitch -> {
                val target = currentSpec.longPressTarget
                if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && target != null) {
                    onModeSwitch?.invoke(target)
                    haptic(HapticFeedbackConstants.LONG_PRESS)
                    return true
                }
            }

            else -> Unit
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = KeyboardDimens.KEY_INSET_DP * density
        rectF.set(pad, pad, w - pad, h - pad)

        val currentSpec = spec ?: return
        val isKeyPressed = gesture.isPressed
        val isLongPressed = gesture.isLongPressActive
        val isAccent = when (currentSpec) {
            is KeySpec.Action -> currentSpec.isAccent
            else -> false
        }
        val isFunctionKey = when (currentSpec) {
            is KeySpec.Action -> !currentSpec.isAccent
            is KeySpec.ModeSwitch, is KeySpec.Shift, is KeySpec.PageSwitch -> true
            else -> false
        }

        // 背景色の決定
        val bgColor = when {
            isAccent -> if (isKeyPressed) colors.accentPressed else colors.accent
            currentSpec is KeySpec.Shift && (isShifted || isCapsLock) -> {
                if (isKeyPressed) colors.functionPressed else colors.keyActive
            }
            isFunctionKey -> if (isKeyPressed) colors.functionPressed else colors.functionBackground
            else -> if (isKeyPressed) colors.keyPressed else colors.keyBackground
        }

        backgroundPaint.color = bgColor
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, backgroundPaint)

        // テキスト色とサイズの決定
        val textColor = when {
            isAccent -> colors.onAccent
            else -> colors.text
        }

        val centerX = rectF.centerX()
        val centerY = rectF.centerY()

        when (currentSpec) {
            is KeySpec.Kana -> {
                drawKanaKey(canvas, currentSpec.type, centerX, centerY, rectF)
            }

            is KeySpec.SimpleText -> {
                val alt = currentSpec.longPressText
                if (isLongPressed && alt != null) {
                    // 長押し成立中は、離したときに入力される文字を強調して示す
                    drawCenteredText(canvas, alt, centerX, centerY, colors.accent, 24f, Typeface.DEFAULT_BOLD)
                } else {
                    drawCenteredText(canvas, shiftedText(currentSpec), centerX, centerY, textColor, 20f, Typeface.DEFAULT)
                    alt?.let { drawCornerHint(canvas, it, rectF) }
                }
            }

            is KeySpec.Action -> {
                val label = customActionLabel ?: currentSpec.label
                drawCenteredText(canvas, label, centerX, centerY, textColor, 15f, Typeface.DEFAULT)
            }

            is KeySpec.ModeSwitch -> {
                val longLabel = currentSpec.longPressLabel
                if (isLongPressed && longLabel != null) {
                    drawCenteredText(canvas, longLabel, centerX, centerY, colors.accent, 14f, Typeface.DEFAULT_BOLD)
                } else {
                    drawCenteredText(canvas, currentSpec.label, centerX, centerY, textColor, 14f, Typeface.DEFAULT)
                    longLabel?.let { drawCornerHint(canvas, it, rectF) }
                }
            }

            is KeySpec.Shift -> {
                val label = when {
                    isCapsLock -> "Caps"
                    isShifted -> "▲"
                    else -> "⇧"
                }
                drawCenteredText(canvas, label, centerX, centerY, textColor, 16f, Typeface.DEFAULT)
            }

            is KeySpec.PageSwitch -> {
                drawCenteredText(canvas, currentSpec.label, centerX, centerY, textColor, 14f, Typeface.DEFAULT)
            }
        }
    }

    /** キー中央へ一つのラベルを描画する。サイズはdp単位で受け取る。 */
    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        color: Int,
        sizeDp: Float,
        face: Typeface,
    ) {
        mainTextPaint.color = color
        mainTextPaint.textSize = sizeDp * density
        mainTextPaint.typeface = face
        val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
        canvas.drawText(text, centerX, textY, mainTextPaint)
    }

    /** 長押しで入力できる文字をキー右上へ小さく描画し、長押しの存在を見て分かるようにする。 */
    private fun drawCornerHint(canvas: Canvas, text: String, rect: RectF) {
        guideTextPaint.textSize = 10f * density
        guideTextPaint.color = colors.textSecondary
        canvas.drawText(text, rect.right - 8f * density, rect.top + 12f * density, guideTextPaint)
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
        mainTextPaint.color = if (currentDirection != FlickDirection.CENTER) colors.accent else colors.text
        mainTextPaint.textSize = if (currentDirection != FlickDirection.CENTER) 26f * density else KANA_LABEL_DP * density
        mainTextPaint.typeface = if (currentDirection != FlickDirection.CENTER) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
        canvas.drawText(selectedChar, centerX, textY, mainTextPaint)

        // フリックの案内文字は押している間だけ出し、普段はキーの文字だけを見せる（Simejiと同じ見た目）
        if (!gesture.isPressed) return
        guideTextPaint.textSize = 11f * density

        val leftChar = map[FlickDirection.LEFT]
        if (!leftChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.LEFT) colors.accent else colors.textSecondary
            val gy = centerY - (guideTextPaint.descent() + guideTextPaint.ascent()) / 2f
            canvas.drawText(leftChar, rect.left + 10f * density, gy, guideTextPaint)
        }

        val upChar = map[FlickDirection.UP]
        if (!upChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.UP) colors.accent else colors.textSecondary
            val gy = rect.top + 13f * density
            canvas.drawText(upChar, centerX, gy, guideTextPaint)
        }

        val rightChar = map[FlickDirection.RIGHT]
        if (!rightChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.RIGHT) colors.accent else colors.textSecondary
            val gy = centerY - (guideTextPaint.descent() + guideTextPaint.ascent()) / 2f
            canvas.drawText(rightChar, rect.right - 10f * density, gy, guideTextPaint)
        }

        val downChar = map[FlickDirection.DOWN]
        if (!downChar.isNullOrEmpty()) {
            guideTextPaint.color = if (currentDirection == FlickDirection.DOWN) colors.accent else colors.textSecondary
            val gy = rect.bottom - 5f * density
            canvas.drawText(downChar, centerX, gy, guideTextPaint)
        }
    }

    /**
     * TalkBackでキーの後に読み上げる補足説明を返す。指で探索中は短い名前だけを読み、少し止まると操作方法を伝える。
     */
    private fun accessibilityHint(currentSpec: KeySpec): String? = when (currentSpec) {
        is KeySpec.Kana -> KeySpeech.kanaHint(currentSpec.type)
        is KeySpec.SimpleText -> currentSpec.longPressText?.let(KeySpeech::longPressHint)
        is KeySpec.ModeSwitch -> currentSpec.longPressTarget?.let { "長押しで" + KeySpeech.modeSwitchDescription(it) }
        else -> null
    }

    /**
     * TalkBack用の日本語コンテンツ説明文を更新する。
     * 指で探索しながら入力しやすいよう、入力される文字や操作だけを短く読む。
     */
    private fun updateContentDescription() {
        val desc = when (val currentSpec = spec) {
            is KeySpec.Kana -> {
                KeySpeech.spokenText(KeyboardLayoutData.getKanaChar(currentSpec.type, FlickDirection.CENTER))
            }

            is KeySpec.SimpleText -> KeySpeech.spokenText(shiftedText(currentSpec))

            is KeySpec.Action -> {
                customActionLabel ?: when (currentSpec.action) {
                    is KeyboardAction.Text -> KeySpeech.spokenText(currentSpec.action.value)
                    is KeyboardAction.Delete -> "削除"
                    is KeyboardAction.Enter -> "確定"
                    is KeyboardAction.Space -> "空白"
                    is KeyboardAction.Convert -> "変換"
                    is KeyboardAction.TransformKana -> "濁点、半濁点、小文字"
                    is KeyboardAction.ToKatakana -> "カタカナにする"
                    is KeyboardAction.DeleteToLineStart -> "行頭まで削除"
                    is KeyboardAction.MoveCursor -> if (currentSpec.action.delta < 0) "カーソルを左へ移動" else "カーソルを右へ移動"
                }
            }

            is KeySpec.ModeSwitch -> KeySpeech.modeSwitchDescription(currentSpec.targetMode)
            is KeySpec.Shift -> when {
                isCapsLock -> "キャップスロック有効"
                isShifted -> "シフト有効"
                else -> "シフト"
            }

            is KeySpec.PageSwitch -> currentSpec.description

            null -> ""
        }
        contentDescription = desc
    }

    companion object {
        /** かなキーの文字の大きさ（dp）。Simejiの実測（約23〜24dp）に合わせる。 */
        private const val KANA_LABEL_DP = 23f

        // TalkBack用カスタムAccessibilityAction ID（0x01000000番台）
        const val ACTION_INPUT_CENTER = 0x01000001
        const val ACTION_INPUT_LEFT = 0x01000002
        const val ACTION_INPUT_UP = 0x01000003
        const val ACTION_INPUT_RIGHT = 0x01000004
        const val ACTION_INPUT_DOWN = 0x01000005

        /** 削除キーの操作メニューに出す「行頭まで削除」のID。左ドラッグと同じ操作をTalkBackから行う。 */
        const val ACTION_DELETE_TO_LINE_START = 0x01000006

        /** 削除キーをこの距離（dp）以上左へ動かして離すと行頭まで消す。 */
        private const val DELETE_DRAG_THRESHOLD_DP = 68f

        /** 削除キーを押した位置からこの距離（dp）以上上へ動かすと、左ドラッグを取り消す。 */
        private const val DELETE_DRAG_ESCAPE_DP = 60f

        // フリック方向と操作メニューのIDの対応。登録と実行で同じ表を使い、食い違いを防ぐ。
        private val FLICK_ACTION_IDS = listOf(
            FlickDirection.CENTER to ACTION_INPUT_CENTER,
            FlickDirection.LEFT to ACTION_INPUT_LEFT,
            FlickDirection.UP to ACTION_INPUT_UP,
            FlickDirection.RIGHT to ACTION_INPUT_RIGHT,
            FlickDirection.DOWN to ACTION_INPUT_DOWN,
        )
    }
}
