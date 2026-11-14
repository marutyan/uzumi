package dev.uzumi.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.uzumi.ime.keyboard.CandidateBarViews
import dev.uzumi.ime.keyboard.KeyboardAppearance
import dev.uzumi.ime.keyboard.KeyboardBackgroundDrawable
import dev.uzumi.ime.keyboard.KeyboardBackgroundImage
import dev.uzumi.ime.keyboard.KeyboardColors
import dev.uzumi.ime.keyboard.KeyboardPanel
import dev.uzumi.ime.keyboard.KeyboardPreferences
import dev.uzumi.ime.keyboard.KeyboardRootLayout
import dev.uzumi.ime.keyboard.Keymap
import dev.uzumi.ime.keyboard.KeymapRules
import dev.uzumi.ime.keyboard.ThemeColorMath
import dev.uzumi.ime.keyboard.ThemePalette
import dev.uzumi.ime.keyboard.ThemePreset
import dev.uzumi.ime.keyboard.ToolbarAction
import dev.uzumi.ime.keyboard.ToolbarBarViews
import dev.uzumi.ime.keyboard.ToolbarEditController
import dev.uzumi.ime.keyboard.ToolbarItem
import dev.uzumi.ime.keyboard.ToolbarLayout
import dev.uzumi.ime.keyboard.ToolbarPreferences
import dev.uzumi.ime.keyboard.themePalette
import kotlin.math.roundToInt

/**
 * 設定の「キーボード」の画面。上に完成予想図（実際のキーボードの部品で組み立てた見本）を固定し、下の項目で変えるとその場で描き直す。
 * 下には、テーマ、背景画像、キーの高さと段数、機能バーの並び（見本のバーと「ほかの機能」の間で移す）、細かい調整（きせかえの画面）を並べる。
 * 値は今のUzumiSettingsの保存処理でそのまま保存し、キーボードには次に表示したときから反映される。
 */
class KeyboardSettingsActivity : Activity() {
    private lateinit var ui: SettingsUi
    private lateinit var root: KeyboardRootLayout
    private lateinit var previewHead: FrameLayout
    private lateinit var previewBox: ScaledPreviewBox
    private lateinit var scroll: ScrollView
    private var tray: SettingsToolbarTray? = null
    private var controller: ToolbarEditController? = null

    // 見本を「入力中」（候補を並べた状態）で見せているか。
    private var composing = false

    // 見本のキーの面と、作り直すときに出し直す配列。左下のキーで切り替えた配列を、設定を変えて作り直した後も保つ。
    private var previewPanel: KeyboardPanel? = null
    private var previewKeymap: Keymap? = null

    // スライダーを動かしている間に見本を作り直しすぎないよう、少し待ってからまとめて作り直す。
    private val handler = Handler(Looper.getMainLooper())
    private val previewRefresh = Runnable { buildPreview() }

