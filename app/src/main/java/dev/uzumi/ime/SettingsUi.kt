package dev.uzumi.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import dev.uzumi.ime.keyboard.ThemeColorMath

/**
 * 起動画面と設定画面の部品（見出しの帯、分類の見出し、角丸の群と行、スイッチ、分割ボタン、注記）を、合意した試作の配色と寸法で作る。
 * 寸法はGboardの設定画面の実測（左右16dp、1行72dp、群の外側の角丸20dp・内側4dp、群の間28dp、アイコンつきの行は文字の開始84dp）に合わせる。
 * 色は`settings_*`の色resourceから読み、端末のライト／ダークで切り替わる。
 */
class SettingsUi(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    val background = context.getColor(R.color.settings_background)
    val surface = context.getColor(R.color.settings_surface)
    val surfaceHigh = context.getColor(R.color.settings_surface_high)
    val text = context.getColor(R.color.settings_text)
    val textSecondary = context.getColor(R.color.settings_text_secondary)
    val accent = context.getColor(R.color.settings_accent)
    val onAccent = context.getColor(R.color.settings_on_accent)
    val accentContainer = context.getColor(R.color.settings_accent_container)
    val onAccentContainer = context.getColor(R.color.settings_on_accent_container)
    val outline = context.getColor(R.color.settings_outline)
    val divider = context.getColor(R.color.settings_divider)
    val danger = context.getColor(R.color.settings_danger)

    /** 見出しの帯。[onBack]があれば左に戻るボタン（行の地の色の丸）を置き、なければ題名だけを左に寄せる。 */
    fun appBar(title: String, onBack: (() -> Unit)?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8), 0, px(12), 0)
        if (onBack != null) {
            addView(FrameLayout(context).apply {
                contentDescription = context.getString(R.string.settings_back)
                isClickable = true
                isFocusable = true
                // 丸い地の上に波紋を重ねるため、波紋は前景に描く。
                foreground = rippleCircle()
                setOnClickListener { onBack() }
                addView(View(context).apply { background = circle(surface) }, FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER))
                addView(icon(R.drawable.ic_arrow_back, text), FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(4) })
        } else {
            addView(View(context), LinearLayout.LayoutParams(px(12), 1))
        }
        addView(TextView(context).apply {
            this.text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(this@SettingsUi.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        minimumHeight = px(64)
    }

    /** 1画面ぶんの縦の並び。左右16dpの余白を取り、[addBlock]で足した塊の間を28dp空ける。 */
    fun page(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(8), px(16), px(40))
    }

    /** [page]へ塊を足す。2つ目からは上を28dp空ける（群の間の実測値）。 */
    fun addBlock(page: LinearLayout, block: View) {
        page.addView(block, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (page.childCount > 0) topMargin = px(BLOCK_GAP_DP)
        })
    }

    /** 分類の見出し（強調色の14sp）と、その下8dpに置く中身。 */
    fun section(title: String, content: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionTitle(title))
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(8)
        })
    }

    /** 分類の見出しの文字。 */
    fun sectionTitle(title: String): TextView = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = MEDIUM
        setTextColor(accent)
        setPadding(px(8), 0, 0, 0)
        isAccessibilityHeading = true
    }

    /**
     * 行を2dpの間隔で縦に並べた群。群の外側の角を20dp、内側の角を4dpで丸める。
     * 地はRippleDrawableの中身として持つため、押せる行では押したときの波紋が角丸の中に収まる。
     */
    fun group(rows: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) OUTER_CORNER_DP else INNER_CORNER_DP
            val bottom = if (index == rows.lastIndex) OUTER_CORNER_DP else INNER_CORNER_DP
            row.background = rowBackground(top, bottom, clickable = row.isClickable)
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = px(2)
            })
        }
    }

    /** 1行の中身。[row]の戻り値で、あとから説明行の文字を変えられるように説明のTextViewも返す。 */
    class Row(val view: LinearLayout, val title: TextView, val summary: TextView)

    /**
     * 行。[iconRes]があれば左20dpにアイコンを置いて文字を左端から84dpで始め、なければ左16dpから文字を始める。
     * [leading]があれば文字の左に16dp空けて置く（起動画面の手順の番号など）。
     * [summary]がnullなら説明行を隠す。あとで値を読んでから出す説明行は空文字で作り、[Row.summary]の文字を書き換える。
     * [danger]なら題名とアイコンを警告色にする。
     */
    fun row(
        title: String,
        summary: String? = null,
        iconRes: Int? = null,
        leading: View? = null,
        trailing: View? = null,
        danger: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): Row {
        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = MEDIUM
            setTextColor(if (danger) this@SettingsUi.danger else this@SettingsUi.text)
        }
        val summaryView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textSecondary)
            text = summary
            visibility = if (summary == null) View.GONE else View.VISIBLE
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(ROW_MIN_HEIGHT_DP)
            setPadding(px(if (iconRes != null) 20 else 16), px(12), px(16), px(12))
            if (iconRes != null) {
                addView(icon(iconRes, if (danger) this@SettingsUi.danger else textSecondary),
                    LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(24) })
            }
            if (leading != null) {
                addView(leading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = px(16)
                })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(summaryView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = px(2)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = px(16)
                })
            }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        return Row(view, titleView, summaryView)
    }

    /** 縦に積む行（キーの高さのように、題名の下へスライダーなどを置く行）。地は[group]が付ける。 */
    fun blockRow(vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(12), px(16), px(12))
        minimumHeight = px(ROW_MIN_HEIGHT_DP)
        children.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
    }

    /** 別の画面を開く行の右に置く「>」。 */
    fun chevron(): ImageView = icon(R.drawable.ic_chevron_right, textSecondary).apply {
        layoutParams = LinearLayout.LayoutParams(px(24), px(24))
    }

    /**
     * ON/OFFの行。行のどこを押しても切り替わり、切り替えるとすぐ[onChange]を呼ぶ。スイッチは[Row]の右端に置く。
     */
    fun switchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): Pair<Row, SettingsSwitch> {
        val switch = SettingsSwitch(context).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        val row = row(title, summary, trailing = switch) { switch.toggle() }
        return row to switch
    }

    /**
     * 分割ボタン。[labels]を枠線で区切って横に並べ、[selected]番目を選択中の地で示す。押すと[onSelect]へ番号を渡す。
     * [compact]なら見本の上の小さな切り替え（高さ28dp、12sp）にする。
     */
    fun segmented(
        labels: List<String>,
        selected: Int,
        enabled: Boolean = true,
        compact: Boolean = false,
        onSelect: (Int) -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val height = px(if (compact) 28 else 40)
        background = GradientDrawable().apply {
            cornerRadius = height / 2f
            setStroke(px(1).coerceAtLeast(1), outline)
        }
        clipToOutline = true
        setPadding(px(1), px(1), px(1), px(1))
        labels.forEachIndexed { index, label ->
            if (index > 0) addView(View(context).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(px(1).coerceAtLeast(1), LinearLayout.LayoutParams.MATCH_PARENT))
            val isSelected = index == selected
            addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 14f)
                typeface = MEDIUM
                setTextColor(if (isSelected) onAccentContainer else if (compact) textSecondary else this@SettingsUi.text)
                if (isSelected) setBackgroundColor(accentContainer)
                minWidth = px(if (compact) 0 else 48)
                setPadding(px(12), 0, px(12), 0)
                isEnabled = enabled
                alpha = if (enabled) 1f else DISABLED_ALPHA
                isClickable = enabled
                isFocusable = enabled
                if (enabled) setOnClickListener { if (!isSelected) onSelect(index) }
                accessibilityDelegate = checkedRole(RadioButton::class.java.name) { isSelected }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height - px(2)))
        }
    }

    /** 画面の下に置く注記（アイコンと説明文）。 */
    fun note(iconRes: Int, message: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(8), 0, px(8), 0)
        addView(icon(iconRes, textSecondary), LinearLayout.LayoutParams(px(20), px(20)).apply { marginEnd = px(12) })
        addView(TextView(context).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textSecondary)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 選択中の地の色で目立たせる案内の帯（開発用の画面の先頭、起動画面の「準備ができました」）。 */
    fun banner(iconRes: Int, title: String, summary: String? = null): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = if (summary == null) Gravity.TOP else Gravity.CENTER_VERTICAL
        setPadding(px(if (summary == null) 16 else 20), px(16), px(16), px(16))
        background = GradientDrawable().apply {
            cornerRadius = px(OUTER_CORNER_DP).toFloat()
            setColor(accentContainer)
        }
        addView(icon(iconRes, onAccentContainer), LinearLayout.LayoutParams(px(if (summary == null) 20 else 24), px(if (summary == null) 20 else 24)).apply {
            marginEnd = px(12)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (summary == null) 14f else 16f)
                if (summary != null) typeface = MEDIUM
                setTextColor(onAccentContainer)
            })
            if (summary != null) {
                addView(TextView(context).apply {
                    text = summary
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(onAccentContainer)
                })
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 強調色で塗った小さなボタン（高さ40dp）。押せないときは文字と地を薄くする。 */
    fun filledButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = MEDIUM
        setPadding(px(16), 0, px(16), 0)
        minHeight = px(40)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        accessibilityDelegate = buttonRole()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(40))
        setFilledButtonEnabled(this, true)
    }

    /** [filledButton]の押せる・押せないを切り替え、見た目を合わせる。 */
    fun setFilledButtonEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.setTextColor(if (enabled) onAccent else ThemeColorMath.withAlpha(text, 0.5f))
        button.background = RippleDrawable(
            ColorStateList.valueOf(ThemeColorMath.withAlpha(onAccent, RIPPLE_ALPHA)),
            GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(if (enabled) accent else ThemeColorMath.withAlpha(text, 0.12f))
            },
            null,
        )
    }

    /** 枠も地もない文字だけのボタン（「標準に戻す」など）。 */
    fun textButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = MEDIUM
        setTextColor(accent)
        setPadding(px(12), 0, px(12), 0)
        minHeight = px(40)
        background = RippleDrawable(ColorStateList.valueOf(ThemeColorMath.withAlpha(accent, RIPPLE_ALPHA)), null,
            GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(this@SettingsUi.text) })
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        accessibilityDelegate = buttonRole()
    }

    /** 同梱のMaterial Symbolsのアイコンを[tint]で染めた画像。名前は持たない飾りとして扱う。 */
    fun icon(resId: Int, tint: Int): ImageView = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 塗りの丸。 */
    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** 丸いボタンの押したときの波紋。 */
    private fun rippleCircle(): Drawable =
        RippleDrawable(ColorStateList.valueOf(ThemeColorMath.withAlpha(text, RIPPLE_ALPHA)), null, circle(text))

    /** 群の中の行の地。上下の角の丸みを別々に指定し、押せる行には波紋を重ねる。 */
    private fun rowBackground(topDp: Int, bottomDp: Int, clickable: Boolean): Drawable {
        val top = px(topDp).toFloat()
        val bottom = px(bottomDp).toFloat()
        val shape = GradientDrawable().apply {
            cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            setColor(surface)
        }
        if (!clickable) return shape
        return RippleDrawable(ColorStateList.valueOf(ThemeColorMath.withAlpha(text, RIPPLE_ALPHA)), shape, null)
    }

    /** TalkBackにボタンとして読ませる。 */
    private fun buttonRole(): View.AccessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    }

    /** TalkBackに[className]の部品（ラジオボタンなど）として、読み上げる時点で[checked]が返す選択の状態とともに読ませる。 */
    fun checkedRole(className: String, checked: () -> Boolean): View.AccessibilityDelegate = object : View.AccessibilityDelegate() {
        // 選択の状態を3値で渡す新しい書き方はAndroid 16からのため、対応する最も古い版（Android 11）でも使える真偽値の書き方を使う。
        @Suppress("DEPRECATION")
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = className
            info.isCheckable = true
            info.isChecked = checked()
        }
    }

    /** dpをpxへ変換する。 */
    fun px(dp: Int): Int = (dp * density).toInt()

    /** dpをpxへ変換する（小数のdp）。 */
    fun px(dp: Float): Int = (dp * density).toInt()

    companion object {
        /** 群の外側と内側の角の丸み、行の高さの下限、群の間隔（dp）。Gboardの設定画面の実測。 */
        const val OUTER_CORNER_DP = 20
        const val INNER_CORNER_DP = 4
        const val ROW_MIN_HEIGHT_DP = 72
        const val BLOCK_GAP_DP = 28

        /** 押せない部品の不透明度（Material 3の無効状態）。 */
        const val DISABLED_ALPHA = 0.38f

        /** 押したときの波紋の濃さ。 */
        private const val RIPPLE_ALPHA = 0.12f

        /** 題名と見出しの太さ（Medium）。 */
        val MEDIUM: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
}

