package dev.uzumi.ime.keyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 拡大表示を置く位置（px、入力Viewの左上が原点）。
 */
data class PreviewPlacement(val left: Float, val top: Float)

/**
 * 押したキーの拡大表示の位置を決める。キーの真上に隙間を空けて置き、左右と上端は入力Viewの内側へ収める。
 * 上端に収まらない最上段のキーでは、表示を下へずらして候補バーへ重ねる。
 */
fun placeKeyPreview(
    keyLeft: Float,
    keyTop: Float,
    keyWidth: Float,
    containerWidth: Float,
    previewWidth: Float,
    previewHeight: Float,
    gap: Float,
): PreviewPlacement {
    val maxLeft = (containerWidth - previewWidth).coerceAtLeast(0f)
    val left = (keyLeft + (keyWidth - previewWidth) / 2f).coerceIn(0f, maxLeft)
    val top = (keyTop - gap - previewHeight).coerceAtLeast(0f)
    return PreviewPlacement(left, top)
}

/**
 * 押したキーの文字を、キーの上へ大きく表示する（Simejiの「ポップアップ」に当たる）。
 * 入力View全体の重ね描き層（ViewOverlay）へ描くため、キーボードの枠の外、候補バーの上にも出せる。
 */
class KeyPreviewPopup(private val host: View, private val colors: KeyboardColors) {
    private val density = host.resources.displayMetrics.density
    private val drawable = PreviewDrawable()
    private var attachedRoot: View? = null
    private val keyLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    /** キー[key]の上に[text]を表示する。表示中なら位置と文字を置き換える。 */
    fun show(key: View, text: String) {
        val root = host.rootView ?: return
        if (attachedRoot !== root) {
            hide()
            root.overlay.add(drawable)
            attachedRoot = root
        }
        key.getLocationInWindow(keyLocation)
        root.getLocationInWindow(rootLocation)
        val keyLeft = (keyLocation[0] - rootLocation[0]).toFloat()
        val keyTop = (keyLocation[1] - rootLocation[1]).toFloat()
        val width = minOf(PREVIEW_WIDTH_DP * density, key.width + PREVIEW_EXTRA_WIDTH_DP * density)
        val height = PREVIEW_HEIGHT_DP * density
        val place = placeKeyPreview(keyLeft, keyTop, key.width.toFloat(), root.width.toFloat(), width, height, PREVIEW_GAP_DP * density)
        drawable.text = text
        drawable.setBounds(place.left.toInt(), place.top.toInt(), (place.left + width).toInt(), (place.top + height).toInt())
        drawable.invalidateSelf()
    }

    /** 拡大表示を消す。 */
    fun hide() {
        attachedRoot?.overlay?.remove(drawable)
        attachedRoot = null
    }

    /** 角丸の背景と中央の文字だけを描く、拡大表示の本体。 */
    private inner class PreviewDrawable : Drawable() {
        var text: String = ""
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.popupBackground }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.onPopup
            textAlign = Paint.Align.CENTER
            textSize = PREVIEW_TEXT_DP * density
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            val radius = KeyboardDimens.KEY_CORNER_DP * density
            canvas.drawRoundRect(rect, radius, radius, background)
            val y = rect.centerY() - (label.descent() + label.ascent()) / 2f
            canvas.drawText(text, rect.centerX(), y, label)
        }

        override fun setAlpha(alpha: Int) {
            background.alpha = alpha
            label.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            background.colorFilter = colorFilter
            label.colorFilter = colorFilter
        }

        @Deprecated("Drawable.getOpacityはAPI 29で非推奨だが、minSdk 30でも実装が必要")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** 拡大表示の幅の上限（dp）。仕様の64×60dp。 */
        const val PREVIEW_WIDTH_DP = 64f

        /** 幅の狭いキー（QWERTY）では、キーの幅にこの値を足した幅にする（dp）。 */
        const val PREVIEW_EXTRA_WIDTH_DP = 12f

        /** 拡大表示の高さ（dp）。 */
        const val PREVIEW_HEIGHT_DP = 60f

        /** キーの上端と拡大表示の間（dp）。 */
        const val PREVIEW_GAP_DP = 8f

        /** 拡大表示の文字の大きさ（dp）。 */
        const val PREVIEW_TEXT_DP = 30f
    }
}

