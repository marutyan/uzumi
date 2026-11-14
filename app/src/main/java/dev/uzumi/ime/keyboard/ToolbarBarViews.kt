package dev.uzumi.ime.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.uzumi.ime.R
import kotlin.math.abs

/** 機能一つの表示に必要な値。[unavailableReason]があれば今は使えず、押すとその理由を知らせる。 */
data class ToolbarItem(val action: ToolbarAction, val label: String, val unavailableReason: String? = null)

/** バーの左端のボタンの状態。機能タイルを閉じている間、開いている間、並べ替えている間の三つ。 */
enum class ToolbarMode { NONE, TILES, EDIT }

/**
 * 機能バー（左端のボタン、中央のアイコン、右端の丸）と、機能タイルの部品を作る。
 * 寸法と色は試作どおり（1dp＝試作の1px）で、候補バーと同じ配色を使う。
 */
class ToolbarBarViews(private val context: Context) {
    val colors = KeyboardColors.from(context)

    // GradientDrawableのapplyの中ではcolorsがdrawable自身の値を指すため、配色へ別名でも触れるようにする。
    private val palette = colors
    private val density = context.resources.displayMetrics.density
    private val candidateViews = CandidateBarViews(context)

    /** 左端のボタン。通常は塗りのgrid_view、タイルを開いている間は×、並べ替え中は✓（機能キーの色の丸い地に載せる）。 */
    fun leftButton(mode: ToolbarMode, onClick: () -> Unit): View {
        val (icon, description) = when (mode) {
            ToolbarMode.NONE -> R.drawable.ic_grid_view_filled to R.string.toolbar_open
            ToolbarMode.TILES -> R.drawable.ic_close to R.string.toolbar_close_tiles
            ToolbarMode.EDIT -> R.drawable.ic_check to R.string.toolbar_edit_done
        }
        val fill = colors.functionBackground.takeIf { mode != ToolbarMode.NONE }
        val tint = if (mode == ToolbarMode.NONE) colors.textSecondary else colors.text
        val button = candidateViews.iconButton(icon, context.getString(description), fill = fill, tint = tint, onClick = onClick)
        return sideHost(LEFT_WIDTH_DP).apply { addView(button, centered(button.layoutParams)) }
    }

    /** 中央の機能アイコンを、段ごとに均等な間隔で並べる。使えない機能は不透明度38%で残し、押すと理由を知らせる。 */
    fun centerRows(rows: List<List<ToolbarItem>>, onTap: (ToolbarItem) -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            rows.forEach { items ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // 各アイコンを等しい幅の枠の中央に置くと、アイコンの周りの空きが均等になる。
                    items.forEach { item ->
                        addView(FrameLayout(context).apply {
                            val slot = slot(item, onTap)
                            addView(slot, centered(slot.layoutParams))
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(CandidateBarViews.BAR_HEIGHT_DP)))
            }
        }

    /** 右端の丸いボタン。機能が無いときは点線の丸に＋を出す。 */
    fun rightButton(item: ToolbarItem?, onTap: (ToolbarItem) -> Unit): View {
        val button: View = if (item == null) emptyRound() else round(item, onTap)
        return sideHost(RIGHT_WIDTH_DP).apply { addView(button, centered(button.layoutParams)) }
    }

    /** 左端・中央・右端を横に並べたバー。 */
    fun assemble(left: View, center: View, right: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left, LinearLayout.LayoutParams(px(LEFT_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT))
        addView(center, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = px(CENTER_MARGIN_DP)
            marginEnd = px(CENTER_MARGIN_DP)
        })
        addView(right, LinearLayout.LayoutParams(px(RIGHT_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT))
    }

    /** 直径40dpの機能アイコン。 */
    private fun slot(item: ToolbarItem, onTap: (ToolbarItem) -> Unit): IconTextView =
        candidateViews.iconButton(item.action.iconRes, item.label) { onTap(item) }.apply {
            alpha = if (item.unavailableReason == null) 1f else DISABLED_ALPHA
        }

    /** 右端の機能の丸。地はキーの色、アイコンは塗りがあれば塗りを使う。 */
    private fun round(item: ToolbarItem, onTap: (ToolbarItem) -> Unit): IconTextView =
        candidateViews.iconButton(
            item.action.filledIconRes ?: item.action.iconRes,
            item.label,
            fill = colors.keyBackground,
            tint = colors.text,
        ) { onTap(item) }.apply { alpha = if (item.unavailableReason == null) 1f else DISABLED_ALPHA }

    /** 機能が無い右端。点線の丸に＋を描く（押しても何も起きない）。 */
    private fun emptyRound(): IconTextView = IconTextView(context).apply {
        setIcon(R.drawable.ic_add, colors.textSecondary)
        contentDescription = context.getString(R.string.toolbar_right_empty)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(px(2f), palette.divider, px(4f).toFloat(), px(3f).toFloat())
        }
        layoutParams = LinearLayout.LayoutParams(px(CandidateBarViews.ICON_BUTTON_DP), px(CandidateBarViews.ICON_BUTTON_DP))
    }

