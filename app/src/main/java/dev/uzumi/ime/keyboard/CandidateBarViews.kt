package dev.uzumi.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.uzumi.ime.R
import dev.uzumi.ime.UzumiSettings

/**
 * 候補バーに並べる部品（候補、札、案内、アイコンのボタン）を、キーボードと同じ配色と寸法で作る。
 * 候補バーの中身はIMEが入力状態に応じて組み立て、見た目の決まりはここへ集める。
 */
class CandidateBarViews(private val context: Context) {
    /** きせかえの設定と端末のライト／ダーク設定に合う配色。 */
    val colors = KeyboardColors.from(context)

    // 候補の文字の書体。きせかえで選んだキーの書体にそろえる。
    private val candidateTypeface = KeyView.keyTypeface(context, UzumiSettings.keyboardAppearance(context).keyFont)
    private val density = context.resources.displayMetrics.density

    /**
     * 候補一つ。選択中の候補は選択色の角丸の地で示し、指定があれば長押しメニューを開く。
     * [dividerBefore]なら左端に縦の区切り線を引く（候補の区切り線の設定）。
     */
    fun item(
        text: String,
        selected: Boolean,
        prediction: Boolean = false,
        dividerBefore: Boolean = false,
        onLongClick: (() -> Boolean)? = null,
        onClick: () -> Unit,
    ): TextView = CandidateItemView(context, if (dividerBefore) colors.divider else null).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, CANDIDATE_TEXT_DP)
        setTextColor(colors.text)
        typeface = candidateTypeface
        gravity = Gravity.CENTER
        setPadding(px(ITEM_PADDING_DP), 0, px(ITEM_PADDING_DP), 0)
        minWidth = px(MIN_ITEM_WIDTH_DP)
        val fill = when {
            selected -> colors.keyActive
            // 予測は選ぶとすぐ確定するため、強調色を薄く敷いて、選ぶだけの通常候補と見分けられるようにする。
            prediction -> (PREDICTION_TINT_ALPHA shl 24) or (colors.accent and 0x00FFFFFF)
            else -> null
        }
        if (fill != null) {
            // 地は上下を5dp内側へ寄せ、バーいっぱいに塗らずに角丸の札として見せる。
            val shape = GradientDrawable().apply {
                cornerRadius = px(if (selected) SELECTED_CORNER_DP else PREDICTION_CORNER_DP).toFloat()
                setColor(fill)
            }
            background = InsetDrawable(shape, 0, px(SELECTED_VERTICAL_INSET_DP), 0, px(SELECTED_VERTICAL_INSET_DP))
        }
        isClickable = true
        isFocusable = true
        if (onLongClick != null) setOnLongClickListener { onLongClick() }
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
        typeface = candidateTypeface
        val palette = this@CandidateBarViews.colors
        setTextColor(if (emphasized) palette.accent else palette.textSecondary)
        if (emphasized) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(px(12f), 0, px(12f), 0)
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

    /** 候補が出ない理由（機密入力・互換入力・辞書なし）を知らせる、小さな案内。 */
    fun hint(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, HINT_TEXT_DP)
        setTextColor(colors.textSecondary)
        typeface = candidateTypeface
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(px(12f), 0, px(12f), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    /** バーの端に置く、短い文字のボタン（訂正中に入力位置へ戻る「末尾」）。 */
    fun symbolButton(symbol: String, description: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = symbol
        contentDescription = description
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, SYMBOL_TEXT_DP)
        setTextColor(colors.accent)
        typeface = candidateTypeface
        gravity = Gravity.CENTER
        minWidth = px(MIN_TOUCH_DP)
        setPadding(px(8f), 0, px(8f), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    /**
     * アイコンだけのボタン。押下域は直径[sizeDp]の丸で、[fill]があれば丸い地を敷く。
     * 名前はTalkBack用にcontentDescriptionへ入れ、アクセシビリティの木ではTextViewとして読ませる。
     */
    fun iconButton(
        iconRes: Int,
        description: String,
        sizeDp: Float = ICON_BUTTON_DP,
        fill: Int? = null,
        tint: Int = colors.textSecondary,
        onClick: () -> Unit,
    ): IconTextView = IconTextView(context).apply {
        setIcon(iconRes, tint)
        contentDescription = description
        background = circleBackground(fill)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    }

    /** 丸い地（[fill]、なければ透明）に、押したときの丸い波紋を重ねた背景を作る。 */
    fun circleBackground(fill: Int?): Drawable {
        val circle = if (fill != null) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) } else null
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
        return RippleDrawable(ColorStateList.valueOf(ThemeColorMath.withAlpha(colors.text, RIPPLE_ALPHA)), circle, mask)
    }

    /** 候補とバーの端の操作を分ける縦線。上下は14dp内側に収める。 */
    fun divider(): View = View(context).apply {
        setBackgroundColor(colors.divider)
        layoutParams = LinearLayout.LayoutParams(px(1f), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            setMargins(0, px(DIVIDER_INSET_DP), 0, px(DIVIDER_INSET_DP))
        }
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    /** 候補バーの寸法（dp）。 */
    companion object {
        /** バー1段の高さ。Gboardの実測（47dp）に近い48dp。 */
        const val BAR_HEIGHT_DP = 48f

        /** 候補の文字の大きさ。 */
        private const val CANDIDATE_TEXT_DP = 17f

        /** 候補の最小の幅と左右の余白。区切り線の有無にかかわらず同じ値にして、詰めない。 */
        private const val MIN_ITEM_WIDTH_DP = 78f
        private const val ITEM_PADDING_DP = 14f

        /** 選択中の候補の地の角丸と、上下の内側への寄せ。 */
        private const val SELECTED_CORNER_DP = 10f
        private const val SELECTED_VERTICAL_INSET_DP = 5f

        /** 縦の区切り線の上下の内側への寄せ。 */
        private const val DIVIDER_INSET_DP = 14f

        /** 予測の候補の背景に敷く強調色の不透明度（0〜255）。文字を読みやすく保つ薄さにする。 */
        private const val PREDICTION_TINT_ALPHA = 0x33

        /** 予測の候補の背景の角の丸み（dp）。 */
        private const val PREDICTION_CORNER_DP = 8f

        /** 札の文字の大きさ。 */
        private const val CHIP_TEXT_DP = 14f

        /** 案内の文字の大きさ。候補が出ない理由を邪魔にならず読める小ささにする。 */
        private const val HINT_TEXT_DP = 12f

        /** 文字のボタンの大きさ。 */
        private const val SYMBOL_TEXT_DP = 14f

        /** タップ領域の幅の下限。 */
        private const val MIN_TOUCH_DP = 48f

        /** アイコンボタンの丸の直径。 */
        const val ICON_BUTTON_DP = 40f

        /** 押したときの波紋の濃さ（0〜255）。 */
        private const val RIPPLE_ALPHA = 0.12f
    }
}

