package dev.uzumi.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.uzumi.ime.conversion.NeuralModelSpec
import dev.uzumi.ime.keyboard.EmojiRecentStore
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

/**
 * Uzumiの設定画面。設定トップ（入力／キーボード／キーの操作と音、変換の学習／ユーザー辞書／クリップボード、
 * バックアップ／第三者ライセンス／開発用）と、その下層の画面を、画面の種類（[SettingsPage]）つきでこのActivityを開き直して見せる。
 * 「キーボード」だけは見本と並べ替えを持つため[KeyboardSettingsActivity]で見せる。値はUzumiSettingsだけに保存する。
 * 候補バーの⚙と、起動画面の「Uzumiの設定」から開く。変更は次にキーボードを表示したときから反映される。
 */
class SettingsActivity : Activity() {
    private var pendingDictionaryExport = false
    private lateinit var ui: SettingsUi
    private lateinit var page: SettingsPage
    private lateinit var scroll: ScrollView

    // 変換の学習の画面の「表示しない候補」の件数。一覧で解除したときに書き換える。
    private var hiddenCountView: TextView? = null

    private val density: Float
        get() = resources.displayMetrics.density

    /** 開く画面の種類を読み、見出しの帯とスクロールする中身の入れ物を作る。中身は[onResume]で作る。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDictionaryExport = savedInstanceState?.getBoolean(STATE_BACKUP_INCLUDE_DICTIONARY) ?: false
        page = intent.getStringExtra(EXTRA_PAGE)?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } } ?: SettingsPage.TOP
        // 変換エンジンの選択は、選択を使えるビルド（debug）だけに出す。releaseでは常にMozcだけで、画面も開かない。
        if (page == SettingsPage.DEVELOPER && !NeuralSelection.isEnabled(this)) {
            finish()
            return
        }
        ui = SettingsUi(this)
        title = getString(page.titleId)
        scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.appBar(getString(page.titleId)) { finish() })
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        applySystemInsets(root)
        setContentView(root)
    }

    /**
     * 画面の中身を今の設定値から作り直す。下層の画面やきせかえの画面で変えた値を、戻ったときに説明行とスイッチへ反映するため。
     * スクロール位置は保つ。
     */
    override fun onResume() {
        super.onResume()
        val scrollY = scroll.scrollY
        val content = ui.page()
        hiddenCountView = null
        when (page) {
            SettingsPage.TOP -> buildTop(content)
            SettingsPage.INPUT -> buildInput(content)
            SettingsPage.KEYS -> buildKeys(content)
            SettingsPage.LEARNING -> buildLearning(content)
            SettingsPage.CLIPBOARD -> buildClipboard(content)
            SettingsPage.BACKUP -> buildBackup(content)
            SettingsPage.DEVELOPER -> buildDeveloper(content)
        }
        scroll.removeAllViews()
        scroll.addView(content)
        scroll.post { scroll.scrollTo(0, scrollY) }
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

    /** 設定トップ。3つの群に行を並べ、各行の説明行に今の値を短く出す。 */
    private fun buildTop(content: LinearLayout) {
        val summaries = summaryText()
        val keyboard = UzumiSettings.keyboardPreferences(this)
        val learning = ui.row(getString(R.string.settings_learning), "", R.drawable.ic_school) { open(SettingsPage.LEARNING) }
        // 学習の設定は保存ファイルから読むため、UI thread外で読んでから説明行へ出す。
        learning.summary.visibility = View.INVISIBLE
        background {
            val enabled = LearningStores.get(this).isEnabled
            runOnUiThread {
                learning.summary.text = summaries.learning(enabled)
                learning.summary.visibility = View.VISIBLE
            }
        }
        ui.addBlock(content, ui.group(listOf(
            ui.row(getString(R.string.settings_input), summaries.input(UzumiSettings.isLiveConversionEnabled(this)),
                R.drawable.ic_language_japanese_kana) { open(SettingsPage.INPUT) }.view,
            ui.row(getString(R.string.settings_keyboard),
                summaries.keyboard(getString(keyboardThemeName(keyboard.appearance.preset)), keyboard.rowHeightDp.toInt()),
                R.drawable.ic_keyboard) { startActivity(Intent(this, KeyboardSettingsActivity::class.java)) }.view,
            ui.row(getString(R.string.settings_keys), summaries.keys(keyboard.keyPreview, keyboard.vibration, keyboard.keySound),
                R.drawable.ic_touch_app) { open(SettingsPage.KEYS) }.view,
        )))
        ui.addBlock(content, ui.group(listOf(
            learning.view,
            ui.row(getString(R.string.open_user_dictionary), getString(R.string.user_dictionary_summary), R.drawable.ic_menu_book) {
                startActivity(Intent(this, UserDictionaryActivity::class.java))
            }.view,
            ui.row(getString(R.string.settings_clipboard), summaries.clipboard(UzumiSettings.isClipboardHistoryEnabled(this)),
                R.drawable.ic_content_paste) { open(SettingsPage.CLIPBOARD) }.view,
        )))
        val lastGroup = mutableListOf<View>(
            ui.row(getString(R.string.settings_backup), getString(R.string.settings_backup_summary), R.drawable.ic_settings_backup_restore) {
                open(SettingsPage.BACKUP)
            }.view,
            ui.row(getString(R.string.open_licenses), iconRes = R.drawable.ic_description) {
                startActivity(Intent(this, LicenseActivity::class.java))
            }.view,
        )
        // 変換エンジンの選択は、選択を使えるビルド（debug）だけに出す。
        if (NeuralSelection.isEnabled(this)) {
            lastGroup += ui.row(getString(R.string.settings_section_developer), summaries.developer(NeuralSelection.current(this)),
                R.drawable.ic_code) { open(SettingsPage.DEVELOPER) }.view
        }
        ui.addBlock(content, ui.group(lastGroup))
    }

    /** 設定トップの説明行の文言の型を、resourceから読んで渡す。 */
    private fun summaryText(): SettingsSummaryText = SettingsSummaryText(
        on = getString(R.string.settings_on),
        off = getString(R.string.settings_off),
        inputFormat = getString(R.string.settings_summary_input),
        keyboardFormat = getString(R.string.settings_summary_keyboard),
        keysFormat = getString(R.string.settings_summary_keys),
        clipboardFormat = getString(R.string.settings_summary_clipboard),
        developerFormat = getString(R.string.settings_summary_developer),
        mozcName = getString(R.string.settings_neural_engine_mozc),
    )

    /** 下層の画面を、このActivityを画面の種類つきで開き直して見せる。 */
    private fun open(target: SettingsPage) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, target.name))
    }

    /**
     * 「入力」の画面。ライブ変換を置く。英字配列でのローマ字かな入力の2項目は、キーマップの選択へ置き換えるまでの間ここに残す。
     */
    private fun buildInput(content: LinearLayout) {
        ui.addBlock(content, ui.section(getString(R.string.settings_section_conversion), ui.group(listOf(
            switchRow(R.string.live_conversion_switch, R.string.live_conversion_description,
                UzumiSettings.isLiveConversionEnabled(this)) { UzumiSettings.setLiveConversionEnabled(this, it) },
        ))))
        ui.addBlock(content, ui.group(listOf(
            switchRow(R.string.settings_romaji_input, R.string.settings_romaji_input_description,
                UzumiSettings.isRomajiInputEnabled(this)) { UzumiSettings.setRomajiInputEnabled(this, it) },
            switchRow(R.string.settings_romaji_english_qwerty, R.string.settings_romaji_english_qwerty_description,
                UzumiSettings.isRomajiEnglishQwertyIncluded(this)) { UzumiSettings.setRomajiEnglishQwertyIncluded(this, it) },
        )))
    }

    /** 「キーの操作と音」の画面。押したキーの拡大表示と削除キーのなぞり、振動とキー音。 */
    private fun buildKeys(content: LinearLayout) {
        val preferences = UzumiSettings.keyboardPreferences(this)
        ui.addBlock(content, ui.section(getString(R.string.settings_section_keys), ui.group(listOf(
            switchRow(R.string.settings_key_preview, null, preferences.keyPreview) { UzumiSettings.setKeyPreview(this, it) },
            switchRow(R.string.settings_delete_drag, null, preferences.deleteDrag) { UzumiSettings.setDeleteDrag(this, it) },
        ))))
        ui.addBlock(content, ui.section(getString(R.string.settings_section_sound), ui.group(listOf(
            switchRow(R.string.settings_vibration, null, preferences.vibration) { UzumiSettings.setVibration(this, it) },
            switchRow(R.string.settings_key_sound, null, preferences.keySound) { UzumiSettings.setKeySound(this, it) },
        ))))
    }

    /** 「変換の学習」の画面。学習のON/OFFと、表示しない候補の一覧、学習履歴の消去。 */
    private fun buildLearning(content: LinearLayout) {
        ui.addBlock(content, ui.group(listOf(learningRow())))
        val hidden = ui.row(getString(R.string.settings_hidden_candidates), "", R.drawable.ic_visibility_off) { showHiddenCandidates() }
        hidden.summary.visibility = View.INVISIBLE
        hiddenCountView = hidden.summary
        background {
            val count = HiddenCandidateStores.get(this).all().size
            runOnUiThread { showHiddenCount(count) }
        }
        ui.addBlock(content, ui.group(listOf(
            hidden.view,
            ui.row(getString(R.string.settings_clear_learning), iconRes = R.drawable.ic_delete, danger = true) { confirmClearLearning() }.view,
        )))
    }

    /** 「表示しない候補」の行の説明に件数を出す。 */
    private fun showHiddenCount(count: Int) {
        val view = hiddenCountView ?: return
        view.text = if (count > 0) getString(R.string.settings_hidden_count, count) else getString(R.string.settings_hidden_none)
        view.visibility = View.VISIBLE
    }

    /** 「クリップボード」の画面。履歴のON/OFFと、OFFにすると消えることの注記。 */
    private fun buildClipboard(content: LinearLayout) {
        ui.addBlock(content, ui.group(listOf(
            switchRow(R.string.settings_clipboard_history, R.string.settings_clipboard_history_description,
                UzumiSettings.isClipboardHistoryEnabled(this)) { enabled ->
                UzumiSettings.setClipboardHistoryEnabled(this, enabled)
                if (!enabled) Toast.makeText(this, R.string.settings_clipboard_history_cleared, Toast.LENGTH_SHORT).show()
            },
        )))
        ui.addBlock(content, ui.note(R.drawable.ic_warning, getString(R.string.settings_clipboard_history_note)))
    }

    /** 「バックアップ」の画面。設定の書き出しと読み込み。 */
    private fun buildBackup(content: LinearLayout) {
        ui.addBlock(content, ui.group(listOf(
            ui.row(getString(R.string.settings_backup_export), iconRes = R.drawable.ic_upload_file) { chooseBackupExport() }.view,
            ui.row(getString(R.string.settings_backup_import), iconRes = R.drawable.ic_file_open) { openBackupFile() }.view,
        )))
        ui.addBlock(content, ui.note(R.drawable.ic_info, getString(R.string.settings_backup_note)))
    }

    /** 「開発用」の画面。debugビルドだけにある変換エンジンの選択。 */
    private fun buildDeveloper(content: LinearLayout) {
        ui.addBlock(content, ui.banner(R.drawable.ic_code, getString(R.string.settings_developer_banner)))
        ui.addBlock(content, ui.section(getString(R.string.settings_section_engine), engineChoices()))
        ui.addBlock(content, ui.note(R.drawable.ic_info, getString(R.string.settings_neural_engine_note)))
    }

    /** ON/OFFの行。切り替えるとすぐ保存する。 */
    private fun switchRow(titleId: Int, descriptionId: Int?, checked: Boolean, onChange: (Boolean) -> Unit): View =
        ui.switchRow(getString(titleId), descriptionId?.let(::getString), checked, onChange).first.view

    /**
     * 変換の学習のON/OFF。学習の保存ファイルはUI thread外で読み、読み終わるまでスイッチを押せなくする。
     */
    private fun learningRow(): View {
        var ready = false
        val (row, switch) = ui.switchRow(getString(R.string.settings_learning), getString(R.string.settings_learning_description), true) { enabled ->
            if (ready) background { LearningStores.get(this).setEnabled(enabled) }
        }
        row.view.isEnabled = false
        switch.isEnabled = false
        background {
            val enabled = LearningStores.get(this).isEnabled
            runOnUiThread {
                switch.isChecked = enabled
                ready = true
                row.view.isEnabled = true
                switch.isEnabled = true
            }
        }
        return row.view
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
        val baseMessage = if (prepared.dictionaryEntries == null) {
            getString(R.string.settings_backup_import_confirm)
        } else {
            getString(R.string.settings_backup_import_confirm_with_dictionary, prepared.dictionaryEntries.size)
        }
        // 読み込みで履歴がONからOFFへ変わると固定を含む履歴を消すため、設定画面で直接OFFにする場合と同じく先に知らせる。
        val turnsHistoryOff = UzumiSettings.isClipboardHistoryEnabled(this) &&
            (prepared.document.values["clipboardHistory"] as? BackupJsonValue.BooleanValue)?.value == false
        val message = if (turnsHistoryOff) {
            baseMessage + "\n\n" + getString(R.string.settings_backup_import_clears_clipboard_history)
        } else {
            baseMessage
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
                showHiddenCount(surfaces.size)
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

    /**
     * 変換エンジン（開発用）の「Mozc／ZS／ZX／JS／JX」。ユーザーが実機でモデルを試すための項目で、選ぶとadbの
     * `NEURAL_SELECT`と同じ[NeuralSelection.select]で保存する。モデルのファイルが端末に無い項目は選べなくする。
     * 行のどこを押しても選べるよう、丸いボタンは行の左に置いて行ごと押させる。
     */
    private fun engineChoices(): View {
        val current = NeuralSelection.current(this)
        val modelDirectory = NeuralRuntimeConnection.modelDirectory(this)
        val radios = mutableListOf<RadioButton>()
        val rows = (listOf<NeuralModelSpec?>(null) + NeuralModelSpec.entries).map { spec ->
            // モデルを置いたか。ファイルの中身（SHA-256）は読み込み時に`:neural`が確かめるため、ここでは有無だけを見る。
            val available = spec == null || modelDirectory.resolve(spec.fileName).isFile
            val name = spec?.let { "${it.key}（${it.fileName.removeSuffix(".gguf")}）" }
                ?: getString(R.string.settings_neural_engine_mozc)
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                isChecked = spec == current
                isEnabled = available
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                buttonTintList = ColorStateList.valueOf(ui.accent)
                setOnCheckedChangeListener { _, checked -> if (checked) NeuralSelection.select(this@SettingsActivity, spec) }
            }
            radios += radio
            val label = if (available) name else getString(R.string.settings_neural_engine_missing, name)
            ui.row(label, leading = radio, onClick = if (available) {
                { radios.forEach { it.isChecked = it === radio } }
            } else {
                null
            }).view.apply {
                if (!available) alpha = SettingsUi.DISABLED_ALPHA
                accessibilityDelegate = ui.checkedRole(RadioButton::class.java.name) { radio.isChecked }
            }
        }
        return ui.group(rows)
    }

    private fun px(dp: Int): Int = (dp * density).toInt()

    /** 確認済みバックアップと、TSV検証済み辞書項目を保持する。 */
    private data class PreparedBackupImport(
        val document: SettingsBackupDocument,
        val dictionaryEntries: List<UserDictionaryEntry>?,
    )

    /** 読み込み処理の完了と、辞書の上限により一部追加できなかった状態を返す。 */
    private data class ImportApplicationResult(val dictionaryWasLimited: Boolean)

    companion object {
        /** 開く画面の種類（[SettingsPage]の名前）を渡すIntentのextra。 */
        private const val EXTRA_PAGE = "dev.uzumi.ime.settings_page"

        /** バックアップ用のStorage Access Framework request code。 */
        private const val REQUEST_BACKUP_IMPORT = 34

        /** バックアップ用のStorage Access Framework request code。 */
        private const val REQUEST_BACKUP_EXPORT = 35

        /** バックアップJSONのMIME type。 */
        private const val BACKUP_MIME_TYPE = "application/json"

        /** 初期ファイル名。 */
        private const val BACKUP_FILE_NAME = "uzumi-settings.json"

        /** 1ファイルの読み込み上限。辞書を含む設定バックアップを扱い、異常に大きな入力を避ける。 */
        private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

        /** SAF選択中の画面再生成で辞書を含める選択を引き継ぐstate key。 */
        private const val STATE_BACKUP_INCLUDE_DICTIONARY = "backup_include_dictionary"
    }
}

/** 設定の画面の種類と、見出しの帯に出す題名。「キーボード」は[KeyboardSettingsActivity]で見せるため含めない。 */
enum class SettingsPage(val titleId: Int) {
    TOP(R.string.settings_title),
    INPUT(R.string.settings_input),
    KEYS(R.string.settings_keys),
    LEARNING(R.string.settings_learning),
    CLIPBOARD(R.string.settings_clipboard),
    BACKUP(R.string.settings_backup),
    DEVELOPER(R.string.settings_section_developer),
}
