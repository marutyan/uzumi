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

    /**
     * 各種機能アクションキー。
     *
     * @property longPressAction 長押しで繰り返し送る別の操作。nullなら従来どおり。指定したキーは、
     * 押した瞬間ではなく長押しにならずに離したときに[action]を送る（変換中の←→の文節の伸縮）
     */
    data class Action(
        val action: KeyboardAction,
        val label: String,
        val isAccent: Boolean = false,
        val longPressAction: KeyboardAction? = null,
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

    // フリックとみなす最小の移動距離と、中心とみなす範囲の形。設定の「入力の操作」から読む。
    private var thresholdPx = InputTuning.DEFAULT_FLICK_THRESHOLD_DP * density
    private var flickJudge = FlickJudge.CIRCLE

    // 長押しの時間（ミリ秒）。設定で0（端末に合わせる）なら端末の長押し時間を使う。
    private var longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong()

    // 連続実行中に指がこの距離以上キーの外へ出たら止める（px）。フリックの判定幅とは別の値である。
    private val repeatCancelMarginPx = REPEAT_CANCEL_MARGIN_DP * density

    // キーボード全体で共有する配色。KeyboardPanelがきせかえの設定から一度だけ作り、applyPreferencesで渡す。
    private lateinit var colors: KeyboardColors

    // きせかえの見た目。キーの形・不透明度・境界線・書体・文字の大きさ・フリックの表示を持つ。
    private var appearance = KeyboardAppearance()
    private var cornerRadius = KeyboardDimens.KEY_CORNER_DP * density
    private var baseTypeface: Typeface = Typeface.DEFAULT
    private var boldTypeface: Typeface = Typeface.DEFAULT_BOLD
    private var textScale = 1f

    // キーの地の不透明度（0〜1）。背景画像があるときだけ設定の値を使い、無ければ常に不透明にする。
    private var keyAlpha = 1f

    private var spec: KeySpec? = null
    private var onAction: ((KeyboardAction) -> Unit)? = null
    private var onModeSwitch: ((KeyboardMode) -> Unit)? = null
    private var onShiftToggle: (() -> Unit)? = null
    private var onPageSwitch: (() -> Unit)? = null

    // 押下中の文字の拡大表示を出す・消すコールバック。文字がnullなら消す。拡大表示を使わない面ではnull。
    private var onPreview: ((View, String?) -> Unit)? = null

    // かなキーのフリックの吹き出しを出す・消すコールバック。一覧が空なら消す。2つ目の値は押している方向。
    private var onFlickBubbles: ((View, List<FlickBubbleLabel>, FlickDirection) -> Unit)? = null

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

    // 空白・変換キーの横移動を、タップと区別して書記素単位の実カーソル移動にする。
    private val spaceCursorDrag = SpaceCursorDragTracker(
        startThresholdPx = SPACE_CURSOR_DRAG_THRESHOLD_DP * density,
        stepPx = SPACE_CURSOR_DRAG_STEP_DP * density,
    )
    private var cursorDragActionSent = false

    // 同じキーボードの全キーで共有する押下順。後から押したキーより先に、古い文字キーを確定するために使う。
    private var pressOrder: KeyPressOrder<KeyView>? = null

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

    // 長押しの別操作（文節の伸縮）を、押し続けている間一定の間隔で繰り返す。回数はrepeatCountで数える。
    private val longPressActionRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val action = longPressAction() ?: return
            // 同じ長押しの2回目以降は繰り返しの印を付け、評価用の計数で押下を重ねて数えないようにする。
            val repeated = (action as? KeyboardAction.ResizeSegment)?.takeIf { repeatCount > 0 }?.copy(continued = true)
            onAction?.invoke(repeated ?: action)
            haptic(if (repeatCount == 0) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
            repeatCount += 1
            repeatHandler.postDelayed(this, KeyRepeatPolicy.LONG_PRESS_ACTION_INTERVAL_MS)
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
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
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
        onFlickBubbles: ((View, List<FlickBubbleLabel>, FlickDirection) -> Unit)? = null,
    ) {
        this.onDeleteDrag = onDeleteDrag
        this.onFlickBubbles = onFlickBubbles
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

    /** キーボード全体で共有する押下順を設定する。設定しなければキーごとに離した順で確定する。 */
    fun setPressOrder(order: KeyPressOrder<KeyView>) {
        pressOrder = order
    }

    /**
     * 設定画面で選んだ振動・キー音・削除の左ドラッグの有無、入力の操作、きせかえの見た目と配色[colors]を反映する。
     */
    fun applyPreferences(preferences: KeyboardPreferences, colors: KeyboardColors) {
        vibrationEnabled = preferences.vibration
        keySoundEnabled = preferences.keySound
        deleteDragEnabled = preferences.deleteDrag
        val input = preferences.input
        thresholdPx = input.flickThresholdDp * density
        flickJudge = input.flickJudge
        longPressTimeoutMillis = input.longPressMillis.takeIf { it > 0 }?.toLong()
            ?: ViewConfiguration.getLongPressTimeout().toLong()
        this.colors = colors
        appearance = preferences.appearance
        cornerRadius = appearance.keyShape.cornerDp * density
        baseTypeface = keyTypeface(appearance.keyFont)
        boldTypeface = Typeface.create(baseTypeface, Typeface.BOLD)
        textScale = appearance.keyTextScale / 100f
        keyAlpha = if (appearance.hasBackgroundImage) appearance.keyOpacity / 100f else 1f
        borderPaint.color = colors.divider
        borderPaint.strokeWidth = maxOf(1f, density)
        invalidate()
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
        pressOrder?.release(this)
        repeatHandler.removeCallbacksAndMessages(null)
        removeCallbacks(longPressRunnable)
        spaceCursorDrag.reset()
        cursorDragActionSent = false
        if (gesture.isPressed) hidePreviews()
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
     * 押下中なら拡大表示を今の文字へ更新する。かなキーはフリックの表示の方式に従って中央の拡大表示と吹き出しを決め、
     * 文字キーは長押し成立後なら長押し文字、それ以外はShiftを反映した文字を出す。文字を入力しないキーでは何も出さない。
     */
    private fun updatePreview() {
        if (!gesture.isPressed) return
        when (val currentSpec = spec) {
            is KeySpec.Kana -> {
                val preview = flickPreview(currentSpec.type, currentDirection, appearance.flickMode)
                onPreview?.invoke(this, preview.centerText)
                onFlickBubbles?.invoke(this, preview.bubbles, currentDirection)
            }
            is KeySpec.SimpleText -> onPreview?.invoke(
                this,
                currentSpec.longPressText?.takeIf { gesture.isLongPressActive } ?: shiftedText(currentSpec),
            )
            else -> Unit
        }
    }

    /** 拡大表示とフリックの吹き出しを消す。 */
    private fun hidePreviews() {
        onPreview?.invoke(this, null)
        if (spec is KeySpec.Kana) onFlickBubbles?.invoke(this, emptyList(), FlickDirection.CENTER)
    }

    /**
     * 削除キーかどうか。削除キーは左ドラッグを受け付けるため、押した瞬間ではなく離したときに1文字消す。
     */
    private fun isDeleteKey(): Boolean = (spec as? KeySpec.Action)?.action == KeyboardAction.Delete

    /** 空白または変換キーとして短押しし、横ドラッグでは実カーソルを動かすキーか。 */
    private fun supportsSpaceCursorDrag(action: KeyboardAction): Boolean =
        action == KeyboardAction.Space || action == KeyboardAction.Convert

    /** ジェスチャーの差分を一書記素ずつIMEへ送り、同じドラッグの続きは評価で重ねて数えない。 */
    private fun dispatchCursorDrag(delta: Int) {
        if (delta == 0) return
        val direction = if (delta < 0) -1 else 1
        repeat(kotlin.math.abs(delta)) {
            if (!gesture.isPressed || activePointerId == MotionEvent.INVALID_POINTER_ID) return
            val action = KeyboardAction.MoveEditorCursor(direction, continued = cursorDragActionSent)
            cursorDragActionSent = true
            onAction?.invoke(action)
        }
    }

    /** 押下中に連続実行するアクションキーであれば、そのアクションを返す。長押しの別操作を持つキーは連続実行しない。 */
    private fun repeatableAction(): KeyboardAction? {
        val currentSpec = spec as? KeySpec.Action ?: return null
        if (currentSpec.longPressAction != null) return null
        return currentSpec.action.takeIf(KeyRepeatPolicy::isRepeatable)
    }

    /** 長押しで送る別の操作を持つアクションキーであれば、その操作を返す。 */
    private fun longPressAction(): KeyboardAction? = (spec as? KeySpec.Action)?.longPressAction

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
                // 別のキーを押したままの指があれば、このキーの処理より先にその文字を確定して押した順を保つ。
                pressOrder?.press(this, isModifier = spec is KeySpec.Shift, isTextKey = KeyView::isTextKeyPressed)
                    ?.forEach(KeyView::commitBeforeNewerKey)
                activePointerId = event.getPointerId(0)
                gesture.down(event.x, event.y)
                spaceCursorDrag.reset()
                cursorDragActionSent = false
                (spec as? KeySpec.Action)?.action
                    ?.takeIf(::supportsSpaceCursorDrag)
                    ?.let { spaceCursorDrag.down(event.x, event.y) }
                currentDirection = FlickDirection.CENTER
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                playKeySound()
                updatePreview()

                val repeatAction = repeatableAction()
                if (longPressAction() != null) {
                    // 長押しの別操作を持つキーは、長押しになったら別操作を繰り返し、ならずに離したら本来の操作を送る。
                    repeatCount = 0
                    repeatHandler.postDelayed(longPressActionRunnable, KeyRepeatPolicy.INITIAL_DELAY_MS)
                } else if (isDeleteKey()) {
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
                    // 設定の長押しの時間（既定は利用者が変更できる端末の長押し時間）に従う
                    postDelayed(longPressRunnable, longPressTimeoutMillis)
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 空白・変換キーは2本目の指で取り消し、ポインタ交代後に意図しないタップを送らない。
                if (spaceCursorDrag.isTracking) cancelPendingInput()
                // それ以外は、追跡中に触れた別の指を無視する。
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) {
                    cancelPendingInput()
                    return true
                }

                val curX = event.getX(pointerIndex)
                val curY = event.getY(pointerIndex)
                gesture.move(curX, curY)
                if (spaceCursorDrag.isTracking) dispatchCursorDrag(spaceCursorDrag.move(curX, curY))
                if (gesture.hasMovedBeyondSlop) {
                    removeCallbacks(longPressRunnable)
                }

                if (spec is KeySpec.Kana) {
                    val newDirection = determineFlickDirection(curX - gesture.startX, curY - gesture.startY, thresholdPx, flickJudge)
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
                } else if (repeatableAction() != null || longPressAction() != null) {
                    // 指がキー領域から大きく外れた場合はリピート停止
                    if (curX < -repeatCancelMarginPx || curX > width + repeatCancelMarginPx ||
                        curY < -repeatCancelMarginPx || curY > height + repeatCancelMarginPx
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
                    if (spaceCursorDrag.isTracking) {
                        // アクティブポインタの交代では空白・変換を送らず、既に動かしたカーソルだけを残す。
                        cancelPendingInput()
                    } else {
                        // アクティブポインタが離脱した場合、そのポインタの座標で入力を確定
                        handleActivePointerUp(event.getX(actionIndex), event.getY(actionIndex))
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                if (pointerId == activePointerId) {
                    handleActivePointerUp(event.getX(actionIndex), event.getY(actionIndex))
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

    /** 文字を入力するキー（文字・かな）が押下中かどうか。後から押したキーより先に確定する対象を選ぶ。 */
    private fun isTextKeyPressed(): Boolean =
        gesture.isPressed && (spec is KeySpec.SimpleText || spec is KeySpec.Kana)

    /**
     * 後から別のキーが押されたため、指を離す前に最後の指の位置で文字を確定する。フリック中のかなキーは
     * その時点の向きの文字、長押し成立後は長押し文字を入力する。この指が後で離れても何も送らない。
     */
    private fun commitBeforeNewerKey() {
        if (!isTextKeyPressed()) return
        handleActivePointerUp(gesture.lastX, gesture.lastY)
    }

    /**
     * アクティブポインタの離脱時に文字確定またはアクション実行を行う。
     *
     * 高速なDOWN→UPでMOVEイベントが届かない場合でもUP座標から方向を再計算し、
     * タッチ経路ではperformClickによる重複送信を防ぎつつAccessibilityEventを通知する。
     *
     * @param upX 指を離した位置のX座標（キー内の座標）
     * @param upY 指を離した位置のY座標（キー内の座標）
     */
    private fun handleActivePointerUp(upX: Float, upY: Float) {
        pressOrder?.release(this)
        repeatHandler.removeCallbacksAndMessages(null)
        removeCallbacks(longPressRunnable)
        hidePreviews()
        val finalSpecBeforeRelease = spec
        val cursorDragDelta = if (
            finalSpecBeforeRelease is KeySpec.Action &&
            supportsSpaceCursorDrag(finalSpecBeforeRelease.action) &&
            spaceCursorDrag.isTracking
        ) {
            spaceCursorDrag.move(upX, upY)
        } else {
            0
        }
        val cursorDragWasActive = spaceCursorDrag.hasDragged
        dispatchCursorDrag(cursorDragDelta)
        spaceCursorDrag.finish()
        val wasPressed = gesture.isPressed
        val wasLongPressed = gesture.isLongPressActive
        val deleteRelease = deleteDrag.release()
        val deleteRepeated = repeatCount > 0
        // 長押しの別操作を一度でも送ったか。送った場合は、離したときに本来の操作を送らない。
        val longPressFired = repeatCount > 0
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

        val isInside = upX in 0f..width.toFloat() && upY in 0f..height.toFloat()

        when (finalSpec) {
            is KeySpec.Kana -> {
                // MOVEが届かない速いフリックでもUP座標から正確にフリック方向を再計算する
                val finalDirection = determineFlickDirection(upX - startX, upY - startY, thresholdPx, flickJudge)
                val char = KeyboardLayoutData.getKanaChar(finalSpec.type, finalDirection)
                if (char.isNotEmpty()) {
                    onAction?.invoke(if (finalDirection == FlickDirection.CENTER) {
                        KeyboardAction.KanaTap(finalSpec.type)
                    } else {
                        KeyboardAction.Text(char)
                    })
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

            is KeySpec.Action -> if (supportsSpaceCursorDrag(finalSpec.action)) {
                // 横ドラッグが始まった後は開始点へ戻して離しても空白・変換を送らない。
                if (shouldSendSpaceCursorKeyTap(isInside, cursorDragWasActive)) {
                    onAction?.invoke(finalSpec.action)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            } else if (finalSpec.action == KeyboardAction.Delete) {
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
            } else if (finalSpec.longPressAction != null) {
                if (isInside && !longPressFired) {
                    onAction?.invoke(finalSpec.action)
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
                    onAction?.invoke(KeyboardAction.KanaTap(currentSpec.type))
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

            is KeySpec.Action -> {
                if (currentSpec.action == KeyboardAction.Delete) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_DELETE_TO_LINE_START, "行頭まで削除"))
                }
                if (supportsSpaceCursorDrag(currentSpec.action)) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_CURSOR_LEFT, "カーソルを左へ移動"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_CURSOR_RIGHT, "カーソルを右へ移動"))
                }
                // 長押しの別操作（文節の伸縮）を、TalkBackの操作メニューから一回ずつ行えるようにする
                currentSpec.longPressAction?.let { alt ->
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_LONG_CLICK,
                            KeySpeech.actionDescription(alt),
                        ),
                    )
                }
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
                if (supportsSpaceCursorDrag(currentSpec.action)) {
                    val delta = when (action) {
                        ACTION_CURSOR_LEFT -> -1
                        ACTION_CURSOR_RIGHT -> 1
                        else -> 0
                    }
                    if (delta != 0) {
                        onAction?.invoke(KeyboardAction.MoveEditorCursor(delta))
                        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                        return true
                    }
                }
                val alt = currentSpec.longPressAction
                if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && alt != null) {
                    onAction?.invoke(alt)
                    haptic(HapticFeedbackConstants.LONG_PRESS)
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

        // 背景色の決定。強調色のキー以外は、背景画像があるとき設定の不透明度で描く。
        val isActiveShift = currentSpec is KeySpec.Shift && (isShifted || isCapsLock)
        val bgColor = when {
            isAccent -> if (isKeyPressed) colors.accentPressed else colors.accent
            isActiveShift -> if (isKeyPressed) colors.functionPressed else colors.keyActive
            isFunctionKey -> if (isKeyPressed) colors.functionPressed else colors.functionBackground
            else -> if (isKeyPressed) colors.keyPressed else colors.keyBackground
        }

        // 枠なしの形では、押していない普通のキーの地を塗らず、文字だけを見せる。
        val paintsBackground = isAccent || isKeyPressed || isActiveShift || appearance.keyShape != KeyShape.FLAT
        if (paintsBackground) {
            backgroundPaint.color = if (isAccent) bgColor else ThemeColorMath.withAlpha(bgColor, keyAlpha)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, backgroundPaint)
        }
        if (appearance.keyBorder) canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)

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
                    drawCenteredText(canvas, alt, centerX, centerY, colors.accent, 24f, boldTypeface)
                } else {
                    drawCenteredText(canvas, shiftedText(currentSpec), centerX, centerY, textColor, 20f, baseTypeface)
                    alt?.let { drawCornerHint(canvas, it, rectF) }
                }
            }

            is KeySpec.Action -> {
                val label = customActionLabel ?: currentSpec.label
                drawCenteredText(canvas, label, centerX, centerY, textColor, 15f, baseTypeface)
            }

            is KeySpec.ModeSwitch -> {
                val longLabel = currentSpec.longPressLabel
                if (isLongPressed && longLabel != null) {
                    drawCenteredText(canvas, longLabel, centerX, centerY, colors.accent, 14f, boldTypeface)
                } else {
                    drawCenteredText(canvas, currentSpec.label, centerX, centerY, textColor, 14f, baseTypeface)
                    longLabel?.let { drawCornerHint(canvas, it, rectF) }
                }
            }

            is KeySpec.Shift -> {
                val label = when {
                    isCapsLock -> "Caps"
                    isShifted -> "▲"
                    else -> "⇧"
                }
                drawCenteredText(canvas, label, centerX, centerY, textColor, 16f, baseTypeface)
            }

            is KeySpec.PageSwitch -> {
                drawCenteredText(canvas, currentSpec.label, centerX, centerY, textColor, 14f, baseTypeface)
            }
        }
    }

    /** キー中央へ一つのラベルを描画する。サイズはdp単位で受け取り、設定の文字の大きさを掛ける。 */
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
        mainTextPaint.textSize = sizeDp * textScale * density
        mainTextPaint.typeface = face
        val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
        canvas.drawText(text, centerX, textY, mainTextPaint)
    }

    /** 長押しで入力できる文字をキー右上へ小さく描画し、長押しの存在を見て分かるようにする。 */
    private fun drawCornerHint(canvas: Canvas, text: String, rect: RectF) {
        guideTextPaint.textSize = 10f * density
        guideTextPaint.typeface = baseTypeface
        guideTextPaint.color = colors.textSecondary
        canvas.drawText(text, rect.right - 8f * density, rect.top + 12f * density, guideTextPaint)
    }

    /**
     * 12キーかなキーのメイン文字およびフリックガイドを描画する。「キーの中の文字」の方式では押している方向の文字を
     * キーの中に大きく出し、四方向の案内を押している間だけ出す（これまでの見た目）。ほかの方式ではキーの文字を変えない。
     * 「方向の文字を常に表示」がONなら、押していない間も四方向の案内を小さく出す。
     */
    private fun drawKanaKey(
        canvas: Canvas,
        type: KanaKeyType,
        centerX: Float,
        centerY: Float,
        rect: RectF,
    ) {
        val map = KeyboardLayoutData.getKanaDirections(type)
        val inKey = appearance.flickMode == FlickDisplayMode.IN_KEY
        val flickingInKey = inKey && currentDirection != FlickDirection.CENTER
        val selectedChar = (if (inKey) map[currentDirection] else null) ?: map[FlickDirection.CENTER] ?: ""

        // メイン文字の描画（キーの中の文字の方式でフリック中は選択中文字を表示）
        mainTextPaint.color = if (flickingInKey) colors.accent else colors.text
        mainTextPaint.textSize = (if (flickingInKey) 26f else KANA_LABEL_DP) * textScale * density
        mainTextPaint.typeface = if (flickingInKey) boldTypeface else baseTypeface
        val textY = centerY - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
        canvas.drawText(selectedChar, centerX, textY, mainTextPaint)

        // 案内文字は、キーの中の方式で押している間か、常に表示する設定のときだけ出す（普段はSimejiと同じ見た目）
        val pressedInKey = inKey && gesture.isPressed
        if (!pressedInKey && !appearance.flickGuide) return
        // 方向を強調するのはキーの中の方式で押している間だけ。ほかの方式では吹き出しが方向を示す。
        val highlighted = if (pressedInKey) currentDirection else null
        guideTextPaint.textSize = 11f * density
        guideTextPaint.typeface = baseTypeface

        val leftChar = map[FlickDirection.LEFT]
        if (!leftChar.isNullOrEmpty()) {
            guideTextPaint.color = if (highlighted == FlickDirection.LEFT) colors.accent else colors.textSecondary
            val gy = centerY - (guideTextPaint.descent() + guideTextPaint.ascent()) / 2f
            canvas.drawText(leftChar, rect.left + 10f * density, gy, guideTextPaint)
        }

        val upChar = map[FlickDirection.UP]
        if (!upChar.isNullOrEmpty()) {
            guideTextPaint.color = if (highlighted == FlickDirection.UP) colors.accent else colors.textSecondary
            val gy = rect.top + 13f * density
            canvas.drawText(upChar, centerX, gy, guideTextPaint)
        }

        val rightChar = map[FlickDirection.RIGHT]
        if (!rightChar.isNullOrEmpty()) {
            guideTextPaint.color = if (highlighted == FlickDirection.RIGHT) colors.accent else colors.textSecondary
            val gy = centerY - (guideTextPaint.descent() + guideTextPaint.ascent()) / 2f
            canvas.drawText(rightChar, rect.right - 10f * density, gy, guideTextPaint)
        }

        val downChar = map[FlickDirection.DOWN]
        if (!downChar.isNullOrEmpty()) {
            guideTextPaint.color = if (highlighted == FlickDirection.DOWN) colors.accent else colors.textSecondary
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
        is KeySpec.Action -> when {
            supportsSpaceCursorDrag(currentSpec.action) -> KeySpeech.spaceCursorDragHint(currentSpec.action)
            currentSpec.longPressAction != null -> "長押しで" + KeySpeech.actionDescription(currentSpec.longPressAction)
            else -> null
        }
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

            is KeySpec.Action -> customActionLabel ?: KeySpeech.actionDescription(currentSpec.action)

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
        /** きせかえの書体を端末の書体へ直す。明朝は端末のserif（日本語ではNoto Serif CJKなど）を使い、同梱しない。 */
        fun keyTypeface(font: KeyFont): Typeface = when (font) {
            KeyFont.GOTHIC -> Typeface.DEFAULT
            KeyFont.MINCHO -> Typeface.SERIF
        }

        /** かなキーの文字の大きさ（dp）。Simejiの実測（約23〜24dp）に合わせる。 */
        private const val KANA_LABEL_DP = 23f

        /** 連続実行中に、指がキーの外へこの距離（dp）以上出たら止める。これまでのフリック閾値と同じ20dp。 */
        private const val REPEAT_CANCEL_MARGIN_DP = 20f

        // TalkBack用カスタムAccessibilityAction ID（0x01000000番台）
        const val ACTION_INPUT_CENTER = 0x01000001
        const val ACTION_INPUT_LEFT = 0x01000002
        const val ACTION_INPUT_UP = 0x01000003
        const val ACTION_INPUT_RIGHT = 0x01000004
        const val ACTION_INPUT_DOWN = 0x01000005

        /** 削除キーの操作メニューに出す「行頭まで削除」のID。左ドラッグと同じ操作をTalkBackから行う。 */
        const val ACTION_DELETE_TO_LINE_START = 0x01000006

        /** 空白・変換キーのTalkBack操作メニューから実カーソルを動かすID。 */
        const val ACTION_CURSOR_LEFT = 0x01000007
        const val ACTION_CURSOR_RIGHT = 0x01000008

        /** 削除キーをこの距離（dp）以上左へ動かして離すと行頭まで消す。 */
        private const val DELETE_DRAG_THRESHOLD_DP = 68f

        /** 空白・変換キーの横ドラッグを始める距離と、以後1書記素動く距離。 */
        private const val SPACE_CURSOR_DRAG_THRESHOLD_DP = 18f
        private const val SPACE_CURSOR_DRAG_STEP_DP = 24f

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
