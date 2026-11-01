package dev.uzumi.ime.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.uzumi.ime.R

/** 機能バーとキーボード面に重ねる機能一覧の共通表示を作る。 */
class ToolbarBarViews(private val context: Context) {
    val colors = KeyboardColors.from(context)
    private val density = context.resources.displayMetrics.density

    /** アイコンと日本語名を持つ、TalkBack対応の機能タイルを作る。 */
    fun tile(
        action: ToolbarAction,
        label: String,
        enabled: Boolean = true,
        heightDp: Float = TILE_HEIGHT_DP,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = context.getString(R.string.toolbar_tile_label, action.icon, label)
        contentDescription = label
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, TILE_TEXT_DP)
        setTextColor(if (enabled) colors.text else colors.textSecondary)
        setTypeface(typeface, Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 2
        minWidth = px(MIN_TOUCH_DP)
        minHeight = px(MIN_TOUCH_DP)
        setPadding(px(3f), 0, px(3f), 0)
        background = GradientDrawable().apply {
            setColor(this@ToolbarBarViews.colors.bar)
            setStroke(px(1f), this@ToolbarBarViews.colors.divider)
        }
        isEnabled = enabled
        isClickable = enabled
        isFocusable = true
        if (enabled) setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, px(heightDp), 1f).apply {
            setMargins(px(1f), 0, px(1f), 0)
        }
    }

    /** 設定した段数に機能を分配し、各段に同じタップ領域のタイルを並べる。 */
    fun addToolbarRows(
        parent: LinearLayout,
        rows: List<List<ToolbarAction>>,
        labelFor: (ToolbarAction) -> String,
        onAction: (ToolbarAction) -> Unit,
    ) {
        parent.removeAllViews()
        rows.forEach { actions ->
            parent.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(colors.bar)
                actions.forEach { action -> addView(tile(action, labelFor(action)) { onAction(action) }) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(BAR_ROW_HEIGHT_DP)))
        }
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    companion object {
        const val BAR_ROW_HEIGHT_DP = 52f
        private const val TILE_HEIGHT_DP = 50f
        private const val TILE_TEXT_DP = 11f
        private const val MIN_TOUCH_DP = 48f
    }
}

/** 選択可能な機能を、候補バーやキーボード面と競合せずに表示する。 */
class ToolbarTilePanel(context: Context) : ScrollView(context) {
    private val views = ToolbarBarViews(context)
    private val density = context.resources.displayMetrics.density
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(views.colors.bar)
        isFillViewport = true
        addView(rows)
        visibility = View.GONE
    }

    /** 実行可能な機能だけをタイルとして並べる。 */
    fun show(
        actions: List<ToolbarAction>,
        labelFor: (ToolbarAction) -> String,
        onAction: (ToolbarAction) -> Unit,
    ) {
        rows.removeAllViews()
        actions.chunked(COLUMNS).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(views.colors.bar)
            }
            chunk.forEach { action ->
                row.addView(views.tile(action, labelFor(action), heightDp = PANEL_TILE_HEIGHT_DP) {
                    onAction(action)
                })
            }
            repeat(COLUMNS - chunk.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, px(PANEL_TILE_HEIGHT_DP), 1f))
            }
            rows.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                px(PANEL_TILE_HEIGHT_DP),
            ))
        }
        scrollTo(0, 0)
        visibility = View.VISIBLE
    }

    /** パネルを閉じて一時的なタイルを破棄する。 */
    fun hide() {
        visibility = View.GONE
        rows.removeAllViews()
    }

    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        const val COLUMNS = 3
        const val PANEL_TILE_HEIGHT_DP = 72f
    }
}