    /** 見出しの帯、固定する見本の欄、スクロールする項目の入れ物を作る。中身は[onResume]で今の設定から作る。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SettingsUi(this)
        title = getString(R.string.settings_keyboard)
        previewHead = FrameLayout(this)
        previewBox = ScaledPreviewBox(this).apply {
            contentDescription = getString(R.string.kisekae_preview_description)
            // 角を丸めて切り抜き、細い枠線は見本の上に重ねて描く。
            background = GradientDrawable().apply { cornerRadius = ui.px(PREVIEW_CORNER_DP).toFloat() }
            foreground = GradientDrawable().apply {
                cornerRadius = ui.px(PREVIEW_CORNER_DP).toFloat()
                setStroke(ui.px(1).coerceAtLeast(1), ui.divider)
            }
            clipToOutline = true
            // 見本が画面の高さの半分を超えないようにする（試作と同じ上限）。
            maxHeightPx = (resources.displayMetrics.heightPixels * PREVIEW_MAX_SCREEN_FRACTION).toInt() - ui.px(PREVIEW_HEAD_DP)
        }
        val previewPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
            setPadding(ui.px(16), ui.px(8), ui.px(16), ui.px(12))
            addView(previewHead, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.px(6)
            })
            addView(previewBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        scroll = ScrollView(this).apply {
            // 下の項目をスクロールしたら、見本の欄の下に影を付けて、上に固定していることを見せる。
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                previewPane.elevation = if (scrollY > 0) ui.px(PREVIEW_ELEVATION_DP).toFloat() else 0f
            }
        }
        // 機能バーの並べ替えのドラッグを受け取り、持ち上げたタイルを全体の上に描くため、根はキーボードと同じ入れ物にする。
        root = KeyboardRootLayout(this).apply {
            addView(LinearLayout(this@KeyboardSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.appBar(getString(R.string.settings_keyboard)) { finish() })
                addView(previewPane)
                addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        applySystemInsets(root)
        setContentView(root)
    }

    /** きせかえの画面などで変えた値を反映するため、戻るたびに見本と項目を作り直す。 */
    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun onDestroy() {
        handler.removeCallbacks(previewRefresh)
        controller?.stop()
        super.onDestroy()
    }

    /** 写真の選択から戻ったら、切り抜いて保存し、終わったら見本とカードを作り直す。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BACKGROUND_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        KeyboardBackgroundPicker.save(this, uri, ::rebuild)
    }

    /** 項目と見本を今の設定から作り直す。スクロール位置は保つ。 */
    private fun rebuild() {
        val scrollY = scroll.scrollY
        scroll.removeAllViews()
        scroll.addView(buildSettings())
        scroll.post { scroll.scrollTo(0, scrollY) }
        buildPreview()
    }

    /** 少し待ってから見本だけを作り直す。 */
    private fun schedulePreview() {
        handler.removeCallbacks(previewRefresh)
        handler.postDelayed(previewRefresh, PREVIEW_DELAY_MS)
    }

    // ---------- 完成予想図 ----------

