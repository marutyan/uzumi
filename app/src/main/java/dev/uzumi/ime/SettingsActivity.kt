package dev.uzumi.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.uzumi.ime.conversion.NeuralModelSpec
import dev.uzumi.ime.keyboard.EmojiRecentStore
import dev.uzumi.ime.keyboard.KeyboardPreferences
import dev.uzumi.ime.keyboard.ToolbarAction
import dev.uzumi.ime.keyboard.ToolbarPreferences
import dev.uzumi.ime.learning.LearningStores
import dev.uzumi.ime.neural.NeuralRuntimeConnection
import dev.uzumi.ime.neural.NeuralSelection
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Uzumiの設定画面。入力・表示・操作の反応・学習と辞書に分けて並べ、値はUzumiSettingsだけに保存する。
 * 候補バーの⚙と、導入画面の「Uzumiの設定」から開く。変更は次にキーボードを表示したときから反映される。
 */
class SettingsActivity : Activity() {
    private val density: Float
        get() = resources.displayMetrics.density

    /** 設定項目を追加UI依存なしで組み立てる。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(24))
        }

        content.addView(section(R.string.settings_section_input))
        content.addView(switchRow(R.string.live_conversion_switch, R.string.live_conversion_description,
            UzumiSettings.isLiveConversionEnabled(this)) { UzumiSettings.setLiveConversionEnabled(this, it) })
        content.addView(switchRow(R.string.settings_romaji_input, R.string.settings_romaji_input_description,
            UzumiSettings.isRomajiInputEnabled(this)) { UzumiSettings.setRomajiInputEnabled(this, it) })
        content.addView(switchRow(R.string.settings_romaji_english_qwerty, R.string.settings_romaji_english_qwerty_description,
            UzumiSettings.isRomajiEnglishQwertyIncluded(this)) { UzumiSettings.setRomajiEnglishQwertyIncluded(this, it) })
        content.addView(switchRow(R.string.settings_delete_drag, R.string.settings_delete_drag_description,
            UzumiSettings.keyboardPreferences(this).deleteDrag) { UzumiSettings.setDeleteDrag(this, it) })

        content.addView(section(R.string.settings_section_display))
        content.addView(label(R.string.settings_height, R.string.settings_height_description))
        content.addView(heightControl())
        content.addView(switchRow(R.string.settings_key_preview, R.string.settings_key_preview_description,
            UzumiSettings.keyboardPreferences(this).keyPreview) { UzumiSettings.setKeyPreview(this, it) })

        content.addView(section(R.string.settings_section_toolbar))
        content.addView(label(R.string.settings_toolbar_tiles, R.string.settings_toolbar_tiles_description))
        content.addView(toolbarActionControl())
        content.addView(rowCountControl(
            R.string.settings_toolbar_rows,
            UzumiSettings.toolbarPreferences(this).toolbarRows,
            UzumiSettings::setToolbarRows,
        ))
        content.addView(rowCountControl(
            R.string.settings_candidate_rows,
            UzumiSettings.toolbarPreferences(this).candidateRows,
            UzumiSettings::setCandidateRows,
        ))
        content.addView(switchRow(
            R.string.settings_clipboard_history,
            R.string.settings_clipboard_history_description,
            UzumiSettings.isClipboardHistoryEnabled(this),
        ) { UzumiSettings.setClipboardHistoryEnabled(this, it) })

        content.addView(section(R.string.settings_section_feedback))
        content.addView(switchRow(R.string.settings_vibration, null,
            UzumiSettings.keyboardPreferences(this).vibration) { UzumiSettings.setVibration(this, it) })
        content.addView(switchRow(R.string.settings_key_sound, null,
            UzumiSettings.keyboardPreferences(this).keySound) { UzumiSettings.setKeySound(this, it) })

        content.addView(section(R.string.settings_section_learning))
        content.addView(learningRow())
        content.addView(linkRow(R.string.settings_clear_learning) { confirmClearLearning() })
        content.addView(linkRow(R.string.open_user_dictionary) {
            startActivity(Intent(this, UserDictionaryActivity::class.java))
        })
        content.addView(linkRow(R.string.open_licenses) {
            startActivity(Intent(this, LicenseActivity::class.java))
        })

        // 変換エンジンの選択は、選択を使えるビルド（debug）だけに出す。releaseでは常にMozcだけで、項目も出さない。
        if (NeuralSelection.isEnabled(this)) {
            content.addView(section(R.string.settings_section_developer))
            content.addView(label(R.string.settings_neural_engine, R.string.settings_neural_engine_description))
            content.addView(engineChoices())
        }

        val scrollView = ScrollView(this).apply { addView(content) }
        applySystemInsets(scrollView)
        setContentView(scrollView)
    }

    /** 分類の見出し。 */
    private fun section(titleId: Int): TextView = TextView(this).apply {
        text = getString(titleId)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.kb_accent))
        setPadding(0, px(20), 0, px(4))
    }

    /** 項目名と補足の説明。 */
    private fun label(titleId: Int, descriptionId: Int?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(10), 0, px(4))
        addView(TextView(this@SettingsActivity).apply {
            text = getString(titleId)
            textSize = 16f
        })
        if (descriptionId != null) {
            addView(TextView(this@SettingsActivity).apply {
                text = getString(descriptionId)
                textSize = 13f
                alpha = DESCRIPTION_ALPHA
            })
        }
    }

    /** ON/OFFの項目。切り替えるとすぐ保存する。 */
    private fun switchRow(titleId: Int, descriptionId: Int?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = px(56)
            val switch = Switch(this@SettingsActivity).apply {
                isChecked = checked
                contentDescription = getString(titleId)
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }
            addView(label(titleId, descriptionId), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch)
            // 行のどこを押しても切り替わるようにし、小さなスイッチだけを狙わなくてよくする
            setOnClickListener { switch.toggle() }
        }
    }

    /**
     * 変換の学習のON/OFF。学習の保存ファイルはUI thread外で読み、読み終わるまでスイッチを押せなくする。
     */
    private fun learningRow(): View {
        var ready = false
        val row = switchRow(R.string.settings_learning, R.string.settings_learning_description, true) { enabled ->
            if (ready) background { LearningStores.get(this).setEnabled(enabled) }
        }
        row.isEnabled = false
        background {
            val enabled = LearningStores.get(this).isEnabled
            runOnUiThread {
                row.findSwitch()?.isChecked = enabled
                ready = true
                row.isEnabled = true
                row.findSwitch()?.isEnabled = true
            }
        }
        row.findSwitch()?.isEnabled = false
        return row
    }

    /** 学習履歴を消す前に確認する。消去はファイルを書き換えるためUI thread外で行う。 */
    private fun confirmClearLearning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_learning)
            .setMessage(R.string.settings_clear_learning_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_clear_learning_ok) { _, _ ->
                background {
                    LearningStores.get(this).clearAll()
                    EmojiRecentStore(this).clear()
                    runOnUiThread { Toast.makeText(this, R.string.settings_clear_learning_done, Toast.LENGTH_SHORT).show() }
                }
            }
            .show()
    }

    /** ファイルI/Oを伴う処理をUI thread外で一度だけ行う。 */
    private fun background(work: () -> Unit) {
        Thread(work, "uzumi-settings").start()
    }

    /** 行の中のスイッチを探す。 */
    private fun View.findSwitch(): Switch? = (this as? LinearLayout)?.let { row ->
        (0 until row.childCount).map(row::getChildAt).filterIsInstance<Switch>().firstOrNull()
    }

    /** キー1段の高さをスライダーとdp入力のどちらからも変更できるようにする。 */
    private fun heightControl(): LinearLayout {
        val minimum = KeyboardPreferences.MIN_ROW_HEIGHT_DP
        val maximum = KeyboardPreferences.MAX_ROW_HEIGHT_DP
        var selectedHeight = UzumiSettings.keyboardHeightDp(this)
        var updatingInput = false

        val valueLabel = TextView(this).apply { textSize = 14f }
        val heightInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelectAllOnFocus(true)
            gravity = android.view.Gravity.CENTER
            contentDescription = getString(R.string.settings_height_input_description)
        }
        val seekBar = SeekBar(this).apply {
            max = maximum - minimum
            contentDescription = getString(R.string.settings_height)
        }

        fun showHeight(value: Int, updateInput: Boolean, save: Boolean) {
            selectedHeight = KeyboardPreferences.normalizeRowHeightDp(value)
            valueLabel.text = getString(R.string.settings_height_value, selectedHeight)
            val progress = selectedHeight - minimum
            if (seekBar.progress != progress) seekBar.progress = progress
            if (updateInput && heightInput.text.toString() != selectedHeight.toString()) {
                updatingInput = true
                // 数値入力と保存値の読み取りを一致させるため、入力欄はASCIIの数字にそろえる。
                heightInput.setText(String.format(Locale.ROOT, "%d", selectedHeight))
                heightInput.setSelection(heightInput.text.length)
                updatingInput = false
            }
            if (save) UzumiSettings.setKeyboardHeightDp(this, selectedHeight)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) showHeight(minimum + progress, updateInput = true, save = true)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        heightInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                if (updatingInput) return
                val value = text?.toString()?.toIntOrNull() ?: return
                if (value in minimum..maximum) showHeight(value, updateInput = false, save = true)
            }
        })
        heightInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = heightInput.text.toString().toIntOrNull() ?: selectedHeight
                showHeight(value, updateInput = true, save = true)
            }
        }
        heightInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                heightInput.clearFocus()
                true
            } else {
                false
            }
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(heightInput, LinearLayout.LayoutParams(px(88), px(48)))
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.settings_height_unit)
                setPadding(px(8), 0, 0, 0)
            })
        }
        val reset = Button(this).apply {
            text = getString(R.string.settings_height_reset)
            setOnClickListener {
                heightInput.clearFocus()
                showHeight(KeyboardPreferences().rowHeightDp.roundToInt(), updateInput = true, save = true)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(4), 0, px(8))
            addView(valueLabel)
            addView(seekBar)
            addView(inputRow)
            addView(reset)
            showHeight(selectedHeight, updateInput = true, save = false)
        }
    }

    /** 機能バーへ常時表示するタイルを選び、上下ボタンで順序を変える。 */
    private fun toolbarActionControl(): View {
        val selected = UzumiSettings.toolbarPreferences(this).selectedActions
        return Button(this).apply {
            text = toolbarActionSummary(selected)
            contentDescription = getString(R.string.settings_toolbar_tiles)
            setOnClickListener { showToolbarActionDialog(this) }
        }
    }

    /** 選択可能な機能の一覧をチェック欄と上下ボタンで編集する。 */
    private fun showToolbarActionDialog(summaryButton: Button) {
        val preferences = UzumiSettings.toolbarPreferences(this)
        val ordered = (preferences.selectedActions + ToolbarPreferences.AVAILABLE_ACTIONS)
            .distinct()
            .toMutableList()
        val selected = preferences.selectedActions.toMutableSet()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun redraw() {
            rows.removeAllViews()
            ordered.forEachIndexed { index, action ->
                val check = CheckBox(this).apply {
                    text = toolbarActionLabel(action)
                    isChecked = action in selected
                    minimumHeight = px(48)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(action) else selected.remove(action)
                    }
                }
                val up = Button(this).apply {
                    text = "↑"
                    contentDescription = getString(R.string.settings_toolbar_move_up, toolbarActionLabel(action))
                    minimumWidth = px(48)
                    minimumHeight = px(48)
                    isEnabled = index > 0
                    setOnClickListener {
                        if (index > 0) {
                            val item = ordered.removeAt(index)
                            ordered.add(index - 1, item)
                            redraw()
                        }
                    }
                }
                val down = Button(this).apply {
                    text = "↓"
                    contentDescription = getString(R.string.settings_toolbar_move_down, toolbarActionLabel(action))
                    minimumWidth = px(48)
                    minimumHeight = px(48)
                    isEnabled = index < ordered.lastIndex
                    setOnClickListener {
                        if (index < ordered.lastIndex) {
                            val item = ordered.removeAt(index)
                            ordered.add(index + 1, item)
                            redraw()
                        }
                    }
                }
                rows.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(up)
                    addView(down)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52)))
            }
        }
        redraw()
        val scroll = ScrollView(this).apply {
            addView(rows)
            isFillViewport = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_toolbar_tiles)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val actions = ordered.filter { it in selected }
                UzumiSettings.setToolbarActions(this, actions)
                summaryButton.text = toolbarActionSummary(actions)
            }
            .show()
    }

    /** 機能バーまたは候補バーの段数を1〜3段から選ぶ。 */
    private fun rowCountControl(titleId: Int, selectedRows: Int, save: (Context, Int) -> Unit): View {
        val title = TextView(this).apply {
            text = getString(titleId)
            textSize = 14f
            setPadding(0, px(8), 0, px(2))
        }
        val choices = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            (ToolbarPreferences.MIN_ROWS..ToolbarPreferences.MAX_ROWS).forEach { count ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = getString(R.string.settings_rows_value, count)
                    minimumHeight = px(48)
                    isChecked = count == selectedRows
                    setOnCheckedChangeListener { _, checked -> if (checked) save(this@SettingsActivity, count) }
                })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(4), 0, px(4))
            addView(title)
            addView(choices)
        }
    }

    /** 機能選択画面に表示するタイル名を返す。 */
    private fun toolbarActionLabel(action: ToolbarAction): String = when (action) {
        ToolbarAction.PASTE -> getString(R.string.toolbar_paste)
        ToolbarAction.CLIPBOARD_HISTORY -> getString(R.string.toolbar_clipboard_history)
        ToolbarAction.UNDO -> getString(R.string.toolbar_undo)
        ToolbarAction.USER_DICTIONARY -> getString(R.string.open_user_dictionary)
        ToolbarAction.SETTINGS -> getString(R.string.open_settings)
        ToolbarAction.CLOSE_KEYBOARD -> getString(R.string.close_keyboard)
    }

    private fun toolbarActionSummary(actions: List<ToolbarAction>): String =
        actions.joinToString("・") { toolbarActionLabel(it) }
            .ifEmpty { getString(R.string.settings_toolbar_tiles_none) }

    /**
     * 変換エンジン（開発用）の「Mozc／ZS／ZX／JS／JX」。ユーザーが実機でモデルを試すための項目で、選ぶとadbの
     * `NEURAL_SELECT`と同じ[NeuralSelection.select]で保存する。モデルのファイルが端末に無い項目は選べなくする。
     */
    private fun engineChoices(): RadioGroup {
        val current = NeuralSelection.current(this)
        val modelDirectory = NeuralRuntimeConnection.modelDirectory(this)
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            (listOf<NeuralModelSpec?>(null) + NeuralModelSpec.entries).forEach { spec ->
                // モデルを置いたか。ファイルの中身（SHA-256）は読み込み時に`:neural`が確かめるため、ここでは有無だけを見る。
                val available = spec == null || modelDirectory.resolve(spec.fileName).isFile
                val name = spec?.let { "${it.key}（${it.fileName.removeSuffix(".gguf")}）" }
                    ?: getString(R.string.settings_neural_engine_mozc)
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = if (available) name else getString(R.string.settings_neural_engine_missing, name)
                    minimumHeight = px(48)
                    isEnabled = available
                    isChecked = spec == current
                    setOnCheckedChangeListener { _, checked -> if (checked) NeuralSelection.select(this@SettingsActivity, spec) }
                })
            }
        }
    }

    /** 別の画面を開く項目。 */
    private fun linkRow(titleId: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = getString(titleId)
        textSize = 16f
        minimumHeight = px(56)
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun px(dp: Int): Int = (dp * density).toInt()

    private companion object {
        /** 補足の説明を項目名より控えめに見せる不透明度。 */
        const val DESCRIPTION_ALPHA = 0.7f
    }
}