/**
 * 削除キーを左へドラッグしている間、キーの左に「離すと何が消えるか」を示す案内。
 * 閾値の手前では「1文字」、超えたら警告色で「行頭まで」と消える文字数を出す。入力欄の文字には手を加えない。
 */
class DeleteDragHint(private val host: View, private val colors: KeyboardColors) {
    private val density = host.resources.displayMetrics.density
    private val drawable = HintDrawable()
    private var attachedRoot: View? = null
    private val keyLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    /** 削除キー[key]の左に、状態[state]の案内を出す。[lineLength]は行頭までの文字数（分からなければnull）。 */
    fun show(key: View, state: DeleteDragState, lineLength: Int?) {
        if (state == DeleteDragState.NONE) {
            hide()
            return
        }
        val root = host.rootView ?: return
        if (attachedRoot !== root) {
            hide()
            root.overlay.add(drawable)
            attachedRoot = root
        }
        drawable.text = deleteDragHintText(state, lineLength)
        drawable.armed = state == DeleteDragState.ARMED
        key.getLocationInWindow(keyLocation)
        root.getLocationInWindow(rootLocation)
        val height = HINT_HEIGHT_DP * density
        val width = drawable.textWidth() + HINT_PADDING_DP * 2 * density
        val right = (keyLocation[0] - rootLocation[0]).toFloat()
        val top = (keyLocation[1] - rootLocation[1]) + (key.height - height) / 2f
        val left = (right - width).coerceAtLeast(0f)
        drawable.setBounds(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt())
        drawable.invalidateSelf()
    }

    /** 案内を消す。 */
    fun hide() {
        attachedRoot?.overlay?.remove(drawable)
        attachedRoot = null
    }

    /** 左端を丸めた札と文字を描く。 */
    private inner class HintDrawable : Drawable() {
        var text: String = ""
        var armed = false
        private val background = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = HINT_TEXT_DP * density
        }
        private val rect = RectF()

        /** 文字の幅（px）。札の幅を決めるために使う。 */
        fun textWidth(): Float = label.measureText(text)

        override fun draw(canvas: Canvas) {
            background.color = if (armed) colors.danger else colors.functionPressed
            label.color = if (armed) colors.onDanger else colors.text
            label.isFakeBoldText = armed
            rect.set(bounds)
            val radius = rect.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, background)
            val y = rect.centerY() - (label.descent() + label.ascent()) / 2f
            canvas.drawText(text, rect.centerX(), y, label)
        }

        override fun setAlpha(alpha: Int) {
            background.alpha = alpha
            label.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            background.colorFilter = colorFilter
            label.colorFilter = colorFilter
        }

        @Deprecated("Drawable.getOpacityはAPI 29で非推奨だが、minSdk 30でも実装が必要")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** 案内の札の高さ（dp）。 */
        const val HINT_HEIGHT_DP = 44f

        /** 札の左右の余白（dp）。 */
        const val HINT_PADDING_DP = 14f

        /** 案内の文字の大きさ（dp）。 */
        const val HINT_TEXT_DP = 14f
    }
}

/**
 * 削除ドラッグの案内の文言を決める。行頭までの文字数が分かる場合は、離す前に消える量を示す。
 */
fun deleteDragHintText(state: DeleteDragState, lineLength: Int?): String = when (state) {
    DeleteDragState.ARMED -> if (lineLength != null) "× 行頭まで ${lineLength}文字" else "× 行頭まで"
    DeleteDragState.DRAGGING -> "× 1文字"
    DeleteDragState.CANCELED -> "取り消し"
    DeleteDragState.NONE -> ""
}
