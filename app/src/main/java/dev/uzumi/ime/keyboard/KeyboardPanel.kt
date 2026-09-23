package dev.uzumi.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * 日本語12キーフリック・英語QWERTY・数字・記号面のキーボードを提供するメインUIパネル。
 *
 * @param context コンテキスト
 * @param onAction キーボード操作イベントを通知するコールバック
 * @param lineDeleteLength 削除キーの左ドラッグで消える、カーソルから行頭までの文字数を返す。分からなければnull
 */
// 必須コールバックを伴うプログラム生成専用Viewであり、XMLからは生成しない。
@SuppressLint("ViewConstructor")
class KeyboardPanel(
    context: Context,
    private val onAction: (KeyboardAction) -> Unit,
    private val lineDeleteLength: () -> Int? = { null },
) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private var currentMode = KeyboardMode.KANA
    private var lastNormalMode = KeyboardMode.KANA

    private var isShifted = false
    private var isCapsLock = false
    private var lastShiftPressTime = 0L

    private var actionLabel = "確定"
    private var isNumericField = false
    private var isPasswordField = false

    private val allKeyViews = mutableListOf<KeyView>()
    private val enterKeyViews = mutableListOf<KeyView>()
    private val qwertyKeyViews = mutableListOf<KeyView>()
    private val shiftKeyViews = mutableListOf<KeyView>()

    private var numericSwitchKey: KeyView? = null

    // 入力中かどうかで役割が変わる12キーのキー。「123」と「カナ」、「空白」と「変換」を切り替える。
    private var kanaNumberKeyView: KeyView? = null
    private var kanaSpaceKeyView: KeyView? = null
    private var isComposing = false

    // かな配列へ戻るキー。password欄ではかな入力を使わないため非表示にする。
    private val kanaSwitchKeyViews = mutableListOf<KeyView>()

    // 記号面で表示中のページ番号。KeyboardLayoutData.SYMBOL_PAGESの添字。
    private var symbolPageIndex = 0
    private val symbolPageContainers = mutableListOf<LinearLayout>()

    // 押したキーの拡大表示。password欄では入力した文字を画面に大きく出さないため使わない。
    private val keyPreview = KeyPreviewPopup(this, KeyboardColors.from(context))

    // 削除キーの左ドラッグ中に、離すと消える範囲を示す案内。
    private val deleteDragHint = DeleteDragHint(this, KeyboardColors.from(context))

    private lateinit var kanaContainer: LinearLayout
    private lateinit var qwertyContainer: LinearLayout
    private lateinit var numericContainer: LinearLayout
    private lateinit var symbolContainer: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(KeyboardColors.from(context).keyboardBackground)
        val pad = (KeyboardDimens.PANEL_PADDING_DP * density).toInt()
        setPadding(pad, pad, pad, pad)

        initLayouts()
        updateSwitchKeys()
        updateModeVisibility()
    }

    /**
     * エディタの入力種別に応じてキーボードの初期表示モードを設定する。
     *
     * @param numeric 数値入力欄（暗証番号、電話番号、金額など）かどうか
     * @param password パスワード入力欄（予測変換不要・直接英語入力）かどうか
     */
    fun setEditorMode(numeric: Boolean, password: Boolean) {
        this.isNumericField = numeric
        this.isPasswordField = password

        updateSwitchKeys()

        if (numeric) {
            switchMode(KeyboardMode.NUMERIC)
        } else if (password) {
            // パスワード入力欄では予測不要の英語表示（QWERTY）へ切り替え
            switchMode(KeyboardMode.QWERTY)
        } else {
            switchMode(lastNormalMode)
        }
    }

    /**
     * Enter/確定キーのアクションラベルを設定する（例: "検索", "改行", "確定", "次へ" など）。
     *
     * @param label 設定する文言
     */
    fun setActionLabel(label: String) {
        this.actionLabel = label.ifEmpty { "確定" }
        enterKeyViews.forEach { it.updateActionLabel(this.actionLabel) }
    }

    /**
     * 入力中（未確定の読みやライブ変換の表示がある）かどうかを受け取り、12キーの「123」「空白」を
     * 入力中だけ「カナ」「変換」に切り替える。IMEは表示を更新するたびに呼ぶ。
     */
    fun setComposing(composing: Boolean) {
        if (composing == isComposing) return
        isComposing = composing
        kanaNumberKeyView?.replaceSpec(KeyboardLayoutData.kanaNumberKey(composing))
        kanaSpaceKeyView?.replaceSpec(KeyboardLayoutData.kanaSpaceKey(composing))
    }

    /**
     * 保留中のタッチ入力、フリック判定、リピート処理等を直ちに中断する。
     */
    fun cancelPendingInput() {
        allKeyViews.forEach { it.cancelPendingInput() }
        keyPreview.hide()
        deleteDragHint.hide()
    }

    override fun onDetachedFromWindow() {
        cancelPendingInput()
        super.onDetachedFromWindow()
    }

    /**
     * 各入力モード用のViewコンテナを初期化する。
     */
    private fun initLayouts() {
        kanaContainer = buildKanaLayout()
        qwertyContainer = buildQwertyLayout()
        numericContainer = buildNumericLayout()
        symbolContainer = buildSymbolLayout()

        // 表示が切り替わったときにTalkBackが面の名前を読み上げるよう、各面をpaneとして名前を付ける
        kanaContainer.accessibilityPaneTitle = KeySpeech.modeName(KeyboardMode.KANA)
        qwertyContainer.accessibilityPaneTitle = KeySpeech.modeName(KeyboardMode.QWERTY)
        numericContainer.accessibilityPaneTitle = KeySpeech.modeName(KeyboardMode.NUMERIC)
        symbolContainer.accessibilityPaneTitle = KeySpeech.modeName(KeyboardMode.SYMBOL)

        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(kanaContainer, lp)
        addView(qwertyContainer, lp)
        addView(numericContainer, lp)
        addView(symbolContainer, lp)
    }

    /**
     * 入力モードを切り替える。モード変更時に全キーの押下状態・リピートを確実に破棄する。
     */
    private fun switchMode(mode: KeyboardMode) {
        // モード変更で押下状態・リピートを完全に破棄
        cancelPendingInput()

        // パスワード入力時は常に英語または数字のみ
        val resolvedMode = if (isPasswordField && mode == KeyboardMode.KANA) {
            KeyboardMode.QWERTY
        } else {
            mode
        }

        currentMode = resolvedMode
        if (!isNumericField && !isPasswordField && (resolvedMode == KeyboardMode.KANA || resolvedMode == KeyboardMode.QWERTY)) {
            lastNormalMode = resolvedMode
        }
        updateModeVisibility()
    }

    /**
     * 現在のパスワード状態等に応じて切替キーの表示ラベルと遷移先を動的に更新する。
     */
    private fun updateSwitchKeys() {
        if (isPasswordField) {
            numericSwitchKey?.updateModeSwitch("ABC", KeyboardMode.QWERTY)
        } else {
            numericSwitchKey?.updateModeSwitch("あ/A", KeyboardMode.KANA)
        }
        val kanaKeyVisibility = if (isPasswordField) View.GONE else View.VISIBLE
        kanaSwitchKeyViews.forEach { it.visibility = kanaKeyVisibility }
    }

    /**
     * 現在のモードに応じてコンテナの表示状態を更新する。
     */
    private fun updateModeVisibility() {
        kanaContainer.visibility = if (currentMode == KeyboardMode.KANA) View.VISIBLE else View.GONE
        qwertyContainer.visibility = if (currentMode == KeyboardMode.QWERTY) View.VISIBLE else View.GONE
        numericContainer.visibility = if (currentMode == KeyboardMode.NUMERIC) View.VISIBLE else View.GONE
        symbolContainer.visibility = if (currentMode == KeyboardMode.SYMBOL) View.VISIBLE else View.GONE
    }

    /**
     * 記号面の次のページを表示する。最後のページの次は最初のページへ戻る。
     */
    private fun showNextSymbolPage() {
        cancelPendingInput()
        symbolPageIndex = (symbolPageIndex + 1) % symbolPageContainers.size
        updateSymbolPageVisibility()
    }

    /**
     * 記号面の各ページのうち、選択中のページだけを表示する。
     */
    private fun updateSymbolPageVisibility() {
        symbolPageContainers.forEachIndexed { index, page ->
            page.visibility = if (index == symbolPageIndex) View.VISIBLE else View.GONE
        }
    }

    /**
     * アクション委譲と自動Shift解除を行う共通コールバック。
     */
    private fun dispatchAction(action: KeyboardAction) {
        onAction(action)
        if (action is KeyboardAction.Text && isShifted && !isCapsLock) {
            isShifted = false
            updateQwertyShiftViews()
        }
    }

    /**
     * Shiftキーの押下（単押し: Shiftトグル、素早いダブルタップ: Caps Lockトグル）。
     */
    private fun toggleShift() {
        val now = System.currentTimeMillis()
        if (isCapsLock) {
            isCapsLock = false
            isShifted = false
        } else if (isShifted) {
            if (now - lastShiftPressTime < DOUBLE_TAP_TIMEOUT_MS) {
                isCapsLock = true
                isShifted = false
            } else {
                isShifted = false
            }
        } else {
            isShifted = true
        }
        lastShiftPressTime = now
        updateQwertyShiftViews()
    }

    /**
     * QWERTY配列の各文字キーおよびShiftキーへ最新のShift/Caps Lock状態を反映する。
     * Shift切替や大文字入力後の自動小文字復帰時に、キートップ表示と文字入力を一括で同期させるために必要であり、
     * パネル全体における英語入力状態の視覚的一貫性を保つ役割を担う。
     */
    private fun updateQwertyShiftViews() {
        qwertyKeyViews.forEach { it.updateShiftState(isShifted, isCapsLock) }
        shiftKeyViews.forEach { it.updateShiftState(isShifted, isCapsLock) }
    }

    /**
     * 12キーかなレイアウトを構築する（5列×4行、5列とも同じ幅）。キーの並びはSimejiに合わせる。
     */
    private fun buildKanaLayout(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val rowHeight = rowHeightPx(KeyboardDimens.ROW_HEIGHT_DP)

        // Row 0: [記号] [あ] [か] [さ] [削除]
        val row0 = createRow(rowHeight).apply {
            addView(createKey(KeySpec.ModeSwitch("記号", KeyboardMode.SYMBOL), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.A), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.KA), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.SA), 1f))
            addView(createKey(KeySpec.Action(KeyboardAction.Delete, "⌫"), 1f))
        }

        // Row 1: [←] [た] [な] [は] [→]
        val row1 = createRow(rowHeight).apply {
            addView(createKey(KeySpec.Action(KeyboardAction.MoveCursor(-1), "←"), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.TA), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.NA), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.HA), 1f))
            addView(createKey(KeySpec.Action(KeyboardAction.MoveCursor(1), "→"), 1f))
        }

        // Row 2: [123 / 入力中はカナ] [ま] [や] [ら] [空白 / 入力中は変換]
        val row2 = createRow(rowHeight).apply {
            val numberKey = createKey(KeyboardLayoutData.kanaNumberKey(isComposing), 1f)
            kanaNumberKeyView = numberKey
            addView(numberKey)
            addView(createKey(KeySpec.Kana(KanaKeyType.MA), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.YA), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.RA), 1f))
            val spaceKey = createKey(KeyboardLayoutData.kanaSpaceKey(isComposing), 1f)
            kanaSpaceKeyView = spaceKey
            addView(spaceKey)
        }

        // Row 3: [あA] [小゛゜] [わ] [、。] [Enter]
        val row3 = createRow(rowHeight).apply {
            addView(createKey(KeySpec.ModeSwitch("あA", KeyboardMode.QWERTY), 1f))
            addView(createKey(KeySpec.Action(KeyboardAction.TransformKana, "小゛゜"), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.WA), 1f))
            addView(createKey(KeySpec.Kana(KanaKeyType.PUNCT), 1f))
            val enterKey = createKey(KeySpec.Action(KeyboardAction.Enter, actionLabel, isAccent = true), 1f)
            enterKeyViews.add(enterKey)
            addView(enterKey)
        }

        container.addView(row0)
        container.addView(row1)
        container.addView(row2)
        container.addView(row3)
        return container
    }

    /**
     * 英語QWERTYレイアウトを構築する（数字行を含む5行）。行の並びは[KeyboardLayoutData.QWERTY_ROWS]に従う。
     */
    private fun buildQwertyLayout(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val rowHeight = rowHeightPx(KeyboardDimens.QWERTY_ROW_HEIGHT_DP)
        val (digits, upper, middle, lower) = KeyboardLayoutData.QWERTY_ROWS

        // Row 0〜2: 数字、q〜p、a〜l と '（各10キー）
        listOf(digits, upper, middle).forEach { chars ->
            val row = createRow(rowHeight)
            chars.forEach { char -> row.addView(createQwertyKey(char)) }
            container.addView(row)
        }

        // Row 3: [123（長押しで記号面）] z x c v b n m [削除]
        val row3 = createRow(rowHeight)
        row3.addView(
            createKey(
                KeySpec.ModeSwitch(
                    label = "123",
                    targetMode = KeyboardMode.NUMERIC,
                    longPressTarget = KeyboardMode.SYMBOL,
                    longPressLabel = "記号",
                ),
                1.5f,
            ),
        )
        lower.forEach { char -> row3.addView(createQwertyKey(char)) }
        row3.addView(createKey(KeySpec.Action(KeyboardAction.Delete, "⌫"), 1.5f))
        container.addView(row3)

        // Row 4: [あ] [Shift] [,] [Space] [.] [←] [→] [Enter]
        val row4 = createRow(rowHeight)
        val kanaKey = createKey(KeySpec.ModeSwitch("あ", KeyboardMode.KANA), 1.5f)
        kanaSwitchKeyViews.add(kanaKey)
        row4.addView(kanaKey)
        val shiftKey = createKey(KeySpec.Shift("⇧"), 1f)
        shiftKeyViews.add(shiftKey)
        row4.addView(shiftKey)
        row4.addView(createKey(KeySpec.SimpleText(","), 1f))
        row4.addView(createKey(KeySpec.Action(KeyboardAction.Space, "space"), 2f))
        row4.addView(createKey(KeySpec.SimpleText("."), 1f))
        row4.addView(createKey(KeySpec.Action(KeyboardAction.MoveCursor(-1), "←"), 1f))
        row4.addView(createKey(KeySpec.Action(KeyboardAction.MoveCursor(1), "→"), 1f))
        val enterKey = createKey(KeySpec.Action(KeyboardAction.Enter, actionLabel, isAccent = true), 1.5f)
        enterKeyViews.add(enterKey)
        row4.addView(enterKey)
        container.addView(row4)
        return container
    }

    /** QWERTYの文字キーを一つ作り、Shift表示の同期対象へ登録する。 */
    private fun createQwertyKey(char: Char): KeyView {
        val key = createKey(qwertyLetterSpec(char.toString()), 1f)
        qwertyKeyViews.add(key)
        return key
    }

    /**
     * 数字・記号テンキーレイアウトを構築する（4行×5列）。
     */
    private fun buildNumericLayout(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val rowHeight = rowHeightPx(KeyboardDimens.ROW_HEIGHT_DP)

        // Row 0: [1] [2] [3] [/] [削除]
        val row0 = createRow(rowHeight).apply {
            addView(createKey(KeySpec.SimpleText("1"), 1.0f))
            addView(createKey(KeySpec.SimpleText("2"), 1.0f))
            addView(createKey(KeySpec.SimpleText("3"), 1.0f))
            addView(createKey(KeySpec.SimpleText("/"), 1.0f))
            addView(createKey(KeySpec.Action(KeyboardAction.Delete, "⌫"), 1.0f))
        }

        // Row 1: [4] [5] [6] [*] [-]
        val row1 = createRow(rowHeight).apply {
            addView(createKey(KeySpec.SimpleText("4"), 1.0f))
            addView(createKey(KeySpec.SimpleText("5"), 1.0f))
            addView(createKey(KeySpec.SimpleText("6"), 1.0f))
            addView(createKey(KeySpec.SimpleText("*"), 1.0f))
            addView(createKey(KeySpec.SimpleText("-"), 1.0f))
        }

        // Row 2: [7] [8] [9] [空白] [+]
        val row2 = createRow(rowHeight).apply {
            addView(createKey(KeySpec.SimpleText("7"), 1.0f))
            addView(createKey(KeySpec.SimpleText("8"), 1.0f))
            addView(createKey(KeySpec.SimpleText("9"), 1.0f))
            addView(createKey(KeySpec.Action(KeyboardAction.Space, "空白"), 1.0f))
            addView(createKey(KeySpec.SimpleText("+"), 1.0f))
        }

        // Row 3: [かな/英字切替] [記号] [0] [.（長押しで,）] [Enter]
        val row3 = createRow(rowHeight).apply {
            val returnKey = createKey(KeySpec.ModeSwitch("あ/A", KeyboardMode.KANA), 1.0f)
            numericSwitchKey = returnKey
            addView(returnKey)

            addView(createKey(KeySpec.ModeSwitch("記号", KeyboardMode.SYMBOL), 1.0f))
            addView(createKey(KeySpec.SimpleText("0"), 1.0f))
            addView(createKey(KeySpec.SimpleText(".", longPressText = ","), 1.0f))
            val enterKey = createKey(KeySpec.Action(KeyboardAction.Enter, actionLabel, isAccent = true), 1.0f)
            enterKeyViews.add(enterKey)
            addView(enterKey)
        }

        container.addView(row0)
        container.addView(row1)
        container.addView(row2)
        container.addView(row3)
        return container
    }

    /**
     * 記号面を構築する。各ページの3行と、全ページ共通の切替・空白・Enterの行からなる（4行構成）。
     * QWERTYや数字からかな配列を経由せずに句読点・括弧・記号を入力するために使う。
     */
    private fun buildSymbolLayout(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val rowHeight = rowHeightPx(KeyboardDimens.ROW_HEIGHT_DP)
        val pages = KeyboardLayoutData.SYMBOL_PAGES

        pages.forEachIndexed { index, page ->
            val pageContainer = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            // 切替キーには次のページ名を表示し、押すと何が出るかを示す
            val nextPage = pages[(index + 1) % pages.size]
            page.rows.forEachIndexed { rowIndex, symbols ->
                val row = createRow(rowHeight)
                val isLastRow = rowIndex == page.rows.lastIndex
                if (isLastRow) {
                    row.addView(
                        createKey(
                            KeySpec.PageSwitch(
                                label = nextPage.label,
                                description = "${nextPage.spokenName}のページへ切り替え、現在は${page.spokenName}",
                            ),
                            1.0f,
                        ),
                    )
                }
                symbols.forEach { symbol -> row.addView(createKey(KeySpec.SimpleText(symbol), 1.0f)) }
                if (isLastRow) {
                    row.addView(createKey(KeySpec.Action(KeyboardAction.Delete, "⌫"), 1.0f))
                }
                pageContainer.addView(row)
            }
            symbolPageContainers.add(pageContainer)
            container.addView(pageContainer)
        }
        updateSymbolPageVisibility()

        // 最下段: [かな] [英字] [数字] [空白] [Enter]
        val bottomRow = createRow(rowHeight)
        val kanaKey = createKey(KeySpec.ModeSwitch("あ", KeyboardMode.KANA), 1.2f)
        kanaSwitchKeyViews.add(kanaKey)
        bottomRow.addView(kanaKey)
        bottomRow.addView(createKey(KeySpec.ModeSwitch("ABC", KeyboardMode.QWERTY), 1.2f))
        bottomRow.addView(createKey(KeySpec.ModeSwitch("123", KeyboardMode.NUMERIC), 1.2f))
        bottomRow.addView(createKey(KeySpec.Action(KeyboardAction.Space, "空白"), 3.9f))
        val enterKey = createKey(KeySpec.Action(KeyboardAction.Enter, actionLabel, isAccent = true), 2.5f)
        enterKeyViews.add(enterKey)
        bottomRow.addView(enterKey)
        container.addView(bottomRow)
        return container
    }

    /**
     * QWERTYの英字キーの定義を作る。長押しで数字・記号を入力できるよう長押し文字を付ける。
     */
    private fun qwertyLetterSpec(char: String): KeySpec.SimpleText {
        return KeySpec.SimpleText(char, longPressText = KeyboardLayoutData.getQwertyLongPress(char[0]))
    }

    /**
     * 指定した高さと上下マージンを持つ水平方向のLinearLayout（行コンテナ）を生成する。
     * 複数キーを均等ウェイトで横一列に配置し、行間の余白を統一的に確保するために必要であり、
     * キーボード各レイアウトの段組みを組み立てる共通の行基盤としての役割を担う。
     */
    private fun createRow(heightPx: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        }
    }

    /** dpの行の高さをpxへ直す。タップ領域の下限48dpを下回らないようにする。 */
    private fun rowHeightPx(heightDp: Float): Int = (maxOf(heightDp, MIN_TOUCH_DP) * density).toInt()

    /**
     * キー定義（KeySpec）とウェイトからKeyViewを生成し、共通設定とイベントコールバックを結線する。
     * タップ領域（最小高48dp）の確保、アクション中継、パスワード欄での安全なモード切替を集約し、
     * 全キーリスト（allKeyViews）へ登録して一括状態管理を可能にする役割を担う。
     */
    private fun createKey(spec: KeySpec, weight: Float): KeyView {
        val keyView = KeyView(context).apply {
            // 余白を付けず、見た目の隙間はKeyViewが内側へ縮めて描く。隙間を押しても隣のキーが反応する。
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
            minimumHeight = (MIN_TOUCH_DP * density).toInt()
            setKeySpec(
                spec = spec,
                onAction = { dispatchAction(it) },
                onModeSwitch = { targetMode ->
                    // 実行時のpassword状態に応じて安全に切り替え
                    val actualTarget = if (isPasswordField) {
                        if (targetMode == KeyboardMode.KANA) KeyboardMode.QWERTY else targetMode
                    } else {
                        targetMode
                    }
                    switchMode(actualTarget)
                },
                onShiftToggle = { toggleShift() },
                onPageSwitch = { showNextSymbolPage() },
                onPreview = { key, text ->
                    if (text == null || isPasswordField) keyPreview.hide() else keyPreview.show(key, text)
                },
                onDeleteDrag = { key, state ->
                    // 文字数は閾値を超えたときだけ問い合わせ、ドラッグ中の毎回の問い合わせを避ける
                    val length = if (state == DeleteDragState.ARMED) lineDeleteLength() else null
                    deleteDragHint.show(key, state, length)
                },
            )
        }
        allKeyViews.add(keyView)
        return keyView
    }

    companion object {
        private const val DOUBLE_TAP_TIMEOUT_MS = 350L

        /** タップ領域の高さの下限（dp）。 */
        private const val MIN_TOUCH_DP = 48f
    }
}
