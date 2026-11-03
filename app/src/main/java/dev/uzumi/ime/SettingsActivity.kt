package dev.uzumi.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
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
import dev.uzumi.ime.dictionary.TsvParseResult
import dev.uzumi.ime.dictionary.UserDictionaries
import dev.uzumi.ime.dictionary.UserDictionaryEntry
import dev.uzumi.ime.dictionary.UserDictionaryTsv
import dev.uzumi.ime.evaluation.EvaluationCounter
import dev.uzumi.ime.learning.LearningStores
import dev.uzumi.ime.learning.HiddenCandidateStores
import dev.uzumi.ime.neural.NeuralRuntimeConnection
import dev.uzumi.ime.neural.NeuralSelection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Uzumiの設定画面。入力・表示・操作の反応・学習と辞書に分けて並べ、値はUzumiSettingsだけに保存する。
 * 候補バーの⚙と、導入画面の「Uzumiの設定」から開く。変更は次にキーボードを表示したときから反映される。
 */
class SettingsActivity : Activity() {
    private var pendingDictionaryExport = false

    private val density: Float
        get() = resources.displayMetrics.density

    /** 設定項目を追加UI依存なしで組み立てる。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDictionaryExport = savedInstanceState?.getBoolean(STATE_BACKUP_INCLUDE_DICTIONARY) ?: false
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

        content.addView(section(R.string.settings_section_feedback))
        content.addView(switchRow(R.string.settings_vibration, null,
            UzumiSettings.keyboardPreferences(this).vibration) { UzumiSettings.setVibration(this, it) })
        content.addView(switchRow(R.string.settings_key_sound, null,
            UzumiSettings.keyboardPreferences(this).keySound) { UzumiSettings.setKeySound(this, it) })

        content.addView(section(R.string.settings_section_learning))
        content.addView(learningRow())
        content.addView(linkRow(R.string.settings_hidden_candidates) { showHiddenCandidates() })
        content.addView(linkRow(R.string.settings_clear_learning) { confirmClearLearning() })
        content.addView(linkRow(R.string.settings_backup_export) { chooseBackupExport() })
        content.addView(linkRow(R.string.settings_backup_import) { openBackupFile() })
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

    /** ファイル選択中に画面が再生成されても、辞書を含める指定を保つ。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_BACKUP_INCLUDE_DICTIONARY, pendingDictionaryExport)
        super.onSaveInstanceState(outState)
    }

    /** SAFから戻ったファイルURIに応じて、設定バックアップを読み込むか書き出す。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_BACKUP_IMPORT -> {
                if (resultCode == RESULT_OK) data?.data?.let(::importBackup)
            }
            REQUEST_BACKUP_EXPORT -> {
                val includeDictionary = pendingDictionaryExport
                pendingDictionaryExport = false
                if (resultCode == RESULT_OK) data?.data?.let { exportBackup(it, includeDictionary) }
            }
        }
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

    /** 書き出すバックアップへユーザー辞書を含めるか選ぶ。学習履歴は選択肢に含めない。 */
    private fun chooseBackupExport() {
        val includeDictionary = CheckBox(this).apply {
            text = getString(R.string.settings_backup_include_dictionary)
            isChecked = true
            setPadding(px(24), px(12), px(24), px(12))
            minimumHeight = px(56)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_export)
            .setMessage(R.string.settings_backup_export_description)
            .setView(includeDictionary)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_backup_export) { _, _ ->
                pendingDictionaryExport = includeDictionary.isChecked
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = BACKUP_MIME_TYPE
                    putExtra(Intent.EXTRA_TITLE, BACKUP_FILE_NAME)
                }
                startActivityForResult(intent, REQUEST_BACKUP_EXPORT)
            }
            .show()
    }

    /** Storage Access FrameworkでJSONバックアップの読み込み元を選ぶ。 */
    private fun openBackupFile() {
        if (EvaluationCounter.shared.isRecording) {
            Toast.makeText(this, R.string.settings_backup_blocked_during_evaluation, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = BACKUP_MIME_TYPE
        }
        startActivityForResult(intent, REQUEST_BACKUP_IMPORT)
    }

    /** SAFへJSONファイルを書き出し、任意選択のユーザー辞書も同じ形式のTSVで内包する。 */
    private fun exportBackup(uri: Uri, includeDictionary: Boolean) {
        backgroundResult(
            work = {
                val dictionary = if (includeDictionary) UserDictionaries.get(applicationContext).exportText() else null
                val json = SettingsBackupCodec.encode(
                    SettingsBackupDocument(UzumiSettings.captureBackupSettings(applicationContext), dictionary),
                )
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("出力先を開けません")
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            },
            onSuccess = { Toast.makeText(this, R.string.settings_backup_export_done, Toast.LENGTH_LONG).show() },
            onFailure = { Toast.makeText(this, R.string.settings_backup_failed, Toast.LENGTH_LONG).show() },
        )
    }

    /** バックアップを読んで形式・設定値・任意のTSVを検査し、変更前の確認画面へ渡す。 */
    private fun importBackup(uri: Uri) {
        if (EvaluationCounter.shared.isRecording) {
            Toast.makeText(this, R.string.settings_backup_blocked_during_evaluation, Toast.LENGTH_LONG).show()
            return
        }
        backgroundResult(
            work = {
                val bytes = readBackupLimited(uri)
                val json = UserDictionaryTsv.decodeUtf8(bytes)
                    ?: throw SettingsBackupException("UTF-8のJSONではありません")
                val document = SettingsBackupCodec.decode(json)
                val settings = UzumiSettings.normalizeBackupSettings(document.values)
                val dictionaryEntries = document.userDictionaryTsv?.let { tsv ->
                    val parsed = UserDictionaryTsv.parse(tsv)
                    if (parsed.failures.isNotEmpty()) throw SettingsBackupException("辞書のTSVが正しくありません")
                    parsed.entries
                }
                PreparedBackupImport(document.copy(values = settings), dictionaryEntries)
            },
            onSuccess = { prepared -> confirmBackupImport(prepared) },
            onFailure = { Toast.makeText(this, R.string.settings_backup_invalid, Toast.LENGTH_LONG).show() },
        )
    }

    /** 上限を超えないか確かめながらSAFのJSON全体を読む。 */
    @Throws(IOException::class, SettingsBackupException::class)
    private fun readBackupLimited(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("入力元を開けません")
        return input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_BACKUP_BYTES) throw SettingsBackupException("バックアップが大きすぎます")
            }
            buffer.toByteArray()
        }
    }

    /** 確認後に反映し、辞書は既存TSV importと同じ追加・重複省略の規則で保存する。 */
    private fun confirmBackupImport(prepared: PreparedBackupImport) {
        val message = if (prepared.dictionaryEntries == null) {
            getString(R.string.settings_backup_import_confirm)
        } else {
            getString(R.string.settings_backup_import_confirm_with_dictionary, prepared.dictionaryEntries.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_import)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_backup_import_apply) { _, _ ->
                if (EvaluationCounter.shared.isRecording) {
                    Toast.makeText(this, R.string.settings_backup_blocked_during_evaluation, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                backgroundResult(
                    work = {
                        if (EvaluationCounter.shared.isRecording) throw SettingsBackupException("評価記録中です")
                        val dictionaryResult = prepared.dictionaryEntries?.let { entries ->
                            if (EvaluationCounter.shared.isRecording) throw SettingsBackupException("評価記録中です")
                            UserDictionaries.get(applicationContext).import(TsvParseResult(entries, emptyList()))
                        }
                        if (dictionaryResult?.storageFailed == true) throw IOException("辞書を保存できません")
                        UzumiSettings.applyBackupSettings(applicationContext, prepared.document.values)
                        ImportApplicationResult(dictionaryWasLimited = (dictionaryResult?.overLimit ?: 0) > 0)
                    },
                    onSuccess = { result ->
                        Toast.makeText(
                            this,
                            if (result.dictionaryWasLimited) R.string.settings_backup_import_partial else R.string.settings_backup_import_done,
                            Toast.LENGTH_LONG,
                        ).show()
                        recreate()
                    },
                    onFailure = { Toast.makeText(this, R.string.settings_backup_failed, Toast.LENGTH_LONG).show() },
                )
            }
            .show()
    }

    /** ファイルI/Oと設定保存を別スレッドで行い、結果の通知だけUIへ戻す。 */
    private fun <T> backgroundResult(work: () -> T, onSuccess: (T) -> Unit, onFailure: () -> Unit) {
        Thread({
            val result = try {
                Result.success(work())
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: SettingsBackupException) {
                Result.failure(error)
            } catch (error: RuntimeException) {
                Result.failure(error)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(onSuccess = onSuccess, onFailure = { onFailure() })
            }
        }, "uzumi-settings-backup").start()
    }

    /** 非表示候補を一覧に出し、解除を押した表記だけ設定から取り除く。 */
    private fun showHiddenCandidates() {
        background {
            val surfaces = HiddenCandidateStores.get(this).all().sorted()
            runOnUiThread {
                if (surfaces.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_hidden_candidates)
                        .setMessage(R.string.settings_hidden_candidates_empty)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val unhideButtons = mutableListOf<Pair<Button, String>>()
                surfaces.forEach { surface ->
                    rows.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(TextView(this@SettingsActivity).apply {
                            text = surface
                            textSize = 16f
                            setPadding(px(12), 0, px(8), 0)
                        }, LinearLayout.LayoutParams(0, px(52), 1f))
                        addView(Button(this@SettingsActivity).apply {
                            text = getString(R.string.settings_hidden_candidate_unhide)
                            unhideButtons += this to surface
                        })
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56)))
                }
                val scroll = ScrollView(this).apply { addView(rows); isFillViewport = true }
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.settings_hidden_candidates)
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                unhideButtons.forEach { (button, surface) ->
                    button.setOnClickListener {
                        button.isEnabled = false
                        dialog.dismiss()
                        background {
                            HiddenCandidateStores.get(this@SettingsActivity).unhide(surface)
                            runOnUiThread { if (!isFinishing) showHiddenCandidates() }
                        }
                    }
                }
                dialog.show()
            }
        }
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

    private fun toolbarActionLabel(action: ToolbarAction): String = when (action) {
        ToolbarAction.PASTE -> getString(R.string.toolbar_paste)
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

    /** 確認済みバックアップと、TSV検証済み辞書項目を保持する。 */
    private data class PreparedBackupImport(
        val document: SettingsBackupDocument,
        val dictionaryEntries: List<UserDictionaryEntry>?,
    )

    /** 読み込み処理の完了と、辞書の上限により一部追加できなかった状態を返す。 */
    private data class ImportApplicationResult(val dictionaryWasLimited: Boolean)

    private companion object {
        /** 補足の説明を項目名より控えめに見せる不透明度。 */
        const val DESCRIPTION_ALPHA = 0.7f

        /** バックアップ用のStorage Access Framework request code。 */
        const val REQUEST_BACKUP_IMPORT = 34

        /** バックアップ用のStorage Access Framework request code。 */
        const val REQUEST_BACKUP_EXPORT = 35

        /** バックアップJSONのMIME type。 */
        const val BACKUP_MIME_TYPE = "application/json"

        /** 初期ファイル名。 */
        const val BACKUP_FILE_NAME = "uzumi-settings.json"

        /** 1ファイルの読み込み上限。辞書を含む設定バックアップを扱い、異常に大きな入力を避ける。 */
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

        /** SAF選択中の画面再生成で辞書を含める選択を引き継ぐstate key。 */
        const val STATE_BACKUP_INCLUDE_DICTIONARY = "backup_include_dictionary"
    }
}
