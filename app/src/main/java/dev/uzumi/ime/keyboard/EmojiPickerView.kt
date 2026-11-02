package dev.uzumi.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** カテゴリタブと縦スクロールの格子で絵文字・顔文字を選ぶ。 */
// 必須コールバックを伴うプログラム生成専用Viewであり、XMLからは生成しない。
@SuppressLint("ViewConstructor")
internal class EmojiPickerView(
    context: Context,
    private val catalog: EmojiPickerCatalog,
    private val rowHeightPx: Int,
    recentValues: List<String>,
    private val onSelect: (String) -> Unit,
    private val onPanelKey: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val colors = KeyboardColors.from(context)
    private val allCategories = listOf(RECENT_CATEGORY) + catalog.categories
    private val recentItems = recentValues.toMutableList()
    private val itemsByValue = catalog.items.associateBy(EmojiPickerItem::value)
    private val tabViews = mutableMapOf<String, TextView>()
    private val tabContent = LinearLayout(context)
    private val itemContent = LinearLayout(context).apply { orientation = VERTICAL }
    private val itemScroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(
            itemContent,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }
    private var selectedCategory = RECENT_CATEGORY.id

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.keyboardBackground)
        accessibilityPaneTitle = "絵文字・顔文字一覧"

        val tabs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                tabContent,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT),
            )
        }
        tabContent.orientation = HORIZONTAL
        allCategories.forEach(::addCategoryTab)

        addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx))
        addView(
            itemScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx * GRID_VISIBLE_ROWS),
        )
        updateTabAppearance()
        renderItems()
    }

    /** 入力成功後の履歴を受け取り、最近タブを開いていれば格子をすぐ更新する。 */
    fun setRecentValues(values: List<String>) {
        recentItems.clear()
        recentItems.addAll(values)
        if (selectedCategory == RECENT_CATEGORY.id) {
            itemScroll.scrollTo(0, 0)
            renderItems()
        }
    }

    /** 一覧を開くたび最近タブへ戻り、最新の履歴を先に表示する。 */
    fun showRecent() {
        selectedCategory = RECENT_CATEGORY.id
        itemScroll.scrollTo(0, 0)
        updateTabAppearance()
        renderItems()
    }

    /** 押されたカテゴリへ切り替え、操作回数と表示状態を更新する。 */
    private fun selectCategory(category: EmojiPickerCategory) {
        if (selectedCategory == category.id) return
        selectedCategory = category.id
        onPanelKey()
        itemScroll.scrollTo(0, 0)
        updateTabAppearance()
        renderItems()
    }

    /** 横並びのカテゴリボタンを作り、TalkBackにはカテゴリの意味を伝える。 */
    private fun addCategoryTab(category: EmojiPickerCategory) {
        val tab = TextView(context).apply {
            text = category.label
            contentDescription = category.spokenLabel
            gravity = Gravity.CENTER
            textSize = TAB_TEXT_SIZE_SP
            typeface = Typeface.DEFAULT
            minWidth = dp(MIN_TAB_WIDTH_DP)
            setPadding(dp(TAB_HORIZONTAL_PADDING_DP), 0, dp(TAB_HORIZONTAL_PADDING_DP), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { selectCategory(category) }
        }
        tabViews[category.id] = tab
        tabContent.addView(
            tab,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT),
        )
    }

    /** 選択中のタブを色とアクセシビリティ状態で示す。 */
    private fun updateTabAppearance() {
        tabViews.forEach { (id, tab) ->
            val selected = id == selectedCategory
            tab.isSelected = selected
            tab.setTextColor(if (selected) colors.onAccent else colors.text)
            tab.background = roundedBackground(if (selected) colors.accent else colors.functionBackground)
        }
    }

    /** 選択中のカテゴリと履歴から項目を作り、絵文字と顔文字をそれぞれの列数で並べる。 */
    private fun renderItems() {
        itemContent.removeAllViews()
        val items = if (selectedCategory == RECENT_CATEGORY.id) {
            recentItems.mapNotNull(itemsByValue::get)
        } else {
            catalog.items.filter { selectedCategory in it.categories }
        }
        if (items.isEmpty()) {
            itemContent.addView(TextView(context).apply {
                text = EMPTY_RECENTS_LABEL
                contentDescription = EMPTY_RECENTS_LABEL
                gravity = Gravity.CENTER
                minHeight = rowHeightPx
                setTextColor(colors.textSecondary)
                textSize = EMPTY_TEXT_SIZE_SP
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx))
            return
        }

        EmojiPickerGrid.rows(items, emojiColumnCount(), KAOMOJI_COLUMN_COUNT).forEach { rowItems ->
            val columns = if (rowItems.first().isKaomoji) KAOMOJI_COLUMN_COUNT else emojiColumnCount()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowItems.forEach { item -> row.addView(createItemTile(item), tileLayoutParams(item)) }
            repeat(columns - rowItems.size) {
                row.addView(
                    View(context).apply { visibility = INVISIBLE },
                    LinearLayout.LayoutParams(0, rowHeightPx, 1f),
                )
            }
            itemContent.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** 絵文字は複数列、顔文字は2列にし、狭い画面でも文字列全体を折り返して表示する。 */
    private fun createItemTile(item: EmojiPickerItem): TextView = TextView(context).apply {
        text = item.value
        contentDescription = "${item.label}を入力"
        gravity = Gravity.CENTER
        textSize = if (item.isKaomoji) KAOMOJI_TEXT_SIZE_SP else EMOJI_TEXT_SIZE_SP
        setTextColor(colors.text)
        setPadding(
            dp(ITEM_HORIZONTAL_PADDING_DP),
            dp(ITEM_VERTICAL_PADDING_DP),
            dp(ITEM_HORIZONTAL_PADDING_DP),
            dp(ITEM_VERTICAL_PADDING_DP),
        )
        minHeight = rowHeightPx
        background = roundedBackground(colors.keyBackground)
        isFocusable = true
        isClickable = true
        setOnClickListener { onSelect(item.value) }
    }

    /** 顔文字の折り返しを許し、絵文字には設定行高を保つ格子セル寸法を返す。 */
    private fun tileLayoutParams(item: EmojiPickerItem): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        if (item.isKaomoji) LinearLayout.LayoutParams.WRAP_CONTENT else rowHeightPx,
        1f,
    ).apply {
        setMargins(dp(ITEM_MARGIN_DP), dp(ITEM_MARGIN_DP), dp(ITEM_MARGIN_DP), dp(ITEM_MARGIN_DP))
    }

    /** 画面幅に合わせ、タップしやすい幅を保てる絵文字列数を返す。 */
    private fun emojiColumnCount(): Int {
        val widthDp = resources.configuration.screenWidthDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / density).toInt()
        return ((widthDp - PANEL_HORIZONTAL_PADDING_DP) / MIN_EMOJI_CELL_WIDTH_DP).coerceIn(
            MIN_EMOJI_COLUMN_COUNT,
            MAX_EMOJI_COLUMN_COUNT,
        )
    }

    /** dp寸法を端末密度に合わせたpxへ変換する。 */
    private fun dp(value: Int): Int = (value * density).toInt()

    /** キーとカテゴリの角丸背景を現在のライト・ダーク配色で作る。 */
    private fun roundedBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(BUTTON_CORNER_DP).toFloat()
    }

    companion object {
        /** 「最近」タブのID。 */
        private const val RECENT_ID = "recent"

        /** 空の最近タブで案内する短い文言。 */
        private const val EMPTY_RECENTS_LABEL = "まだありません"

        /** 画面上部へ最初に置く最近カテゴリ。 */
        private val RECENT_CATEGORY = EmojiPickerCategory(RECENT_ID, "最近", "最近使った絵文字と顔文字")

        /** 絵文字格子の表示領域を設定したキー2行分に保つ。 */
        private const val GRID_VISIBLE_ROWS = 2

        /** 顔文字の格子は長い文字列を見せるため2列にする。 */
        private const val KAOMOJI_COLUMN_COUNT = 2

        /** 一つの絵文字セルに必要な最小幅(dp)。 */
        private const val MIN_EMOJI_CELL_WIDTH_DP = 52

        /** 画面幅が狭い場合に使う絵文字の列数。 */
        private const val MIN_EMOJI_COLUMN_COUNT = 4

        /** 画面幅が広い場合の絵文字列数の上限。 */
        private const val MAX_EMOJI_COLUMN_COUNT = 10

        /** 横画面を含めた列数計算で除くパネル左右の合計余白(dp)。 */
        private const val PANEL_HORIZONTAL_PADDING_DP = 8

        /** 横スクロールするカテゴリタブの最小幅(dp)。 */
        private const val MIN_TAB_WIDTH_DP = 54

        /** カテゴリタブ左右の文字余白(dp)。 */
        private const val TAB_HORIZONTAL_PADDING_DP = 8

        /** カテゴリ名の文字サイズ(sp)。 */
        private const val TAB_TEXT_SIZE_SP = 13f

        /** 絵文字セルの文字サイズ(sp)。 */
        private const val EMOJI_TEXT_SIZE_SP = 25f

        /** 顔文字が2列幅へ収まる文字サイズ(sp)。 */
        private const val KAOMOJI_TEXT_SIZE_SP = 14f

        /** 空の最近タブの案内文字サイズ(sp)。 */
        private const val EMPTY_TEXT_SIZE_SP = 14f

        /** タイル左右の内側余白(dp)。 */
        private const val ITEM_HORIZONTAL_PADDING_DP = 3

        /** タイル上下の内側余白(dp)。 */
        private const val ITEM_VERTICAL_PADDING_DP = 4

        /** タイル間の隙間(dp)。 */
        private const val ITEM_MARGIN_DP = 2

        /** ボタン背景の角丸(dp)。 */
        private const val BUTTON_CORNER_DP = 7
    }
}
