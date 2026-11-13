package dev.uzumi.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * アイコンだけを描くボタン。自動測定の道具がアクセシビリティの木でボタンをTextViewとして読むため、
 * ImageViewにせずTextViewのまま中央へアイコンを描く。名前はcontentDescriptionで伝える。
 */
class IconTextView(context: Context) : TextView(context) {
    private var icon: Drawable? = null
    private val density = context.resources.displayMetrics.density

    /** アイコンの一辺（dp）。既定はMaterial Symbolsの標準の24dp。 */
    var iconSizeDp: Float = ICON_SIZE_DP
        set(value) {
            field = value
            invalidate()
        }

    /** アイコンのresourceを[tint]の色に染めて表示する。 */
    fun setIcon(resId: Int, tint: Int) {
        icon = requireNotNull(context.getDrawable(resId)).mutate().also { it.setTint(tint) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = icon ?: return
        val sizePx = (iconSizeDp * density).toInt()
        val left = (width - sizePx) / 2
        val top = (height - sizePx) / 2
        drawable.setBounds(left, top, left + sizePx, top + sizePx)
        drawable.draw(canvas)
    }

    private companion object {
        /** アイコンの大きさ（dp）。Material Symbolsの標準の大きさ。 */
        const val ICON_SIZE_DP = 24f
    }
}