    /** 並べ替え中の中央のアイコン。54×32dpの丸い地（機能キーの色）に載せる。[action]がnullなら見えない空きマス。 */
    fun editSlot(action: ToolbarAction?, label: String): View {
        val size = LinearLayout.LayoutParams(px(EDIT_SLOT_WIDTH_DP), px(EDIT_SLOT_HEIGHT_DP))
        if (action == null) return View(context).apply { layoutParams = size; visibility = View.INVISIBLE }
        return IconTextView(context).apply {
            setIcon(action.iconRes, colors.text)
            contentDescription = label
            tag = action
            background = GradientDrawable().apply {
                cornerRadius = px(EDIT_SLOT_HEIGHT_DP / 2f).toFloat()
                setColor(palette.functionBackground)
            }
            layoutParams = size
        }
    }

    /** 並べ替え中の中央。[host]がバーの中央いっぱいの領域、[frame]が強調色の線で囲む高さ44dpの枠。 */
    class EditCenter(val host: View, val frame: View)

    /** 並べ替え中の右端。[host]が右端の枠の領域、[ring]が強調色の輪。 */
    class EditRight(val host: View, val ring: View)

    /** 並べ替え中の中央の枠。強調色の2dpの線で囲み、満杯で入れられないときは赤くする。 */
    fun editCenter(slots: List<View>, full: Boolean): EditCenter {
        val frame = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(EDIT_FRAME_HEIGHT_DP / 2f).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(px(OUTLINE_DP), if (full) palette.danger else palette.accent)
            }
            // アイコンの周りの空きを均等にするため、両端と各アイコンの間に同じ重みの空きを置く。
            slots.forEach { slot ->
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(slot)
            }
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
        val host = FrameLayout(context).apply {
            addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(EDIT_FRAME_HEIGHT_DP), Gravity.CENTER_VERTICAL))
        }
        return EditCenter(host, frame)
    }

    /** 並べ替え中の右端。直径40dpの丸を強調色の2dpの輪で囲み、ドラッグ先に重なっている間は選択色にする。 */
    fun editRight(action: ToolbarAction?, label: String, highlighted: Boolean, hole: Boolean): EditRight {
        val diameter = px(CandidateBarViews.ICON_BUTTON_DP)
        val inner: View = when {
            hole -> View(context).apply { visibility = View.INVISIBLE }
            action == null -> emptyRound()
            else -> IconTextView(context).apply {
                setIcon(action.filledIconRes ?: action.iconRes, colors.text)
                contentDescription = label
                tag = action
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (highlighted) palette.keyActive else palette.keyBackground)
                }
            }
        }
        val ring = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(px(OUTLINE_DP), palette.accent)
            }
            addView(inner, FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER))
        }
        val ringSize = px(CandidateBarViews.ICON_BUTTON_DP + 2 * (OUTLINE_DP + RING_GAP_DP))
        val host = sideHost(RIGHT_WIDTH_DP).apply { addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER)) }
        return EditRight(host, ring)
    }

    /** 左右の端のボタンを中央に置く、幅[widthDp]の枠。 */
    private fun sideHost(widthDp: Float): FrameLayout = FrameLayout(context).apply {
        minimumWidth = px(widthDp)
    }

    private fun centered(params: ViewGroup.LayoutParams): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(params.width, params.height, Gravity.CENTER)

    /**
     * 機能タイル。アイコン（補助色）、名前、別の画面を開く機能は右に「>」を並べた、両端が丸い高さ48dpの形。
     * [muted]なら使えない機能として不透明度38%で表す。押したときの動作は呼び出し側が付ける。
     */
    fun tile(item: ToolbarItem, muted: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(TILE_START_PADDING_DP), 0, px(TILE_END_PADDING_DP), 0)
        background = GradientDrawable().apply {
            cornerRadius = px(TILE_HEIGHT_DP / 2f).toFloat()
            setColor(palette.keyBackground)
        }
        contentDescription = item.label
        tag = item.action
        isClickable = true
        isFocusable = true
        alpha = if (muted) DISABLED_ALPHA else 1f
        addTileContent(item, colors.textSecondary, colors.text)
    }

    /** ドラッグで持ち上げたタイル。影つきの濃い地に、反転した色の文字を載せる。 */
    fun ghostTile(item: ToolbarItem): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(TILE_START_PADDING_DP), 0, px(TILE_END_PADDING_DP), 0)
        background = GradientDrawable().apply {
            cornerRadius = px(TILE_HEIGHT_DP / 2f).toFloat()
            setColor(palette.textSecondary)
        }
        elevation = px(GHOST_ELEVATION_DP).toFloat()
        addTileContent(item, colors.keyboardBackground, colors.keyboardBackground)
        layoutParams = FrameLayout.LayoutParams(px(GHOST_TILE_WIDTH_DP), px(TILE_HEIGHT_DP))
    }

    /** バーの上へ来たときに縮む、直径52dpの丸いアイコン。 */
    fun ghostIcon(item: ToolbarItem): IconTextView = IconTextView(context).apply {
        setIcon(item.action.iconRes, colors.keyboardBackground)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.textSecondary)
        }
        elevation = px(GHOST_ELEVATION_DP).toFloat()
        layoutParams = FrameLayout.LayoutParams(px(GHOST_ICON_DP), px(GHOST_ICON_DP))
    }

    /** タイルの中身（アイコン、名前、「>」）を[container]へ並べる。 */
    private fun LinearLayout.addTileContent(item: ToolbarItem, iconTint: Int, textColor: Int) {
        addView(iconView(item.action.iconRes, iconTint, TILE_ICON_DP))
        addView(TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, TILE_TEXT_DP)
            setTextColor(textColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(TILE_GAP_DP) })
        if (item.action.opensScreen) addView(iconView(R.drawable.ic_chevron_right, iconTint, TILE_CHEVRON_DP))
    }

    /** アイコンだけの飾りのView。名前は親のタイルが持つため、TalkBackには読ませない。 */
    private fun iconView(resId: Int, tint: Int, sizeDp: Float): IconTextView = IconTextView(context).apply {
        setIcon(resId, tint)
        iconSizeDp = sizeDp
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    companion object {
        /** 左端のボタンの枠の幅、右端の丸の枠の幅、中央の左右の余白（dp）。 */
        const val LEFT_WIDTH_DP = 52f
        const val RIGHT_WIDTH_DP = 56f
        private const val CENTER_MARGIN_DP = 4f

        /** 並べ替え中の中央の枠の高さと、アイコンの丸い地の寸法（dp）。 */
        private const val EDIT_FRAME_HEIGHT_DP = 44f
        private const val EDIT_SLOT_WIDTH_DP = 54f
        private const val EDIT_SLOT_HEIGHT_DP = 32f

        /** 強調色の線の太さと、右端の丸と輪の間の空き（dp）。 */
        private const val OUTLINE_DP = 2f
        private const val RING_GAP_DP = 2f

        /** 使えない機能の不透明度。 */
        const val DISABLED_ALPHA = 0.38f

        /** タイルの高さ、余白、アイコンの大きさ、名前の文字の大きさ（dp）。 */
        const val TILE_HEIGHT_DP = 48f
        private const val TILE_START_PADDING_DP = 16f
        private const val TILE_END_PADDING_DP = 10f
        private const val TILE_GAP_DP = 10f
        private const val TILE_ICON_DP = 22f
        private const val TILE_CHEVRON_DP = 20f
        private const val TILE_TEXT_DP = 14f

        /** 持ち上げたタイルの幅と、丸いアイコンに縮んだときの直径、影の高さ（dp）。 */
        const val GHOST_TILE_WIDTH_DP = 214f
        const val GHOST_ICON_DP = 52f
        private const val GHOST_ELEVATION_DP = 8f
    }
}

