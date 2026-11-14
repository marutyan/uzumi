package dev.uzumi.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.uzumi.ime.keyboard.ThemePalette
import dev.uzumi.ime.keyboard.ToolbarAction
import dev.uzumi.ime.keyboard.ToolbarBarViews
import dev.uzumi.ime.keyboard.ToolbarItem
import dev.uzumi.ime.keyboard.ToolbarLayout
import dev.uzumi.ime.keyboard.ToolbarTrayArea
import dev.uzumi.ime.keyboard.centerCropRect
import kotlin.math.roundToInt

/**
 * 完成予想図の入れ物。見本のキーボードを端末の幅（[naturalWidthPx]）で組み立て、置ける幅と高さの上限（[maxHeightPx]）に
 * 収まるよう全体を同じ比率で縮めて置く。縮めても部品の並びと比率は実際のキーボードと同じになる。
 */
class ScaledPreviewBox(context: Context) : FrameLayout(context) {
    /** 見本を組み立てる幅（端末の画面の幅）。 */
    var naturalWidthPx: Int = context.resources.displayMetrics.widthPixels

    /** 見本を置ける高さの上限。 */
    var maxHeightPx: Int = Int.MAX_VALUE

    private var scale = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = if (childCount > 0) getChildAt(0) else null
        if (child == null) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            return
        }
        child.measure(
            MeasureSpec.makeMeasureSpec(naturalWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val naturalHeight = child.measuredHeight.coerceAtLeast(1)
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        scale = minOf(1f, availableWidth / naturalWidthPx.toFloat(), maxHeightPx / naturalHeight.toFloat())
        setMeasuredDimension((naturalWidthPx * scale).roundToInt(), (naturalHeight * scale).roundToInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = if (childCount > 0) getChildAt(0) else return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
    }
}

/**
 * 見本のキーの面を包み、触れても反応させない入れ物。見本は縮めて描くため、キーの吹き出しなどが正しい位置に出ないことを避ける。
 */
class TouchBlockingFrame(context: Context) : FrameLayout(context) {
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** 触れた操作をすべてここで受け止め、中のキーへ渡さない。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = true
}

/**
 * 「キーボード」の画面の「ほかの機能」。機能バーに置いていない機能のタイルを2列で並べる置き場で、
 * 見本のバーとの間のドラッグは[dev.uzumi.ime.keyboard.ToolbarEditController]が判定する。
 * 余白と行の間隔は[ToolbarLayout.trayDropIndex]の計算（上の余白10dp、1行48dp、行の間8dp）に合わせる。
 * [onTileClick]はTalkBackでタイルを選んだときと、見本が「入力中」のときに押したときに呼ぶ。
 */
class SettingsToolbarTray(
    private val context: Context,
    private val views: ToolbarBarViews,
    private val onTileClick: (ToolbarAction) -> Unit,
) : ToolbarTrayArea {
    private val density = context.resources.displayMetrics.density

    override val area: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(SIDE_PADDING_DP), px(ToolbarLayout.TRAY_TOP_PADDING_DP), px(SIDE_PADDING_DP), px(BOTTOM_PADDING_DP))
        background = GradientDrawable().apply {
            cornerRadius = px(CORNER_DP).toFloat()
            setColor(views.colors.keyboardBackground)
            setStroke(px(1f).coerceAtLeast(1), views.colors.divider)
        }
    }

    /** 置き場は画面の中に常に見えているため、開く操作は要らない。 */
    override fun show() = Unit

    override fun render(entries: List<ToolbarItem?>) {
        area.removeAllViews()
        entries.chunked(ToolbarLayout.TRAY_COLUMNS).forEachIndexed { rowIndex, row ->
            area.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { column, item ->
                    val cell: View = item?.let { tile ->
                        views.tile(tile, muted = false).apply { setOnClickListener { onTileClick(tile.action) } }
                    } ?: View(context).apply { visibility = View.INVISIBLE }
                    addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (column > 0) marginStart = px(GAP_DP)
                    })
                }
                if (row.size < ToolbarLayout.TRAY_COLUMNS) {
                    addView(View(context), LinearLayout.LayoutParams(0, 1, (ToolbarLayout.TRAY_COLUMNS - row.size).toFloat()))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ToolbarBarViews.TILE_HEIGHT_DP)).apply {
                if (rowIndex > 0) topMargin = px(GAP_DP)
            })
        }
    }

    /** 置き場は画面のスクロールの中に全体を並べるため、置き場だけを送ることはしない。 */
    override fun scrollAreaBy(deltaPx: Int) = Unit

    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        /** 置き場の左右と下の余白、タイルの間隔、角の丸み（dp）。行の間隔は[ToolbarLayout.TRAY_ROW_STRIDE_DP]と同じ8dp。 */
        const val SIDE_PADDING_DP = 12f
        const val BOTTOM_PADDING_DP = 12f
        const val GAP_DP = 8f
        const val CORNER_DP = 20f
    }
}

/**
 * テーマと背景画像のカードの縮図。地（テーマの色または写真）の上に、キーボードの下3段を小さな四角で描く（試作と同じ並び）。
 * [palette]がnullなら「写真を選ぶ」のカード（行の地に＋）にする。選んだカードは強調色の3dpの枠と中央の✓で示す。
 */
class KeyboardThumbView(context: Context) : View(context) {
    private val ui = SettingsUi(context)
    /** 描くテーマの配色。nullなら＋のカード。 */
    var palette: ThemePalette? = null

    /** 地に敷く写真。nullならテーマの地の色で塗る。 */
    var photo: Bitmap? = null

    /** 選んでいるカードか。 */
    var checked = false