    /**
     * 見本を作り直す。右上に「入力前／入力中」の切り替え、その下に、キーボードと同じ部品で組み立てたバーの領域とキーの面を置く。
     * 入力前は機能バーをそのまま並べ替えの場にし、「ほかの機能」のタイルとの間で機能を移せるようにする。
     */
    private fun buildPreview() {
        handler.removeCallbacks(previewRefresh)
        controller?.stop()
        controller = null
        previewHead.removeAllViews()
        previewHead.addView(ui.segmented(
            listOf(getString(R.string.keyboard_preview_idle), getString(R.string.keyboard_preview_composing)),
            selected = if (composing) 1 else 0,
            compact = true,
        ) { index -> setComposing(index == 1) }.apply {
            contentDescription = getString(R.string.keyboard_preview_state)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END))

        val preferences = UzumiSettings.keyboardPreferences(this)
        val toolbar = UzumiSettings.toolbarPreferences(this)
        val appearance = preferences.appearance
        val barViews = CandidateBarViews(this)
        val image = KeyboardBackgroundImage.load(this, appearance.backgroundStamp, appearance.backgroundBlur)
        val sample = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (image != null) {
                KeyboardBackgroundDrawable(image, appearance.backgroundBrightness, barViews.colors.keyboardBackground)
            } else {
                GradientDrawable().apply { setColor(barViews.colors.keyboardBackground) }
            }
        }
        // 候補バーと機能バーは同じ領域に出し、高さは段数の大きい方で決める（キーボードと同じ）。
        val areaRows = maxOf(toolbar.toolbarRows, toolbar.candidateRows)
        val barAlpha = if (image != null) maxOf(MIN_BAR_ALPHA_WITH_IMAGE, appearance.keyOpacity / 100f) else 1f
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeColorMath.withAlpha(barViews.colors.bar, barAlpha))
        }
        if (composing) {
            addCandidateRows(area, barViews, toolbar, areaRows)
        } else {
            addToolbarRows(area, barViews, toolbar, areaRows)
        }
        sample.addView(area, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barRowPx() * areaRows))
        sample.addView(TouchBlockingFrame(this).apply {
            val panel = keyboardSample(preferences)
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            passThrough = { panel.keymapSwitchKey }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        previewBox.removeAllViews()
        previewBox.addView(sample, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val currentTray = tray ?: return
        if (composing) {
            // 入力中の見本には機能バーが無いため、置き場は並べるだけにする（押すと入力前へ戻して移す）。
            currentTray.render(toolbar.trayActions.map(::toolbarItem))
        } else {
            controller = ToolbarEditController(
                context = this,
                views = ToolbarBarViews(this),
                rootLayout = root,
                barHost = requireNotNull(toolbarHost),
                panel = currentTray,
                itemFor = ::toolbarItem,
                onDone = null,
                onCommit = ::saveToolbarLayout,
                barRows = toolbar.toolbarRows,
                movesOnTap = true,
                touchesVisibleOnly = true,
            ).also { it.start(toolbar.layout) }
        }
    }

    /**
     * 見本のキーの面。選んだキーマップを渡し、通常の入力欄と同じ最初の配列を出す。前の見本で左下のキーを使って切り替えていれば、
     * その配列がまだ選ばれている限り同じ配列を出す。左下のキーで選んだ順に切り替わるのはキーボードと同じ処理による。
     */
    private fun keyboardSample(preferences: KeyboardPreferences): KeyboardPanel {
        previewPanel?.let { previewKeymap = it.shownKeymap }
        val order = UzumiSettings.keymaps(this)
        return KeyboardPanel(this, { }, preferences = preferences).apply {
            setKeymaps(order)
            setEditorMode(numeric = false, password = false, directCommit = false)
            previewKeymap?.takeIf { it in order }?.let(::selectKeymap)
            previewPanel = this
        }
    }

    // 入力前の見本で、機能バーを描く場所。並べ替えの処理がここへ描く。
    private var toolbarHost: FrameLayout? = null

    /**
     * 入力前のバーの領域。機能バーの段（並べ替えの場）と、候補の段数が機能バーより多いときに空く段（最近コピーした文字）を、
     * 機能バーの位置の設定に従って上下に並べる。
     */
    private fun addToolbarRows(area: LinearLayout, barViews: CandidateBarViews, toolbar: ToolbarPreferences, areaRows: Int) {
        val host = FrameLayout(this)
        toolbarHost = host
        val clipRows = (areaRows - toolbar.toolbarRows).coerceAtLeast(0)
        val toolbarParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barRowPx() * toolbar.toolbarRows)
        if (clipRows > 0 && !toolbar.toolbarOnTop) addClipboardRows(area, barViews, clipRows)
        area.addView(host, toolbarParams)
        if (clipRows > 0 && toolbar.toolbarOnTop) addClipboardRows(area, barViews, clipRows)
    }

    /**
     * 最近コピーした文字の段の見本。キーボードと同じく、先頭の段の左に履歴のボタンを置き、見本の文字を札で並べる。
     * 履歴がオフなら、キーボードと同じ案内を出す。
     */
    private fun addClipboardRows(area: LinearLayout, barViews: CandidateBarViews, rows: Int) {
        val historyOn = UzumiSettings.isClipboardHistoryEnabled(this)
        val chips = if (historyOn) {
            SAMPLE_CLIPBOARD.map { text ->
                barViews.chip(text, null, emphasized = false, onClick = null).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = resources.displayMetrics.widthPixels / 2
                }
            }
        } else {
            emptyList()
        }
        val lines = ToolbarPreferences.splitAcrossRows(chips, rows)
        repeat(rows) { rowIndex ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (rowIndex == 0) {
                line.addView(barViews.iconButton(R.drawable.ic_content_paste, getString(R.string.toolbar_clipboard_history)) {}.apply {
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = ui.px(CLIPBOARD_BUTTON_MARGIN_DP)
                    }
                    if (!historyOn) visibility = View.INVISIBLE
                })
                if (!historyOn) line.addView(barViews.hint(getString(R.string.clipboard_row_history_off)))
            }
            lines.getOrNull(rowIndex).orEmpty().forEach(line::addView)
            area.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(line, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barRowPx()))
        }
    }

    /**
     * 入力中のバーの領域。見本の候補を候補の段数に分けて並べ、先頭の段の左に読み、右に候補一覧の∨を置く。
     * 候補の区切り線の設定は、キーボードと同じく選択中の候補の両脇には引かない。
     */
    private fun addCandidateRows(area: LinearLayout, barViews: CandidateBarViews, toolbar: ToolbarPreferences, areaRows: Int) {
        toolbarHost = null
        val lines = ToolbarPreferences.splitAcrossRows(SAMPLE_CANDIDATES, toolbar.candidateRows)
        lines.forEachIndexed { rowIndex, words ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (rowIndex == 0) line.addView(barViews.chip(SAMPLE_READING, null, emphasized = false, onClick = null))
            words.forEachIndexed { index, word ->
                val selected = rowIndex == 0 && index == 0
                val besideSelected = rowIndex == 0 && index == 1
                line.addView(barViews.item(word, selected, dividerBefore = toolbar.candidateDividers && index > 0 && !besideSelected) {})
            }
            area.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(HorizontalScrollView(this@KeyboardSettingsActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(line, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                if (rowIndex == 0) {
                    addView(barViews.iconButton(R.drawable.ic_keyboard_arrow_down, getString(R.string.candidate_list_toggle)) {}.apply {
                        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_VERTICAL }
                    })
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barRowPx()))
        }
        // 候補より機能バーの段数が多いときも、バーの領域の高さはキーボードと同じに保つ。
        repeat(areaRows - lines.size) {
            area.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barRowPx()))
        }
    }

    /** 見本を「入力前」と「入力中」で切り替える。 */
    private fun setComposing(value: Boolean) {
        if (composing == value) return
        composing = value
        buildPreview()
    }

    /**
     * 「ほかの機能」のタイルを押したとき。入力中の見本なら入力前へ戻してから、押したときと同じく機能バーへ移す。
     * 入力前の指の操作は並べ替えの処理が先に受け取るため、ここへはTalkBackでの選択だけが届く。
     */
    private fun onTrayTileClick(action: ToolbarAction) {
        setComposing(false)
        controller?.tap(action)
    }

    /** 並べ替えで確定した並びを保存し、保存後の（整えた）並びを返す。 */
    private fun saveToolbarLayout(layout: ToolbarLayout): ToolbarLayout {
        UzumiSettings.setToolbarLayout(this, layout)
        return UzumiSettings.toolbarPreferences(this).layout
    }

    /** 機能の表示の名前。設定画面では今使えるかを問わないため、使えない理由は持たせない。 */
    private fun toolbarItem(action: ToolbarAction): ToolbarItem = ToolbarItem(action, toolbarActionLabel(action))

    /** 機能の名前を、キーボードの機能バーと同じ文言にする。 */
    private fun toolbarActionLabel(action: ToolbarAction): String = when (action) {
        ToolbarAction.PASTE -> getString(R.string.toolbar_paste)
        ToolbarAction.CLIPBOARD_HISTORY -> getString(R.string.toolbar_clipboard_history)
        ToolbarAction.EDIT -> getString(R.string.toolbar_edit)
        ToolbarAction.MICROPHONE -> getString(R.string.toolbar_microphone)
        ToolbarAction.UNDO -> getString(R.string.toolbar_undo)
        ToolbarAction.USER_DICTIONARY -> getString(R.string.open_user_dictionary)
        ToolbarAction.TEMPLATES -> getString(R.string.toolbar_templates)
        ToolbarAction.SETTINGS -> getString(R.string.open_settings)
        ToolbarAction.CLOSE_KEYBOARD -> getString(R.string.close_keyboard)
        ToolbarAction.EMOJI -> getString(R.string.toolbar_emoji)
        ToolbarAction.KISEKAE -> getString(R.string.toolbar_kisekae)
        ToolbarAction.HEIGHT -> getString(R.string.toolbar_height)
    }

    private fun barRowPx(): Int = ui.px(CandidateBarViews.BAR_HEIGHT_DP)

    // ---------- 下の項目 ----------

    /** 見本の下に並べる項目。 */
    private fun buildSettings(): LinearLayout {
        val page = ui.page().apply { clipToPadding = false }
        val preferences = UzumiSettings.keyboardPreferences(this)
        val toolbar = UzumiSettings.toolbarPreferences(this)
        ui.addBlock(page, stripSection(getString(R.string.settings_keymaps), keymapCards(), KEYMAP_CARD_WIDTH_DP))
        ui.addBlock(page, stripSection(getString(R.string.keyboard_section_theme), themeCards()))
        ui.addBlock(page, stripSection(getString(R.string.kisekae_section_background), backgroundCards()))
        ui.addBlock(page, ui.section(getString(R.string.keyboard_section_size), ui.group(listOf(
            heightRow(preferences),
            rowCountRow(R.string.settings_candidate_rows, toolbar.candidateRows, UzumiSettings::setCandidateRows),
            rowCountRow(R.string.settings_toolbar_rows, toolbar.toolbarRows, UzumiSettings::setToolbarRows),
            toolbarPositionRow(toolbar),
            ui.switchRow(getString(R.string.settings_candidate_dividers), null, toolbar.candidateDividers) {
                UzumiSettings.setCandidateDividers(this, it)
                buildPreview()
            }.first.view,
        ))))
        val newTray = SettingsToolbarTray(this, ToolbarBarViews(this), ::onTrayTileClick)
        tray = newTray
        ui.addBlock(page, ui.section(getString(R.string.settings_toolbar_tray), newTray.area))
        ui.addBlock(page, ui.group(listOf(
            ui.row(getString(R.string.settings_fine_tune), getString(R.string.settings_fine_tune_summary), R.drawable.ic_tune,
                trailing = ui.chevron()) { startActivity(Intent(this, KisekaeActivity::class.java)) }.view,
        )))
        return page
    }

    /** 横に流して選ぶカードの列を、見出しつきで置く。列は画面の端まで広げ、最初のカードを左端から24dpで始める。 */
    private fun stripSection(title: String, cards: List<View>, cardWidthDp: Int = CARD_WIDTH_DP): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.px(24), ui.px(2), ui.px(24), ui.px(6))
            cards.forEachIndexed { index, card ->
                addView(card, LinearLayout.LayoutParams(ui.px(cardWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = ui.px(8)
                })
            }
        }
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(ui.sectionTitle(title))
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(8)
                leftMargin = -ui.px(16)
                rightMargin = -ui.px(16)
            })
        }
    }

    /**
     * キーマップのカード。6つの配列の面の縮図を今のテーマの配色で描き、選んでいる配列には選んだ順の番号を付ける。
     * 押すと選択・解除し（最後の1つは外さない）、足した配列を見本に出す。保存はUzumiSettingsのキーマップの読み書きを使う。
     */
    private fun keymapCards(): List<View> {
        val appearance = UzumiSettings.keyboardAppearance(this)
        val palette = paletteFor(appearance, KeyboardColors.isDark(this, appearance))
        val order = UzumiSettings.keymaps(this)
        return Keymap.entries.map { keymap ->
            val position = order.indexOf(keymap)
            card(
                label = keymap.displayName,
                palette = palette,
                checked = position >= 0,
                role = CheckBox::class.java.name,
                number = (position + 1).takeIf { position >= 0 },
                keymapRows = KeymapThumbnails.rows(keymap),
                description = if (position >= 0) getString(R.string.settings_keymap_selected, position + 1, keymap.displayName) else null,
            ) { toggleKeymap(keymap) }
        }
    }

    /** キーマップの選択を切り替えて保存し、見本と項目を作り直す。足した配列は見本に出し、外した配列を見本に出していたら最初の配列へ戻す。 */
    private fun toggleKeymap(keymap: Keymap) {
        val order = UzumiSettings.keymaps(this)
        val changed = KeymapRules.toggled(order, keymap)
        if (changed == order) return
        UzumiSettings.setKeymaps(this, changed)
        previewKeymap = if (keymap in changed) keymap else previewPanel?.shownKeymap?.takeIf { it in changed }
        previewPanel = null
        rebuild()
    }

    /** テーマのカード。各テーマの今の明るさの配色で縮図を描き、カスタムには左上にスポイトの印を付ける。 */
    private fun themeCards(): List<View> {
        val appearance = UzumiSettings.keyboardAppearance(this)
        val dark = KeyboardColors.isDark(this, appearance)
        return ThemePreset.entries.map { preset ->
            card(
                label = getString(keyboardThemeName(preset)),
                palette = paletteFor(appearance.copy(preset = preset), dark),
                checked = appearance.preset == preset,
                role = RadioButton::class.java.name,
                badge = if (preset == ThemePreset.CUSTOM) R.drawable.ic_colorize else null,
            ) { selectTheme(preset) }
        }
    }

    /**
     * テーマを選んで保存する。カスタムは今の色を写してカスタムにし、色を選ぶためにきせかえの画面を開く。
     */
    private fun selectTheme(preset: ThemePreset) {
        val current = UzumiSettings.keyboardAppearance(this)
        if (preset == ThemePreset.CUSTOM) {
            UzumiSettings.setKeyboardAppearance(this, current.toCustom())
            startActivity(Intent(this, KisekaeActivity::class.java))
            return
        }
        if (current.preset == preset) return
        UzumiSettings.setKeyboardAppearance(this, current.copy(preset = preset))
        rebuild()
    }

    /**
     * 背景画像のカード。「写真を選ぶ」、保存した写真があれば「今の写真」、「なし」を並べ、使っている方に選択の印を付ける。
     * 「なし」を選ぶと、きせかえの画面の「画像を外す」と同じく保存した写真を消す。
     */
    private fun backgroundCards(): List<View> {
        val appearance = UzumiSettings.keyboardAppearance(this)
        val palette = paletteFor(appearance, KeyboardColors.isDark(this, appearance))
        val photo = KeyboardBackgroundImage.load(this, appearance.backgroundStamp, appearance.backgroundBlur)
        return buildList {
            add(card(getString(R.string.kisekae_background_pick), palette = null, checked = false, role = null) {
                KeyboardBackgroundPicker.open(this@KeyboardSettingsActivity, REQUEST_BACKGROUND_IMAGE)
            })
            if (photo != null) {
                add(card(getString(R.string.keyboard_background_current), palette, checked = true, role = RadioButton::class.java.name,
                    photo = photo) {})
            }
            add(card(getString(R.string.keyboard_background_none), palette, checked = photo == null, role = RadioButton::class.java.name) {
                if (appearance.hasBackgroundImage) {
                    KeyboardBackgroundImage.remove(this@KeyboardSettingsActivity)
                    UzumiSettings.setBackgroundStamp(this@KeyboardSettingsActivity, 0L)
                    rebuild()
                }
            })
        }
    }

    /** 選んだテーマと明るさの配色。標準は色resourceと同じ値を持つ定義から読む。 */
    private fun paletteFor(appearance: KeyboardAppearance, dark: Boolean): ThemePalette =
        themePalette(appearance, dark) ?: requireNotNull(ThemePreset.STANDARD.palette(dark))

    /**
     * カード一つ。縮図の下に名前を置き、押すと[onClick]を呼ぶ。[role]があればTalkBackにその部品（ラジオボタン・チェックボックス）として、
     * 選択中かどうかとともに読ませる。読み上げの名前は[description]、なければ[label]にする。
     */
    private fun card(
        label: String,
        palette: ThemePalette?,
        checked: Boolean,
        role: String?,
        badge: Int? = null,
        photo: Bitmap? = null,
        number: Int? = null,
        keymapRows: List<List<ThumbKey>>? = null,
        description: String? = null,
        onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        contentDescription = description ?: label
        setOnClickListener { onClick() }
        if (role != null) accessibilityDelegate = ui.checkedRole(role) { checked }
        addView(KeyboardThumbView(this@KeyboardSettingsActivity).apply {
            this.palette = palette
            this.photo = photo
            this.checked = checked
            this.number = number
            this.keymapRows = keymapRows
            this.badge = badge?.let { requireNotNull(getDrawable(it)).mutate() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@KeyboardSettingsActivity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ui.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.px(6)
        })
    }

    /**
     * キーの高さの行。題名・今の値・「標準に戻す」を1行に置き、その下にスライダーと「低い・高い」を置く。
     * 動かしている間も保存し、見本を少し待ってから描き直す。
     */
    private fun heightRow(preferences: KeyboardPreferences): View {
        val minimum = KeyboardPreferences.MIN_ROW_HEIGHT_DP
        val maximum = KeyboardPreferences.MAX_ROW_HEIGHT_DP
        val value = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ui.textSecondary)
        }
        val seekBar = SeekBar(this).apply {
            max = maximum - minimum
            contentDescription = getString(R.string.settings_height)
            progressTintList = ColorStateList.valueOf(ui.accent)
            thumbTintList = ColorStateList.valueOf(ui.accent)
            progressBackgroundTintList = ColorStateList.valueOf(ui.outline)
        }

        /** 高さを表示へ反映し、[save]なら保存して見本を描き直す。 */
        fun showHeight(height: Int, save: Boolean) {
            val normalized = KeyboardPreferences.normalizeRowHeightDp(height)
            value.text = getString(R.string.settings_height_dp, normalized)
            if (seekBar.progress != normalized - minimum) seekBar.progress = normalized - minimum
            if (save) {
                UzumiSettings.setKeyboardHeightDp(this, normalized)
                schedulePreview()
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) showHeight(minimum + progress, save = true)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        showHeight(preferences.rowHeightDp.roundToInt(), save = false)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@KeyboardSettingsActivity).apply {
                text = getString(R.string.settings_height)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = SettingsUi.MEDIUM
                setTextColor(ui.text)
            })
            addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.px(8) })
            addView(ui.textButton(getString(R.string.settings_height_reset)) {
                showHeight(KeyboardPreferences().rowHeightDp.roundToInt(), save = true)
            })
        }
        val ticks = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tickLabel(R.string.settings_height_low), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tickLabel(R.string.settings_height_high).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return ui.blockRow(header, seekBar, ticks).apply {
            (seekBar.layoutParams as LinearLayout.LayoutParams).apply {
                height = ui.px(SLIDER_HEIGHT_DP)
                topMargin = ui.px(8)
            }
            (ticks.layoutParams as LinearLayout.LayoutParams).topMargin = ui.px(4)
        }
    }

    /** スライダーの下の「低い」「高い」。 */
    private fun tickLabel(textId: Int): TextView = TextView(this).apply {
        text = getString(textId)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ui.textSecondary)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 候補または機能バーの段数を、1段・2段・3段の分割ボタンで選ぶ行。選ぶと保存して見本と項目を作り直す。 */
    private fun rowCountRow(titleId: Int, selectedRows: Int, save: (Context, Int) -> Unit): View {
        val counts = (ToolbarPreferences.MIN_ROWS..ToolbarPreferences.MAX_ROWS).toList()
        val choices = ui.segmented(counts.map { getString(R.string.settings_rows_value, it) }, counts.indexOf(selectedRows)) { index ->
            save(this, counts[index])
            rebuild()
        }.apply { contentDescription = getString(titleId) }
        return ui.row(getString(titleId), trailing = choices).view
    }

    /**
     * 機能バーの位置（上・下）の行。候補が1段のときは空く段が無く選べないため、薄くして理由を出す。
     */
    private fun toolbarPositionRow(toolbar: ToolbarPreferences): View {
        val selectable = toolbar.candidateRows >= 2
        val choices = ui.segmented(
            listOf(getString(R.string.settings_toolbar_position_top), getString(R.string.settings_toolbar_position_bottom)),
            selected = if (toolbar.toolbarOnTop) 0 else 1,
            enabled = selectable,
        ) { index ->
            UzumiSettings.setToolbarOnTop(this, index == 0)
            rebuild()
        }.apply { contentDescription = getString(R.string.settings_toolbar_position) }
        val row = ui.row(
            getString(R.string.settings_toolbar_position),
            summary = if (selectable) null else getString(R.string.settings_toolbar_position_disabled),
            trailing = choices,
        )
        if (!selectable) {
            row.title.alpha = OFF_TEXT_ALPHA
            row.summary.alpha = OFF_TEXT_ALPHA
        }
        return row.view
    }

    private companion object {
        /** 写真を選ぶStorage Access Frameworkのrequest code。 */
        const val REQUEST_BACKGROUND_IMAGE = 42

        /** 見本の角の丸み、見本の上の切り替えの欄の高さ、スクロール中の影の高さ（dp）。 */
        const val PREVIEW_CORNER_DP = 12
        const val PREVIEW_HEAD_DP = 34
        const val PREVIEW_ELEVATION_DP = 4

        /** 見本の高さの上限（画面の高さに対する割合）。 */
        const val PREVIEW_MAX_SCREEN_FRACTION = 0.5f

        /** 動かしている間に見本を作り直すまでの待ち時間（ミリ秒）。 */
        const val PREVIEW_DELAY_MS = 80L

        /** 背景画像があるとき、バーの地に残す最小の不透明度（キーボードと同じ55%）。 */
        const val MIN_BAR_ALPHA_WITH_IMAGE = 0.55f

        /** 最近コピーした文字の段の、左端の履歴ボタンの左の余白（dp、キーボードと同じ）。 */
        const val CLIPBOARD_BUTTON_MARGIN_DP = 4

        /** テーマと背景画像のカードの幅と、キーマップのカードの幅（dp）。 */
        const val CARD_WIDTH_DP = 112
        const val KEYMAP_CARD_WIDTH_DP = 120

        /** キーの高さのスライダーの高さ（dp）。 */
        const val SLIDER_HEIGHT_DP = 32

        /** 選べない行の題名と説明の不透明度（試作の55%）。 */
        const val OFF_TEXT_ALPHA = 0.55f

        /** 見本の候補と、その読み。 */
        const val SAMPLE_READING = "へんかん"
        val SAMPLE_CANDIDATES = listOf("変換", "返還", "変換する", "変換候補", "へんかん", "ヘンカン", "返還金", "変換キー", "変換器")

        /** 見本の最近コピーした文字。 */
        val SAMPLE_CLIPBOARD = listOf("15時から会議室B", "https://example.com/uzumi", "〒150-0002", "よろしくお願いします")
    }
}

/** テーマの表示名。「キーボード」の画面のカード、設定トップの説明行、きせかえの画面で使う。 */
internal fun keyboardThemeName(preset: ThemePreset): Int = when (preset) {
    ThemePreset.STANDARD -> R.string.kisekae_theme_standard
    ThemePreset.WASHI -> R.string.kisekae_theme_washi
    ThemePreset.AI -> R.string.kisekae_theme_ai
    ThemePreset.WAKATAKE -> R.string.kisekae_theme_wakatake
    ThemePreset.SAKURA -> R.string.kisekae_theme_sakura
    ThemePreset.SUMI -> R.string.kisekae_theme_sumi
    ThemePreset.CUSTOM -> R.string.kisekae_theme_custom
}