/**
 * 機能タイルの一覧。キーボード面の位置に重ねて、2列×最大4段を1ページとして並べ、横のスワイプでページを替える。
 * 下に現在のページの点、右下に並べ替えを始める鉛筆ボタンを置く。
 */
class ToolbarTilePanel(context: Context) : FrameLayout(context) {
    private val views = ToolbarBarViews(context)
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pagesHost = FrameLayout(context)
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }

    private var items: List<ToolbarItem> = emptyList()
    private var onTap: (ToolbarItem) -> Unit = {}
    private var page = 0
    private var perPage = TILES_PER_PAGE
    private var dragDx = 0f
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var settleAnimator: ValueAnimator? = null

    init {
        setBackgroundColor(views.colors.keyboardBackground)
        visibility = View.GONE
        addView(pagesHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { bottomMargin = px(FOOT_HEIGHT_DP) })
        addView(FrameLayout(context).apply {
            addView(dots, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }, LayoutParams(LayoutParams.MATCH_PARENT, px(FOOT_HEIGHT_DP), Gravity.BOTTOM))
    }

    /** [tiles]を1ページ目から並べて開く。タイルを押すと[onTap]、鉛筆を押すと[onEdit]を呼ぶ。 */
    fun show(tiles: List<ToolbarItem>, onTap: (ToolbarItem) -> Unit, onEdit: () -> Unit) {
        items = tiles
        this.onTap = onTap
        page = 0
        dragDx = 0f
        removeView(pencil)
        pencil = createPencil(onEdit)
        addView(pencil, LayoutParams(px(PENCIL_WIDTH_DP), px(PENCIL_HEIGHT_DP), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = px(SIDE_PADDING_DP)
            bottomMargin = px((FOOT_HEIGHT_DP - PENCIL_HEIGHT_DP) / 2f)
        })
        visibility = View.VISIBLE
        rebuild()
    }

    /** 開いている間にタイルの使える・使えないが変わったとき、ページを保ったまま表示を更新する。 */
    fun update(tiles: List<ToolbarItem>) {
        if (!isShowing || tiles == items) return
        items = tiles
        rebuild()
    }

    /** 一覧を閉じて、持っているタイルを破棄する。 */
    fun hide() {
        settleAnimator?.cancel()
        visibility = View.GONE
        pagesHost.removeAllViews()
        dots.removeAllViews()
        items = emptyList()
    }

    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    private var pencil: View = View(context)

    /** 右下の鉛筆ボタン。64×30dpの丸い枠線。 */
    private fun createPencil(onEdit: () -> Unit): View = IconTextView(context).apply {
        setIcon(R.drawable.ic_edit, views.colors.textSecondary)
        iconSizeDp = PENCIL_ICON_DP
        contentDescription = context.getString(R.string.toolbar_tiles_edit)
        background = GradientDrawable().apply {
            cornerRadius = px(PENCIL_HEIGHT_DP / 2f).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(px(PENCIL_STROKE_DP), views.colors.textSecondary)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onEdit() }
    }

    /** 高さで入る段数に合わせて1ページの個数を決め、ページと点を作り直す。 */
    private fun rebuild() {
        perPage = TILES_PER_PAGE
        if (height > 0) {
            val usable = height / density - FOOT_HEIGHT_DP - TOP_PADDING_DP + ROW_GAP_DP
            val rows = (usable / (TILE_HEIGHT + ROW_GAP_DP)).toInt().coerceIn(1, TILES_PER_PAGE / TILE_COLUMNS)
            perPage = rows * TILE_COLUMNS
        }
        val pages = items.chunked(perPage).ifEmpty { listOf(emptyList()) }
        page = page.coerceIn(0, pages.lastIndex)
        pagesHost.removeAllViews()
        pages.forEach { tiles -> pagesHost.addView(pageView(tiles), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        dots.removeAllViews()
        if (pages.size > 1) {
            pages.indices.forEach { index ->
                dots.addView(dot(index == page, index), LinearLayout.LayoutParams(px(DOT_HIT_DP), px(DOT_HIT_DP)))
            }
        }
        applyOffsets()
    }

    /** 2列のタイルを並べた1ページ。 */
    private fun pageView(tiles: List<ToolbarItem>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(SIDE_PADDING_DP), px(TOP_PADDING_DP), px(SIDE_PADDING_DP), 0)
        tiles.chunked(TILE_COLUMNS).forEachIndexed { rowIndex, row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { column, item ->
                    addView(tileFor(item), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (column > 0) marginStart = px(ROW_GAP_DP)
                    })
                }
                if (row.size < TILE_COLUMNS) {
                    addView(View(context), LinearLayout.LayoutParams(0, 1, (TILE_COLUMNS - row.size).toFloat()))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(TILE_HEIGHT)).apply {
                if (rowIndex > 0) topMargin = px(ROW_GAP_DP)
            })
        }
    }

    /** 使える機能は押すと実行し、使えない機能は薄く表示して押すと理由を知らせる。 */
    private fun tileFor(item: ToolbarItem): View = views.tile(item, muted = item.unavailableReason != null).apply {
        setOnClickListener { onTap(item) }
    }

    /** ページの位置を示す点。押すとそのページへ移る。 */
    private fun dot(current: Boolean, index: Int): View = View(context).apply {
        val color = if (current) views.colors.textSecondary else views.colors.divider
        background = InsetDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }, px((DOT_HIT_DP - DOT_DP) / 2f))
        contentDescription = context.getString(R.string.toolbar_tiles_page, index + 1)
        isClickable = true
        isFocusable = true
        setOnClickListener { settleTo(index) }
    }

    /** 現在のページと指のずれから、各ページを横へ並べる。 */
    private fun applyOffsets() {
        val width = pagesHost.width.takeIf { it > 0 } ?: width
        for (index in 0 until pagesHost.childCount) {
            pagesHost.getChildAt(index).translationX = (index - page) * width + dragDx
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && isShowing) {
            // 高さが変わると1ページに入る段数も変わるため、ページを作り直す。
            post { rebuild() }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                settleAnimator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (!swiping && pagesHost.childCount > 1 && abs(dx) > touchSlop && abs(dx) > abs(ev.y - downY)) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return swiping
    }

    // ページのスワイプだけを扱う面で、面自体はクリックの対象ではないためperformClickは要らない。
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (swiping) {
                dragDx = ev.x - downX
                applyOffsets()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false
                val moved = ev.x - downX
                val target = when {
                    ev.actionMasked == MotionEvent.ACTION_CANCEL -> page
                    moved < -width * SWIPE_FRACTION -> page + 1
                    moved > width * SWIPE_FRACTION -> page - 1
                    else -> page
                }
                settleTo(target)
            }
        }
        return swiping || ev.actionMasked == MotionEvent.ACTION_DOWN
    }

    /** [target]ページへ、指のずれから滑らかに移して点を更新する。 */
    private fun settleTo(target: Int) {
        val last = (pagesHost.childCount - 1).coerceAtLeast(0)
        val newPage = target.coerceIn(0, last)
        // 新しいページを基準にしたずれへ置き換えてから、ずれを0へ戻す。
        dragDx += (page - newPage) * (pagesHost.width.takeIf { it > 0 } ?: width)
        page = newPage
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(dragDx, 0f).apply {
            duration = SETTLE_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dragDx = it.animatedValue as Float
                applyOffsets()
            }
            start()
        }
        for (index in 0 until dots.childCount) {
            dots.getChildAt(index).background = InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (index == page) views.colors.textSecondary else views.colors.divider)
            }, px((DOT_HIT_DP - DOT_DP) / 2f))
        }
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        /** 1ページに並べるタイルの数（2列×4段）。高さが足りないときは段を減らす。 */
        const val TILES_PER_PAGE = 8
        const val TILE_COLUMNS = 2
        const val TILE_HEIGHT = ToolbarBarViews.TILE_HEIGHT_DP

        /** タイルの間隔、左右の余白、上の余白（dp）。 */
        const val ROW_GAP_DP = 8f
        const val SIDE_PADDING_DP = 20f
        const val TOP_PADDING_DP = 12f

        /** 下の欄（ページの点と鉛筆）の高さ。 */
        const val FOOT_HEIGHT_DP = 36f

        /** ページの点の大きさと、押せる範囲（dp）。 */
        const val DOT_DP = 7f
        const val DOT_HIT_DP = 24f

        /** 鉛筆ボタンの大きさ、線の太さ、アイコンの大きさ（dp）。 */
        const val PENCIL_WIDTH_DP = 64f
        const val PENCIL_HEIGHT_DP = 30f
        const val PENCIL_STROKE_DP = 1.5f
        const val PENCIL_ICON_DP = 18f

        /** 指がページ幅のこの割合を超えて動いたらページを替える。 */
        const val SWIPE_FRACTION = 0.2f

        /** ページを滑らせる時間（ミリ秒）。 */
        const val SETTLE_MILLIS = 220L
    }
}

