package dev.uzumi.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.uzumi.ime.dictionary.UserDictionaries
import dev.uzumi.ime.dictionary.UserDictionaryCategory
import dev.uzumi.ime.dictionary.UserDictionaryEntry
import dev.uzumi.ime.dictionary.UserDictionaryError
import dev.uzumi.ime.dictionary.UserDictionaryImportSummary
import dev.uzumi.ime.dictionary.UserDictionaryRepository
import dev.uzumi.ime.dictionary.UserDictionaryRules
import dev.uzumi.ime.dictionary.UserDictionaryTsv
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ユーザー辞書の一覧・検索・追加・編集・削除と、利用者が選ぶファイルとのimport/exportを行う管理画面。
 * ファイルの読み書きは専用スレッドで行い、画面の更新だけをUIスレッドへ戻す。
 */
class UserDictionaryActivity : Activity() {
    // 辞書の読込み・保存とファイル入出力を直列に実行するスレッド。
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var repository: UserDictionaryRepository? = null
    private lateinit var searchField: EditText
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var actionButtons: List<Button>

    /** 画面を構築し、辞書を別スレッドで開いてから一覧を表示する。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.dictionary_title)

        val padding = dp(24)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.dictionary_title)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            text = getString(R.string.dictionary_loading)
        }
        content.addView(statusText)
        searchField = EditText(this).apply {
            hint = getString(R.string.dictionary_search_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) = refreshList()
            })
        }
        content.addView(searchField)
        actionButtons = listOf(
            actionButton(R.string.dictionary_add) { showEditDialog(original = null) },
            actionButton(R.string.dictionary_import) { openImportFile() },
            actionButton(R.string.dictionary_export) { createExportFile() },
        )
        actionButtons.forEach(content::addView)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(listContainer)

        val scrollView = ScrollView(this).apply { addView(content) }
        applySystemInsets(scrollView)
        setContentView(scrollView)

        setActionsEnabled(false)
        runInBackground({ UserDictionaries.get(this) }) { opened ->
            repository = opened
            setActionsEnabled(true)
            refreshList()
        }
    }

    /** 画面の破棄とともに入出力スレッドを止める。実行中の保存は完了まで続く。 */
    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }

    /** ファイル選択画面の結果を受け取り、importまたはexportを実行する。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_IMPORT -> importFrom(uri)
            REQUEST_EXPORT -> exportTo(uri)
        }
    }

    /** 検索語に合う項目で一覧を作り直す。表示件数は上限までに抑える。 */
    private fun refreshList() {
        val dictionary = repository ?: return
        val matches = dictionary.search(searchField.text.toString())
        listContainer.removeAllViews()
        matches.take(MAX_VISIBLE_ROWS).forEach { entry -> listContainer.addView(entryRow(entry)) }
        val status = StringBuilder(getString(R.string.dictionary_count, dictionary.allEntries().size, matches.size))
        if (matches.size > MAX_VISIBLE_ROWS) {
            status.append('\n').append(getString(R.string.dictionary_truncated, MAX_VISIBLE_ROWS))
        }
        if (dictionary.hadLoadProblem) {
            status.append('\n').append(getString(R.string.dictionary_load_problem))
        }
        statusText.text = status
    }

    /** 一覧の一行。表記を大きく、読みと分類を小さく表示し、押すと編集画面を開く。 */
    private fun entryRow(entry: UserDictionaryEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        TypedValue().also { value ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            setBackgroundResource(value.resourceId)
        }
        addView(TextView(context).apply {
            text = entry.surface
            textSize = 18f
        })
        addView(TextView(context).apply {
            text = getString(R.string.dictionary_row_detail, entry.reading, entry.category.label)
            textSize = 14f
        })
        setOnClickListener { showEditDialog(original = entry) }
    }

    /** 追加（originalがnull）または編集の入力画面を表示する。検証に失敗した場合は画面を閉じずに理由を示す。 */
    private fun showEditDialog(original: UserDictionaryEntry?) {
        val categories = UserDictionaryCategory.entries
        val readingField = EditText(this).apply {
            hint = getString(R.string.dictionary_reading_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(original?.reading.orEmpty())
        }
        val surfaceField = EditText(this).apply {
            hint = getString(R.string.dictionary_surface_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(original?.surface.orEmpty())
        }
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@UserDictionaryActivity,
                android.R.layout.simple_spinner_item,
                categories.map(UserDictionaryCategory::label),
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(categories.indexOf(original?.category ?: UserDictionaryCategory.NOUN))
        }
        val errorText = TextView(this).apply {
            setTextColor(ERROR_TEXT_COLOR)
            visibility = View.GONE
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(readingField)
            addView(surfaceField)
            addView(categorySpinner)
            addView(errorText)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (original == null) R.string.dictionary_add else R.string.dictionary_edit)
            .setView(form)
            .setPositiveButton(R.string.dictionary_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (original != null) builder.setNeutralButton(R.string.dictionary_delete, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entry = UserDictionaryEntry(
                    reading = readingField.text.toString(),
                    surface = surfaceField.text.toString(),
                    category = categories[categorySpinner.selectedItemPosition],
                )
                runInBackground({
                    val dictionary = checkNotNull(repository)
                    if (original == null) dictionary.add(entry) else dictionary.update(original, entry)
                }) { error ->
                    if (error == null) {
                        dialog.dismiss()
                        refreshList()
                    } else {
                        errorText.text = errorMessage(error)
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
            if (original != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    confirmDelete(original) { dialog.dismiss() }
                }
            }
        }
        dialog.show()
    }

    /** 削除前に確認し、削除に成功したらonDeletedを呼んで一覧を更新する。 */
    private fun confirmDelete(entry: UserDictionaryEntry, onDeleted: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dictionary_delete_confirm, entry.surface, entry.reading))
            .setPositiveButton(R.string.dictionary_delete) { _, _ ->
                runInBackground({ checkNotNull(repository).remove(entry) }) { error ->
                    if (error == null) {
                        onDeleted()
                        refreshList()
                    } else {
                        toast(errorMessage(error))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Storage Access Frameworkで読み込むファイルを利用者に選ばせる。 */
    private fun openImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    /** Storage Access Frameworkで書き出し先のファイルを利用者に作らせる。 */
    private fun createExportFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = EXPORT_MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, EXPORT_FILE_NAME)
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    /** 選ばれたファイルを読み、正しい行だけを既存辞書へ追加して結果を示す。読めない場合は辞書を変更しない。 */
    private fun importFrom(uri: Uri) {
        runInBackground<ImportOutcome>({
            val bytes = readLimited(uri) ?: return@runInBackground ImportOutcome.TooLarge
            val text = UserDictionaryTsv.decodeUtf8(bytes) ?: return@runInBackground ImportOutcome.NotUtf8
            ImportOutcome.Done(checkNotNull(repository).import(UserDictionaryTsv.parse(text)))
        }, onFailure = { toast(getString(R.string.dictionary_import_read_failed)) }) { outcome ->
            when (outcome) {
                ImportOutcome.TooLarge ->
                    toast(getString(R.string.dictionary_import_too_large, MAX_IMPORT_BYTES / BYTES_PER_MEGABYTE))
                ImportOutcome.NotUtf8 -> toast(getString(R.string.dictionary_import_not_utf8))
                is ImportOutcome.Done -> {
                    refreshList()
                    showImportSummary(outcome.summary)
                }
            }
        }
    }

    /** 選ばれたファイルへ現在の全項目を書き出す。 */
    private fun exportTo(uri: Uri) {
        runInBackground({
            val dictionary = checkNotNull(repository)
            val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("出力先を開けません")
            output.use { it.write(dictionary.exportText().toByteArray(Charsets.UTF_8)) }
            dictionary.allEntries().size
        }, onFailure = { toast(getString(R.string.dictionary_export_failed)) }) { count ->
            toast(getString(R.string.dictionary_export_done, count))
        }
    }

    /** 上限を超えないかを確かめながらファイル全体を読む。上限を超えたらnullを返す。 */
    @Throws(IOException::class)
    private fun readLimited(uri: Uri): ByteArray? {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("入力元を開けません")
        return input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_IMPORT_BYTES) return null
            }
            buffer.toByteArray()
        }
    }

    /** importの件数と、失敗行の先頭数件を行番号付きで示す。 */
    private fun showImportSummary(summary: UserDictionaryImportSummary) {
        val message = StringBuilder()
        if (summary.storageFailed) message.append(getString(R.string.dictionary_import_storage_failed)).append('\n')
        message.append(
            getString(
                R.string.dictionary_import_summary,
                summary.added,
                summary.duplicates,
                summary.overLimit,
                summary.failures.size,
            ),
        )
        summary.failures.take(MAX_REPORTED_FAILURES).forEach { failure ->
            message.append('\n').append(
                getString(R.string.dictionary_import_failure_line, failure.lineNumber, errorMessage(failure.error)),
            )
        }
        if (summary.failures.size > MAX_REPORTED_FAILURES) {
            message.append('\n').append(
                getString(R.string.dictionary_import_more_failures, summary.failures.size - MAX_REPORTED_FAILURES),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_import_result_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 処理を入出力スレッドで実行し、結果をUIスレッドへ戻す。画面が破棄された後の結果は捨てる。
     * 読込み・保存の失敗は例外としてonFailureへ渡す。
     */
    private fun <T> runInBackground(
        work: () -> T,
        onFailure: () -> Unit = { toast(getString(R.string.dictionary_storage_failed)) },
        onResult: (T) -> Unit,
    ) {
        if (ioExecutor.isShutdown) return
        ioExecutor.execute {
            val result = try {
                Result.success(work())
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: SecurityException) {
                Result.failure(error)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(onSuccess = onResult, onFailure = { onFailure() })
            }
        }
    }

    /** 辞書を開くまで、辞書を必要とする操作を無効にする。 */
    private fun setActionsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
        searchField.isEnabled = enabled
    }

    /** 画面上部の操作ボタンを作る。 */
    private fun actionButton(label: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = getString(label)
        setOnClickListener { onClick() }
    }

    /** 失敗理由を利用者向けの文言へ変換する。 */
    private fun errorMessage(error: UserDictionaryError): String = when (error) {
        UserDictionaryError.EMPTY_READING -> getString(R.string.dictionary_error_empty_reading)
        UserDictionaryError.READING_TOO_LONG ->
            getString(R.string.dictionary_error_reading_too_long, UserDictionaryRules.MAX_READING_LENGTH)
        UserDictionaryError.READING_NOT_HIRAGANA -> getString(R.string.dictionary_error_reading_not_hiragana)
        UserDictionaryError.EMPTY_SURFACE -> getString(R.string.dictionary_error_empty_surface)
        UserDictionaryError.SURFACE_TOO_LONG ->
            getString(R.string.dictionary_error_surface_too_long, UserDictionaryRules.MAX_SURFACE_LENGTH)
        UserDictionaryError.SURFACE_HAS_CONTROL_CHARACTER -> getString(R.string.dictionary_error_surface_control)
        UserDictionaryError.WRONG_COLUMN_COUNT -> getString(R.string.dictionary_error_column_count)
        UserDictionaryError.UNKNOWN_CATEGORY -> getString(R.string.dictionary_error_unknown_category)
        UserDictionaryError.DUPLICATE -> getString(R.string.dictionary_error_duplicate)
        UserDictionaryError.NOT_FOUND -> getString(R.string.dictionary_error_not_found)
        UserDictionaryError.TOO_MANY_ENTRIES -> getString(R.string.dictionary_error_too_many)
        UserDictionaryError.STORAGE_FAILED -> getString(R.string.dictionary_storage_failed)
    }

    /** 短い結果を通知する。 */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** dp単位の長さを画素数へ変換する。 */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** importの読込み段階の結果。辞書への反映に進んだ場合だけ件数を持つ。 */
    private sealed interface ImportOutcome {
        data object TooLarge : ImportOutcome
        data object NotUtf8 : ImportOutcome
        data class Done(val summary: UserDictionaryImportSummary) : ImportOutcome
    }

    private companion object {
        const val REQUEST_IMPORT = 1
        const val REQUEST_EXPORT = 2

        /** 一覧に一度に並べる最大件数。全件をViewにすると画面の構築が重くなるため、検索で絞り込ませる。 */
        const val MAX_VISIBLE_ROWS = 300

        /** import結果に列挙する失敗行の最大数。 */
        const val MAX_REPORTED_FAILURES = 20

        /** importで読むファイルの最大byte数。登録上限の件数を十分に収め、巨大ファイルでメモリを使い切らないようにする。 */
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val BYTES_PER_MEGABYTE = 1024 * 1024

        const val EXPORT_MIME_TYPE = "text/tab-separated-values"
        const val EXPORT_FILE_NAME = "uzumi-user-dictionary.tsv"

        // 入力エラーの文言に使う色。既存テーマのアクセント色と区別できる赤にする。
        const val ERROR_TEXT_COLOR = 0xFFB3261E.toInt()
    }
}
