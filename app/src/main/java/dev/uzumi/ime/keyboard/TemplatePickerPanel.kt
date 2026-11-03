package dev.uzumi.ime.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.uzumi.ime.R
import dev.uzumi.ime.dictionary.UserDictionaryEntry

/** キーボード面に重ね、登録した定型文を選んで入力する一覧を表示する。 */
class TemplatePickerPanel(context: Context) : ScrollView(context) {
    private val colors = KeyboardColors.from(context)
    private val density = context.resources.displayMetrics.density
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(colors.bar)
        isFillViewport = true
        addView(rows)
        visibility = View.GONE
    }

    /** 読みと本文の一部を表示し、タップした項目そのものを呼び出し元へ返す。 */
    fun show(entries: List<UserDictionaryEntry>, onSelect: (UserDictionaryEntry) -> Unit) {
        rows.removeAllViews()
        addHeader()
        if (entries.isEmpty()) {
            rows.addView(TextView(context).apply {
                text = context.getString(R.string.template_picker_empty)
                setTextColor(colors.textSecondary)
                setPadding(px(PANEL_PADDING_DP), px(PANEL_PADDING_DP), px(PANEL_PADDING_DP), px(PANEL_PADDING_DP))
            })
        } else {
            entries.forEach { entry -> rows.addView(templateRow(entry) { onSelect(entry) }) }
        }
        scrollTo(0, 0)
        visibility = View.VISIBLE
    }

    /** 読込み中を示す行を出し、辞書の初回読込みでUI threadを止めない。 */
    fun showLoading() {
        rows.removeAllViews()
        addHeader()
        rows.addView(TextView(context).apply {
            text = context.getString(R.string.template_picker_loading)
            setTextColor(colors.textSecondary)
            setPadding(px(PANEL_PADDING_DP), px(PANEL_PADDING_DP), px(PANEL_PADDING_DP), px(PANEL_PADDING_DP))
        })
        visibility = View.VISIBLE
    }

    /** 一覧を閉じ、本文を含む一時Viewを破棄する。 */
    fun hide() {
        visibility = View.GONE
        rows.removeAllViews()
    }

    /** 表示中かを呼び出し元へ伝え、ほかのキーボード面との重なりを管理する。 */
    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    /** 一覧名と閉じる操作を上部に置く。 */
    private fun addHeader() {
        rows.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(PANEL_PADDING_DP), 0, px(PANEL_PADDING_DP), 0)
            addView(TextView(context).apply {
                text = context.getString(R.string.template_picker_title)
                setTextColor(colors.text)
                textSize = HEADER_TEXT_SP
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, px(HEADER_HEIGHT_DP), 1f))
            addView(TextView(context).apply {
                text = context.getString(R.string.template_picker_close)
                contentDescription = context.getString(R.string.template_picker_close)
                setTextColor(colors.accent)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(px(PANEL_PADDING_DP), 0, px(PANEL_PADDING_DP), 0)
                setOnClickListener { hide() }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(HEADER_HEIGHT_DP)))
        rows.addView(View(context).apply { setBackgroundColor(colors.divider) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(DIVIDER_HEIGHT_DP)))
    }

    /** 項目の読みと本文冒頭を示すタップ行を作る。本文は選択時に省略せずそのまま返す。 */
    private fun templateRow(entry: UserDictionaryEntry, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(PANEL_PADDING_DP), px(8f), px(PANEL_PADDING_DP), px(8f))
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            setColor(colors.bar)
            setStroke(px(1f), colors.divider)
        }
        addView(TextView(context).apply {
            text = entry.reading
            setTextColor(colors.textSecondary)
            textSize = DETAIL_TEXT_SP
            maxLines = 1
        })
        addView(TextView(context).apply {
            text = entry.surface
            setTextColor(colors.text)
            textSize = BODY_TEXT_SP
            maxLines = TEMPLATE_PREVIEW_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        setOnClickListener { onClick() }
    }

    /** dpの余白や枠線を現在の画面密度に合わせる。 */
    private fun px(dp: Float): Int = (dp * density).toInt()

    /** 整数指定のdp高さを現在の画面密度に合わせる。 */
    private fun px(dp: Int): Int = (dp * density).toInt()

    private companion object {
        /** 一覧の見出しの高さと余白を保ち、端末幅に関係なく閉じる操作を残す。 */
        const val HEADER_HEIGHT_DP = 52

        /** 一覧の項目を分ける線の高さ。 */
        const val DIVIDER_HEIGHT_DP = 1

        /** 本文のプレビュー行数。選択後に入る全文は切り詰めない。 */
        const val TEMPLATE_PREVIEW_LINES = 3

        /** 一覧の左右の余白。 */
        const val PANEL_PADDING_DP = 12

        /** 一覧見出しの文字サイズ。 */
        const val HEADER_TEXT_SP = 16f

        /** 読みの補助表示の文字サイズ。 */
        const val DETAIL_TEXT_SP = 12f

        /** 定型文のプレビュー文字サイズ。 */
        const val BODY_TEXT_SP = 16f
    }
}