    /** 左上に小さく出すアイコン（カスタムのテーマのスポイト）。 */
    var badge: Drawable? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val bounds = RectF()
    private val key = RectF()
    private val photoSource = Rect()
    private val photoTarget = Rect()
    private val checkIcon: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_check)).mutate()
    private val addIcon: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_add)).mutate()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, dp(CORNER_DP), dp(CORNER_DP), Path.Direction.CW)
    }

    /** 地、キーの縮図、枠、選択の印を順に描く。 */
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        val colors = palette
        if (colors == null) {
            canvas.drawColor(ui.surface)
            drawIcon(canvas, addIcon, ui.textSecondary, ADD_ICON_DP, width / 2f, height / 2f)
        } else {
            val image = photo
            if (image != null) {
                val crop = centerCropRect(image.width, image.height, width, height)
                photoSource.set(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height)
                photoTarget.set(0, 0, width, height)
                canvas.drawBitmap(image, photoSource, photoTarget, photoPaint)
            } else {
                canvas.drawColor(colors.background)
            }
            drawKeys(canvas, colors, if (image != null) PHOTO_KEY_ALPHA else 255)
            badge?.let { drawIcon(canvas, it, colors.text, BADGE_DP, dp(6f) + dp(BADGE_DP) / 2f, dp(6f) + dp(BADGE_DP) / 2f) }
        }
        canvas.restore()
        val strokeWidth = dp(if (checked) 3f else 1f)
        stroke.strokeWidth = strokeWidth
        stroke.color = when {
            checked -> ui.accent
            colors == null -> ui.outline
            else -> ui.divider
        }
        key.set(strokeWidth / 2f, strokeWidth / 2f, width - strokeWidth / 2f, height - strokeWidth / 2f)
        canvas.drawRoundRect(key, dp(CORNER_DP) - strokeWidth / 2f, dp(CORNER_DP) - strokeWidth / 2f, stroke)
        if (checked && colors != null) {
            fill.color = ui.accent
            fill.alpha = 255
            canvas.drawCircle(width / 2f, height / 2f, dp(CHECK_CIRCLE_DP) / 2f, fill)
            drawIcon(canvas, checkIcon, ui.onAccent, CHECK_ICON_DP, width / 2f, height / 2f)
        }
    }

    /**
     * キーボードの下3段の縮図。左右10%・下12%の余白の中に5列の格子を取り、上2段は「機能・キー×3・機能」、
     * 下の段は「機能・3列ぶんの空白キー・強調色の丸」にする。写真の上ではキーを少し透かす。
     */
    private fun drawKeys(canvas: Canvas, colors: ThemePalette, alpha: Int) {
        val left = width * SIDE_FRACTION
        val right = width - width * SIDE_FRACTION
        val bottom = height - width * BOTTOM_FRACTION
        val gap = dp(KEY_GAP_DP)
        val keyHeight = dp(KEY_HEIGHT_DP)
        val columnWidth = (right - left - gap * (COLUMNS - 1)) / COLUMNS
        val radius = dp(KEY_CORNER_DP)
        fun columnLeft(column: Int) = left + column * (columnWidth + gap)
        for (row in 0 until 3) {
            val top = bottom - (3 - row) * keyHeight - (2 - row) * gap
            if (row < 2) {
                for (column in 0 until COLUMNS) {
                    val function = column == 0 || column == COLUMNS - 1
                    drawKey(canvas, columnLeft(column), top, columnLeft(column) + columnWidth, keyHeight,
                        if (function) colors.function else colors.key, alpha, radius)
                }
            } else {
                drawKey(canvas, columnLeft(0), top, columnLeft(0) + columnWidth, keyHeight, colors.function, alpha, radius)
                drawKey(canvas, columnLeft(1), top, columnLeft(3) + columnWidth, keyHeight, colors.key, alpha, radius)
                fill.color = colors.accent
                fill.alpha = alpha
                canvas.drawCircle(right - keyHeight / 2f, top + keyHeight / 2f, keyHeight / 2f, fill)
            }
        }
    }

    /** 縮図のキー一つ。 */
    private fun drawKey(canvas: Canvas, left: Float, top: Float, right: Float, height: Float, color: Int, alpha: Int, radius: Float) {
        fill.color = color
        fill.alpha = alpha
        key.set(left, top, right, top + height)
        canvas.drawRoundRect(key, radius, radius, fill)
    }

    /** アイコンを[tint]で染め、中心を([cx], [cy])に合わせて描く。 */
    private fun drawIcon(canvas: Canvas, icon: Drawable, tint: Int, sizeDp: Float, cx: Float, cy: Float) {
        val half = dp(sizeDp) / 2f
        icon.setTint(tint)
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** カードの角の丸み、＋の大きさ、左上のアイコンの大きさ、選択の丸と✓の大きさ（dp）。試作の寸法。 */
        const val CORNER_DP = 12f
        const val ADD_ICON_DP = 32f
        const val BADGE_DP = 16f
        const val CHECK_CIRCLE_DP = 28f
        const val CHECK_ICON_DP = 18f

        /** 縮図の格子の列数、キーの高さ・間隔・角の丸み（dp）、左右と下の余白（カードの幅に対する割合）。 */
        const val COLUMNS = 5
        const val KEY_HEIGHT_DP = 7f
        const val KEY_GAP_DP = 3f
        const val KEY_CORNER_DP = 2f
        const val SIDE_FRACTION = 0.10f
        const val BOTTOM_FRACTION = 0.12f

        /** 写真の上のキーの不透明度（0〜255、試作の78%）。 */
        const val PHOTO_KEY_ALPHA = 199
    }
}