/** 候補一つを描くTextView。[dividerColor]があれば、左端に上下14dp内側の縦の区切り線を描く。 */
// 色を引数に取るプログラム生成専用のViewで、XMLからは生成しない。
@SuppressLint("ViewConstructor")
private class CandidateItemView(context: Context, private val dividerColor: Int?) : TextView(context) {
    private val density = context.resources.displayMetrics.density
    private val dividerPaint = Paint().apply {
        color = dividerColor ?: Color.TRANSPARENT
        strokeWidth = maxOf(1f, density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dividerColor == null) return
        val inset = DIVIDER_INSET_DP * density
        canvas.drawLine(dividerPaint.strokeWidth / 2f, inset, dividerPaint.strokeWidth / 2f, height - inset, dividerPaint)
    }

    private companion object {
        const val DIVIDER_INSET_DP = 14f
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

    /** 候補[values]を並べ、[dividerIndexes]の各位置の前に区切りを入れる。[selected]を示し、押された元indexを[onPick]へ渡す。 */
    fun show(
        values: List<String>,
        selected: Int,
        dividerIndexes: Set<Int> = emptySet(),
        predictionIndexes: Set<Int> = emptySet(),
        onLongClick: ((Int) -> Boolean)? = null,
        onPick: (Int) -> Unit,
    ) {
        rows.removeAllViews()
        values.chunked(COLUMNS).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(views.colors.bar)
            }
            chunk.forEachIndexed { column, value ->
                val index = rowIndex * COLUMNS + column
                val sectionBreak = index in dividerIndexes
                if (sectionBreak) row.addView(views.divider())
                row.addView(views.item(
                    value,
                    index == selected,
                    index in predictionIndexes,
                    // 列の間には縦の区切り線を引く。選択中の候補の両脇と、区分けの線の直後には引かない。
                    dividerBefore = column > 0 && !sectionBreak && index != selected && index - 1 != selected,
                    onLongClick = onLongClick?.let { callback -> { callback(index) } },
                ) { onPick(index) }.apply {
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

/** キーボード面に重ね、候補の学習削除・非表示・取消を選ぶ小さなメニュー。 */
class CandidateActionPanel(context: Context) : FrameLayout(context) {
    private val views = CandidateBarViews(context)
    private val density = context.resources.displayMetrics.density
    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(views.colors.bar)
        elevation = px(8f).toFloat()
    }

    init {
        setBackgroundColor(0x66000000)
        isClickable = true
        setOnClickListener { hide() }
        addView(menu, FrameLayout.LayoutParams(px(MENU_WIDTH_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        visibility = View.GONE
    }

    /** 学習行がある候補だけ削除項目を出し、非表示と取消の操作を並べる。 */
    fun show(
        canDeleteLearning: Boolean,
        onDeleteLearning: () -> Unit,
        onHideCandidate: () -> Unit,
    ) {
        menu.removeAllViews()
        if (canDeleteLearning) menu.addView(action(context.getString(R.string.candidate_action_delete_learning), onDeleteLearning))
        menu.addView(action(context.getString(R.string.candidate_action_hide), onHideCandidate))
        menu.addView(action(context.getString(R.string.candidate_action_cancel), ::hide))
        visibility = View.VISIBLE
        bringToFront()
    }

    /** メニューを閉じて候補情報を破棄する。 */
    fun hide() {
        visibility = View.GONE
        menu.removeAllViews()
    }

    /** タップしやすい行のメニュー項目を作る。 */
    private fun action(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(views.colors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MENU_TEXT_SP)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(18f), 0, px(18f), 0)
        minHeight = px(MENU_ROW_HEIGHT_DP)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            hide()
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** メニューの寸法を端末の密度に合わせてpxへ変換する。 */
    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        /** 候補操作メニューの幅（dp）。 */
        const val MENU_WIDTH_DP = 232f

        /** 候補操作メニューの各行の高さ（dp）。 */
        const val MENU_ROW_HEIGHT_DP = 52f

        /** 候補操作メニューの文字サイズ（sp）。 */
        const val MENU_TEXT_SP = 14f
    }
}
