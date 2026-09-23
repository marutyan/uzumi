package dev.uzumi.ime.compat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.uzumi.ime.evaluation.EvaluationCounter
import dev.uzumi.ime.evaluation.TaskOperationCounts
import java.io.File

/**
 * Phase 2cの評価用計数を`adb shell am broadcast`から開始・終了・取り出しする受信口。debugビルドだけに入る。
 * IMEと同じprocessで動き、[EvaluationCounter.shared]を操作する。結果は課題IDと件数・時刻のTSVで、本文は含まない。
 * 終えた課題の行は`noBackupFilesDir`のファイルへ追記し、端末のバックアップへ載せない。
 */
class EvaluationCounterReceiver : BroadcastReceiver() {
    /** actionに応じて計数を操作し、結果を`am broadcast`の出力（result data）へ返す。 */
    override fun onReceive(context: Context, intent: Intent) {
        val counter = EvaluationCounter.shared
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val message = when (intent.action) {
            ACTION_START -> {
                val taskId = intent.getStringExtra(EXTRA_TASK).orEmpty()
                // 前の版の見出しで始まるファイルへ新しい版の行を足すと列がずれるため、取り出して消すまで始めない。
                if (file.isFile && file.useLines { it.firstOrNull() } != TaskOperationCounts.TSV_HEADER) {
                    return reject("counter file has an older header; dump and clear first")
                }
                if (counter.start(taskId)) "started\t$taskId" else return reject("invalid task id")
            }
            ACTION_FINISH -> {
                val result = counter.finish() ?: return reject("not recording")
                appendRow(file, result)
                result.toTsvRow()
            }
            ACTION_DUMP -> if (file.isFile) file.readText() else TaskOperationCounts.TSV_HEADER + "\n"
            ACTION_CLEAR -> {
                counter.finish()
                file.delete()
                "cleared"
            }
            else -> return reject("unknown action")
        }
        setResult(Activity.RESULT_OK, message, null)
    }

    /** 失敗を結果コードと短い理由で返す。 */
    private fun reject(reason: String) {
        setResult(Activity.RESULT_CANCELED, reason, null)
    }

    /** 課題1件の行をファイルへ追記する。ファイルが無ければ見出し行から書く。 */
    private fun appendRow(file: File, result: TaskOperationCounts) {
        if (!file.isFile) file.writeText(TaskOperationCounts.TSV_HEADER + "\n")
        file.appendText(result.toTsvRow() + "\n")
    }

    /** adbから送るactionとextraの名前。評価条件の文書の手順と一致させる。 */
    private companion object {
        const val ACTION_START = "dev.uzumi.ime.debug.EVAL_START"
        const val ACTION_FINISH = "dev.uzumi.ime.debug.EVAL_FINISH"
        const val ACTION_DUMP = "dev.uzumi.ime.debug.EVAL_DUMP"
        const val ACTION_CLEAR = "dev.uzumi.ime.debug.EVAL_CLEAR"
        const val EXTRA_TASK = "task"

        /** 終えた課題の行を追記するファイル名。`run-as dev.uzumi.ime cat no_backup/<この名前>`でも読める。 */
        const val FILE_NAME = "phase2c-counts.tsv"
    }
}