/**
 * Material 3の形のスイッチ（幅52dp・高さ32dp、つまみに✓・×の印が入る型）。端末の部品の見た目に頼らず、設定画面の配色で描く。
 * 状態の管理とTalkBackへの伝え方はCompoundButtonに任せ、描画だけを差し替える。
 */
class SettingsSwitch(context: Context) : CompoundButton(context) {
    private val ui = SettingsUi(context)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.px(2).toFloat()
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val checkIcon: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_check)).mutate()
    private val closeIcon: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_close)).mutate()

    init {
        buttonDrawable = null
        background = null
        minimumWidth = ui.px(WIDTH_DP)
        minimumHeight = ui.px(HEIGHT_DP)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ui.px(WIDTH_DP), ui.px(HEIGHT_DP))
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        invalidate()
    }

    /** 溝（外枠2dp）と、直径24dpのつまみ、つまみの中の印を描く。押せないときは全体を薄くする。 */
    override fun onDraw(canvas: Canvas) {
        val border = borderPaint.strokeWidth
        val on = isChecked
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), if (isEnabled) 255 else (SettingsUi.DISABLED_ALPHA * 255).toInt())
        track.set(border / 2f, border / 2f, width - border / 2f, height - border / 2f)
        val radius = track.height() / 2f
        trackPaint.color = if (on) ui.accent else ui.surfaceHigh
        canvas.drawRoundRect(track, radius, radius, trackPaint)
        borderPaint.color = if (on) ui.accent else ui.outline
        canvas.drawRoundRect(track, radius, radius, borderPaint)
        // つまみの中心は、溝の内側から2dp空けた位置（オフは左、オンは右）。
        val thumbRadius = ui.px(THUMB_DP / 2f).toFloat()
        val inset = border + ui.px(2)
        val cx = if (on) width - inset - thumbRadius else inset + thumbRadius
        val cy = height / 2f
        thumbPaint.color = if (on) ui.onAccent else ui.outline
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint)
        val icon = if (on) checkIcon else closeIcon
        icon.setTint(if (on) ui.accent else ui.surfaceHigh)
        val half = ui.px(ICON_DP / 2f)
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun getAccessibilityClassName(): CharSequence = Switch::class.java.name

    private companion object {
        /** 溝の幅と高さ、つまみの直径、印の大きさ（dp）。Material 3のスイッチの寸法。 */
        const val WIDTH_DP = 52
        const val HEIGHT_DP = 32
        const val THUMB_DP = 24f
        const val ICON_DP = 16f
    }
}
