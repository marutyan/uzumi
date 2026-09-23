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

/**
 * 候補バーに並べる部品（候補、札、案内、記号のボタン）を、キーボードと同じ配色と寸法で作る。
 * 候補バーの中身はIMEが入力状態に応じて組み立て、見た目の決まりはここへ集める。
 */
class CandidateBarViews(private val context: Context) {
    /** 端末のライト／ダーク設定に合う配色。 */
    val colors = KeyboardColors.from(context)
    private val density = context.resources.displayMetrics.density

    /** 候補一つ。選択中の候補は背景色で示す。 */
    fun item(text: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, CANDIDATE_TEXT_DP)
        setTextColor(colors.text)
        gravity = Gravity.CENTER
        setPadding(px(12f), 0, px(12f), 0)
        minWidth = px(MIN_TOUCH_DP)
        if (selected) setBackgroundColor(colors.keyActive)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    /**
     * 角の丸い小さな札。[emphasized]なら強調色の枠と文字（「元に戻す」）、そうでなければ控えめな表示（読みなど）にする。
     * [onClick]がnullなら押せない表示だけの札になる。
     */
    fun chip(text: String, description: String?, emphasized: Boolean, onClick: (() -> Unit)?): TextView = TextView(context).apply {
        this.text = text
        contentDescription = description
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, CHIP_TEXT_DP)
        val palette = this@CandidateBarViews.colors
        setTextColor(if (emphasized) palette.accent else palette.textSecondary)
        if (emphasized) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(px(10f), 0, px(10f), 0)
        background = GradientDrawable().apply {
            cornerRadius = px(17f).toFloat()
            setStroke(px(1f), if (emphasized) palette.accent else palette.divider)
        }
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(34f)).apply {
            setMargins(px(6f), 0, px(2f), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    /** 候補が無いときの短い案内。 */
    fun hint(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, HINT_TEXT_DP)
        setTextColor(colors.textSecondary)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(10f), 0, px(10f), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    /** バーの端に置く、記号一文字のボタン（設定、閉じる、候補一覧の開閉、末尾へ戻る）。 */
    fun symbolButton(symbol: String, description: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = symbol
        contentDescription = description
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, SYMBOL_TEXT_DP)
        setTextColor(colors.text)
        gravity = Gravity.CENTER
        minWidth = px(MIN_TOUCH_DP)
        setPadding(px(8f), 0, px(8f), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    /** 候補とバーの端の操作を分ける縦線。 */
    fun divider(): View = View(context).apply {
        setBackgroundColor(colors.divider)
        layoutParams = LinearLayout.LayoutParams(px(1f), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            setMargins(0, px(10f), 0, px(10f))
        }
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    /** 候補バーの寸法（dp）。 */
    companion object {
        /** 候補バーの高さ。Simejiの実測52.6dpに合わせる。 */
        const val BAR_HEIGHT_DP = 52f

        /** 候補の文字の大きさ。 */
        private const val CANDIDATE_TEXT_DP = 16f

        /** 札の文字の大きさ。 */
        private const val CHIP_TEXT_DP = 14f

        /** 案内の文字の大きさ。 */
        private const val HINT_TEXT_DP = 13f

        /** 記号のボタンの文字の大きさ。 */
        private const val SYMBOL_TEXT_DP = 18f

        /** タップ領域の幅の下限。 */
        private const val MIN_TOUCH_DP = 48f
    }
}

/**
 * 候補バーの∨で開く候補一覧。キーボードの面を覆って4列に並べ、長い候補列でも一画面で選べるようにする（Simejiと同じ）。
 */
class CandidateGridView(context: Context) : ScrollView(context) {
    private val views = CandidateBarViews(context)
    private val density = context.resources.displayMetrics.density
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(views.colors.bar)
        addView(rows)
        visibility = View.GONE
    }

    /** 候補[values]を並べて表示する。[selected]番目を選択中として示し、押された候補の番号を[onPick]へ渡す。 */
    fun show(values: List<String>, selected: Int, onPick: (Int) -> Unit) {
        rows.removeAllViews()
        values.chunked(COLUMNS).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(views.colors.bar)
            }
            chunk.forEachIndexed { column, value ->
                val index = rowIndex * COLUMNS + column
                row.addView(views.item(value, index == selected) { onPick(index) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    maxLines = 1
                })
            }
            // 最終行の空き列も同じ幅を保つ
            repeat(COLUMNS - chunk.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (ROW_HEIGHT_DP * density).toInt()))
            rows.addView(View(context).apply { setBackgroundColor(views.colors.divider) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        scrollTo(0, 0)
        visibility = View.VISIBLE
    }

    /** 一覧を閉じる。 */
    fun hide() {
        visibility = View.GONE
        rows.removeAllViews()
    }

    /** 開いているか。 */
    val isShowing: Boolean
        get() = visibility == View.VISIBLE

    private companion object {
        /** 1行に並べる候補の数。 */
        const val COLUMNS = 4

        /** 1行の高さ（dp）。 */
        const val ROW_HEIGHT_DP = 52f
    }
}