/**
 * 並べ替え中にキーボード面の位置へ出す面。案内文と、強調色の線で囲んだ縦スクロールの2列のタイル置き場を持つ。
 * 描画だけを受け持ち、ドラッグの判定は[ToolbarEditController]が行う。
 */
class ToolbarEditPanel(context: Context) : FrameLayout(context) {
    private val views = ToolbarBarViews(context)
    private val density = context.resources.displayMetrics.density
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(GRID_SIDE_PADDING_DP), px(GRID_TOP_PADDING_DP), px(GRID_SIDE_PADDING_DP), px(GRID_BOTTOM_PADDING_DP))
    }

    /** タイル置き場のスクロール領域。ドラッグ中の位置の判定に使う。 */
    val area = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        background = GradientDrawable().apply {
            // 上の角だけ丸め、下は面の外へ出して線を見せない。
            val radius = px(AREA_CORNER_DP).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(Color.TRANSPARENT)
            setStroke(px(AREA_STROKE_DP), views.colors.accent)
        }
        addView(rows)
    }

    init {
        setBackgroundColor(views.colors.keyboardBackground)
        visibility = View.GONE
        // 案内文や左右の余白に触れても、背面のキーへタッチが届かないよう面全体で受け止める
        isClickable = true
        addView(TextView(context).apply {
            text = context.getString(R.string.toolbar_edit_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, HINT_TEXT_DP)
            setTextColor(views.colors.textSecondary)
            gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, px(HINT_HEIGHT_DP)))
        addView(area, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = px(AREA_SIDE_MARGIN_DP)
            marginEnd = px(AREA_SIDE_MARGIN_DP)
            topMargin = px(HINT_HEIGHT_DP)
            bottomMargin = -px(AREA_STROKE_DP)
        })
    }

    /** 置き場のタイルを[entries]の順に2列で並べる。nullは見えない空きマス（持ち上げたタイルの元の位置など）。 */
    fun render(entries: List<ToolbarItem?>) {
        rows.removeAllViews()
        entries.chunked(COLUMNS).forEachIndexed { rowIndex, row ->
            rows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { column, item ->
                    val cell: View = item?.let { views.tile(it, muted = false) }
                        ?: View(context).apply { visibility = View.INVISIBLE }
                    addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (column > 0) marginStart = px(GRID_GAP_DP)
                    })
                }
                if (row.size < COLUMNS) addView(View(context), LinearLayout.LayoutParams(0, 1, (COLUMNS - row.size).toFloat()))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ToolbarBarViews.TILE_HEIGHT_DP)).apply {
                if (rowIndex > 0) topMargin = px(GRID_GAP_DP)
            })
        }
    }

    /** 面を開く。 */
    fun show() {
        visibility = View.VISIBLE
        area.scrollTo(0, 0)
    }

    /** 面を閉じる。 */
    fun hide() {
        visibility = View.GONE
        rows.removeAllViews()
    }

    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    /** ドラッグ中に面の端へ近づいたとき、置き場を[deltaPx]だけ送る。 */
    fun scrollAreaBy(deltaPx: Int) {
        area.scrollBy(0, deltaPx)
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        const val COLUMNS = 2
        const val GRID_TOP_PADDING_DP = ToolbarLayout.TRAY_TOP_PADDING_DP
        const val GRID_SIDE_PADDING_DP = 6f
        const val GRID_BOTTOM_PADDING_DP = 14f
        const val GRID_GAP_DP = 8f
        const val AREA_SIDE_MARGIN_DP = 14f
        const val AREA_STROKE_DP = 2f
        const val AREA_CORNER_DP = 24f
        const val HINT_HEIGHT_DP = 34f
        const val HINT_TEXT_DP = 13f
    }
}

