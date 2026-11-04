package dev.uzumi.ime.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.uzumi.ime.R
import dev.uzumi.ime.clipboard.ClipboardHistoryItem

/** キーボード面へ重ね、保存済みclipの貼り付け・固定・削除を行う履歴一覧を表示する。 */
class ClipboardHistoryPanel(context: Context) : ScrollView(context) {
    private val colors = KeyboardColors.from(context)
    private val density = context.resources.displayMetrics.density
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(colors.bar)
        isFillViewport = true
        addView(rows)
        visibility = View.GONE
    }

    /** 固定履歴と通常履歴を分け、各行の操作と全削除入口を組み立てる。 */
    fun show(
        items: List<ClipboardHistoryItem>,
        onPaste: (ClipboardHistoryItem) -> Unit,
        onPin: (ClipboardHistoryItem, Boolean) -> Unit,
        onDelete: (ClipboardHistoryItem) -> Unit,
        onClearAll: () -> Unit,
        onClose: () -> Unit,
    ) {
        rows.removeAllViews()
        clearConfirmation = null
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12f), 0, px(8f), 0)
            setBackgroundColor(colors.bar)
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.clipboard_history_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.text)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, px(56f), 1f))
        header.addView(control(R.string.clipboard_history_clear, "⌫", enabled = items.isNotEmpty(), onClick = onClearAll))
        header.addView(control(R.string.clipboard_history_close, "∨", onClick = onClose))
        rows.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56f)))
        rows.addView(divider())

        val pinned = items.filter { it.pinned }
        val recent = items.filterNot { it.pinned }
        if (pinned.isNotEmpty()) addSection(R.string.clipboard_history_pinned, pinned, onPaste, onPin, onDelete)
        if (recent.isNotEmpty()) addSection(R.string.clipboard_history_recent, recent, onPaste, onPin, onDelete)
        if (items.isEmpty()) {
            rows.addView(TextView(context).apply {
                text = context.getString(R.string.clipboard_history_empty)
                setTextColor(colors.textSecondary)
                gravity = Gravity.CENTER
                setPadding(px(16f), px(20f), px(16f), px(20f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(64f)))
        }
        scrollTo(0, 0)
        visibility = View.VISIBLE
    }

    /** 履歴一覧を閉じ、表示中の本文Viewを親から外す。 */
    fun hide() {
        visibility = View.GONE
        rows.removeAllViews()
        clearConfirmation = null
    }

    /** 履歴の表示状態を返し、他の重ね表示と切り替える判断に使う。 */
    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    /** 誤操作を防ぐ確認行をパネル内へ出し、削除か取消のどちらかを選ばせる。 */
    fun showClearConfirmation(onConfirm: () -> Unit) {
        clearConfirmation?.let(rows::removeView)
        val confirmation = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8f), px(4f), px(8f), px(4f))
            setBackgroundColor(colors.bar)
        }
        confirmation.addView(TextView(context).apply {
            text = context.getString(R.string.clipboard_history_clear_confirm)
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, px(56f), 1f))
        confirmation.addView(textAction(R.string.clipboard_history_confirm_delete) {
            onConfirm()
            clearConfirmation?.let(rows::removeView)
            clearConfirmation = null
        })
        confirmation.addView(textAction(R.string.clipboard_history_cancel) {
            rows.removeView(confirmation)
            clearConfirmation = null
        })
        rows.addView(confirmation, 1.coerceAtMost(rows.childCount), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            px(64f),
        ))
        clearConfirmation = confirmation
    }

    /** 履歴の固定・通常区分と行操作を表示する。 */
    private fun addSection(
        titleId: Int,
        items: List<ClipboardHistoryItem>,
        onPaste: (ClipboardHistoryItem) -> Unit,
        onPin: (ClipboardHistoryItem, Boolean) -> Unit,
        onDelete: (ClipboardHistoryItem) -> Unit,
    ) {
        rows.addView(TextView(context).apply {
            text = context.getString(titleId)
            setTextColor(colors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(px(12f), px(8f), px(12f), px(4f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(36f)))
        items.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(colors.bar)
            }
            val text = TextView(context).apply {
                this.text = item.text
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12f), px(6f), px(8f), px(6f))
                isFocusable = true
                isClickable = true
                contentDescription = context.getString(R.string.clipboard_history_paste_item)
                setOnClickListener { onPaste(item) }
                setOnLongClickListener {
                    onPin(item, !item.pinned)
                    true
                }
            }
            row.addView(text, LinearLayout.LayoutParams(0, px(64f), 1f))
            row.addView(control(
                if (item.pinned) R.string.clipboard_history_unpin else R.string.clipboard_history_pin,
                if (item.pinned) "📌" else "☆",
            ) { onPin(item, !item.pinned) })
            row.addView(control(R.string.clipboard_history_delete, "✕") { onDelete(item) })
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(64f)))
            rows.addView(divider())
        }
    }

    /** アクセシビリティ説明付きの固定幅操作ボタンを作る。 */
    private fun control(descriptionId: Int, symbol: String, enabled: Boolean = true, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = symbol
            contentDescription = context.getString(descriptionId)
            setTextColor(if (enabled) colors.text else colors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            minHeight = px(48f)
            minWidth = px(48f)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = true
            setOnClickListener(if (enabled) View.OnClickListener { onClick() } else null)
        }

    /** 確認行の文字ボタンを作り、内容と操作を読み上げ可能にする。 */
    private fun textAction(labelId: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = context.getString(labelId)
        contentDescription = context.getString(labelId)
        setTextColor(colors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minWidth = px(64f)
        minHeight = px(48f)
        setPadding(px(6f), 0, px(6f), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** 行の境界を色テーマに合わせて示す。 */
    private fun divider(): View = View(context).apply {
        setBackgroundColor(colors.divider)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1f))
    }

    /** dp指定の寸法を端末密度に変換する。 */
    private fun px(dp: Float): Int = (dp * density).toInt()

    private var clearConfirmation: View? = null
}
