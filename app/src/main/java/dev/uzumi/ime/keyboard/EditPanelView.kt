package dev.uzumi.ime.keyboard

import android.annotation.SuppressLint
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
import dev.uzumi.ime.editor.EditPanelAction
import dev.uzumi.ime.editor.EditPanelRules

/** キーボード面へ重ね、編集操作と選択状態をタイルで表示する。 */
// 必須コールバックを伴うプログラム生成専用Viewであり、XMLからは生成しない。
@SuppressLint("ViewConstructor")
class EditPanelView(
    context: Context,
    private val onAction: (EditPanelAction) -> Unit,
) : ScrollView(context) {
    private val colors = KeyboardColors.from(context)
    private val density = context.resources.displayMetrics.density
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(colors.bar)
        isFillViewport = true
        addView(rows)
        visibility = View.GONE
    }

    /** パネルが入力View上に表示されているかを返す。 */
    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    /** 利用可否と選択モードを反映して編集パネルを表示する。 */
    fun show(isPassword: Boolean, hasSelection: Boolean, hasClipboardText: Boolean, selectionMode: Boolean) {
        render(isPassword, hasSelection, hasClipboardText, selectionMode)
        scrollTo(0, 0)
        visibility = View.VISIBLE
    }

    /** 表示中のパネルへselectionやclipboardの変化を反映する。 */
    fun update(isPassword: Boolean, hasSelection: Boolean, hasClipboardText: Boolean, selectionMode: Boolean) {
        if (visibility == View.VISIBLE) render(isPassword, hasSelection, hasClipboardText, selectionMode)
    }

    /** パネルを閉じ、表示中のタイルを破棄する。 */
    fun hide() {
        visibility = View.GONE
        rows.removeAllViews()
    }

    /** 編集操作の順序を固定し、狭い画面ではScrollViewから全操作へ到達できるようにする。 */
    private fun render(isPassword: Boolean, hasSelection: Boolean, hasClipboardText: Boolean, selectionMode: Boolean) {
        rows.removeAllViews()
        ACTIONS.chunked(COLUMNS).forEach { actions ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(colors.bar)
            }
            actions.forEach { action ->
                val enabled = EditPanelRules.isEnabled(action, isPassword, hasSelection, hasClipboardText)
                row.addView(createTile(action, enabled, action == EditPanelAction.TOGGLE_SELECTION && selectionMode))
            }
            repeat(COLUMNS - actions.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, px(TILE_HEIGHT_DP), 1f))
            }
            rows.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                px(TILE_HEIGHT_DP),
            ))
        }
    }

    /** 編集操作のアイコン、説明、利用可否を持つタイルを作る。 */
    private fun createTile(action: EditPanelAction, enabled: Boolean, selected: Boolean): TextView {
        val (icon, label) = descriptor(action)
        return TextView(context).apply {
            text = context.getString(R.string.toolbar_tile_label, icon, context.getString(label))
            contentDescription = context.getString(label)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, TILE_TEXT_DP)
            // 選択ON（地色keyActive）でも通常の文字色を使う。keyActiveは淡い強調色で、onAccentでは読めない。
            // Shiftキーの有効表示と同じ組み合わせにする。
            setTextColor(if (enabled) colors.text else colors.textSecondary)
            setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 2
            minWidth = px(MIN_TOUCH_DP)
            minHeight = px(MIN_TOUCH_DP)
            setPadding(px(3f), 0, px(3f), 0)
            background = GradientDrawable().apply {
                setColor(if (selected) this@EditPanelView.colors.keyActive else this@EditPanelView.colors.bar)
                setStroke(px(1f), this@EditPanelView.colors.divider)
            }
            isEnabled = enabled
            isClickable = enabled
            isFocusable = true
            isSelected = selected
            alpha = if (enabled) 1f else DISABLED_ALPHA
            if (enabled) setOnClickListener { onAction(action) }
            layoutParams = LinearLayout.LayoutParams(0, px(TILE_HEIGHT_DP), 1f).apply {
                setMargins(px(1f), 0, px(1f), 0)
            }
        }
    }

    /** 各操作の表示記号と日本語リソースを返す。 */
    private fun descriptor(action: EditPanelAction): Pair<String, Int> = when (action) {
        EditPanelAction.LEFT -> "←" to R.string.edit_panel_left
        EditPanelAction.UP -> "↑" to R.string.edit_panel_up
        EditPanelAction.DOWN -> "↓" to R.string.edit_panel_down
        EditPanelAction.RIGHT -> "→" to R.string.edit_panel_right
        EditPanelAction.TOGGLE_SELECTION -> "⇧" to R.string.edit_panel_select_mode
        EditPanelAction.SELECT_ALL -> "全" to R.string.edit_panel_select_all
        EditPanelAction.COPY -> "⧉" to R.string.edit_panel_copy
        EditPanelAction.CUT -> "✂" to R.string.edit_panel_cut
        EditPanelAction.PASTE -> "▣" to R.string.edit_panel_paste
        EditPanelAction.HOME -> "Home" to R.string.edit_panel_home
        EditPanelAction.END -> "End" to R.string.edit_panel_end
        EditPanelAction.DELETE -> "⌫" to R.string.edit_panel_delete
        EditPanelAction.CLOSE -> "×" to R.string.edit_panel_close
    }

    /** dpを現在の表示密度に合わせてpxへ変換する。 */
    private fun px(dp: Float): Int = (dp * density).toInt()

    /** 編集パネルの寸法と操作順。 */
    private companion object {
        /** 1段に並べる操作タイルの数。 */
        const val COLUMNS = 4

        /** 編集パネルのタイル高。 */
        const val TILE_HEIGHT_DP = 72f

        /** タイル内の文字サイズ。 */
        const val TILE_TEXT_DP = 11f

        /** タッチ操作に必要な最小寸法。 */
        const val MIN_TOUCH_DP = 48f

        /** 無効なタイルを見分けるための透明度。 */
        const val DISABLED_ALPHA = 0.55f

        /** 編集パネルに出す全操作。 */
        val ACTIONS = listOf(
            EditPanelAction.LEFT,
            EditPanelAction.UP,
            EditPanelAction.DOWN,
            EditPanelAction.RIGHT,
            EditPanelAction.TOGGLE_SELECTION,
            EditPanelAction.SELECT_ALL,
            EditPanelAction.COPY,
            EditPanelAction.CUT,
            EditPanelAction.PASTE,
            EditPanelAction.HOME,
            EditPanelAction.END,
            EditPanelAction.DELETE,
            EditPanelAction.CLOSE,
        )
    }
}