/**
 * 「高さの変更」で開く小さな面。キーボード面の上端に重ね、設定の「キーボードの高さ」と同じ値と範囲を、その場で変えて保存する。
 */
class KeyboardHeightPanel(context: Context) : FrameLayout(context) {
    private val colors = KeyboardColors.from(context)
    private val density = context.resources.displayMetrics.density
    private val valueLabel = TextView(context)
    // 端末のテーマ色ではなく、キーボードの強調色と区切り線の色で描く
    private val seekBar = SeekBar(context).apply {
        progressTintList = ColorStateList.valueOf(colors.accent)
        thumbTintList = ColorStateList.valueOf(colors.accent)
        progressBackgroundTintList = ColorStateList.valueOf(colors.divider)
    }

    init {
        setBackgroundColor(colors.keyboardBackground)
        visibility = View.GONE
        isClickable = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(PADDING_DP), px(PADDING_DP), px(PADDING_DP), px(PADDING_DP))
        }
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.height_panel_title)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, TITLE_TEXT_DP)
                setTextColor(colors.text)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            valueLabel.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, VALUE_TEXT_DP)
                setTextColor(colors.textSecondary)
            }
            addView(valueLabel)
        })
        column.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(SEEK_HEIGHT_DP)))
        column.addView(TextView(context).apply {
            text = context.getString(R.string.height_panel_done)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, VALUE_TEXT_DP)
            setTextColor(colors.onAccent)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = px(DONE_HEIGHT_DP / 2f).toFloat()
                // GradientDrawableにも colors があるため、面の配色を明示して参照する
                setColor(this@KeyboardHeightPanel.colors.accent)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onDone() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(DONE_HEIGHT_DP)))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private var onDone: () -> Unit = {}

    /** 現在の高さ[currentDp]で面を開く。つまみを動かすたびに[onChange]へ新しい値を渡し、「完了」で[onDone]を呼ぶ。 */
    fun show(currentDp: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
        this.onDone = onDone
        seekBar.max = KeyboardPreferences.MAX_ROW_HEIGHT_DP - KeyboardPreferences.MIN_ROW_HEIGHT_DP
        seekBar.setOnSeekBarChangeListener(null)
        seekBar.progress = currentDp - KeyboardPreferences.MIN_ROW_HEIGHT_DP
        valueLabel.text = context.getString(R.string.settings_height_value, currentDp)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = KeyboardPreferences.normalizeRowHeightDp(progress + KeyboardPreferences.MIN_ROW_HEIGHT_DP)
                valueLabel.text = context.getString(R.string.settings_height_value, value)
                if (fromUser) onChange(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        visibility = View.VISIBLE
    }

    /** 面を閉じる。 */
    fun hide() {
        visibility = View.GONE
        seekBar.setOnSeekBarChangeListener(null)
    }

    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        const val PADDING_DP = 16f
        const val TITLE_TEXT_DP = 15f
        const val VALUE_TEXT_DP = 14f
        const val SEEK_HEIGHT_DP = 48f
        const val DONE_HEIGHT_DP = 40f
    }
}
