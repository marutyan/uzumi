package dev.uzumi.ime.compat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import dev.uzumi.ime.R
import dev.uzumi.ime.conversion.NeuralModelSpec
import dev.uzumi.ime.evaluation.CorrectnessJudge
import dev.uzumi.ime.evaluation.EvaluationCounter
import dev.uzumi.ime.evaluation.EvaluationStatusSource
import dev.uzumi.ime.evaluation.TaskOperationCounts
import dev.uzumi.ime.neural.NeuralSelection
import java.io.File
import java.util.Base64

/**
 * Phase 2cの評価用計数を`adb shell am broadcast`から開始・終了・取り出しする受信口。debugビルドだけに入る。
 * IMEと同じprocessで動き、[EvaluationCounter.shared]を操作する。結果は課題IDと件数・時刻のTSVで、本文は含まない。
 * 終えた課題の行は`noBackupFilesDir`のファイルへ追記し、端末のバックアップへ載せない。
 * `EVAL_STATUS`は計数を変えずに、IMEの状態（数値と真偽だけ）を返す。
 * Phase 3aでは、許容表記と最終文をBase64で受けて端末内で照合し（結果の件数だけを残す）、`NEURAL_SELECT`で
 * 評価の条件（Mozcだけ、またはモデル）を切り替え、`EVAL_MEMORY`でPSSを返し、`EVAL_DUMP_TIMINGS`で時間の記録を返す。
 */
class EvaluationCounterReceiver : BroadcastReceiver() {
    /** actionに応じて計数を操作し、結果を`am broadcast`の出力（result data）へ返す。 */
    override fun onReceive(context: Context, intent: Intent) {
        val counter = EvaluationCounter.shared
        val judge = CorrectnessJudge.shared
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val timings = File(context.noBackupFilesDir, TIMING_FILE_NAME)
        val message = when (intent.action) {
            ACTION_START -> {
                val taskId = intent.getStringExtra(EXTRA_TASK).orEmpty()
                // 前の版の見出しで始まるファイルへ新しい版の行を足すと列がずれるため、取り出して消すまで始めない。
                if (file.isFile && file.useLines { it.firstOrNull() } != TaskOperationCounts.TSV_HEADER) {
                    return reject("counter file has an older header; dump and clear first")
                }
                if (!counter.start(taskId)) return reject("invalid task id")
                // Phase 3a：許容表記（`|`区切りをBase64にしたもの）があれば、正→誤の遷移と数字の並びを端末内で判定する。
                judge.start(decodeBase64(intent.getStringExtra(EXTRA_ACCEPTED_B64))?.split('|').orEmpty())
                "started\t$taskId"
            }
            ACTION_FINISH -> {
                // Phase 3a：最終文（Base64）があれば、許容表記と数字の並びを照合する。文字列は照合にだけ使い残さない。
                val digitViolation = judge.digitViolation(decodeBase64(intent.getStringExtra(EXTRA_FINAL_B64)))
                judge.clear()
                val result = counter.finish(digitViolation) ?: return reject("not recording")
                appendRow(file, result)
                appendTimings(timings, result)
                result.toTsvRow()
            }
            ACTION_DUMP -> if (file.isFile) file.readText() else TaskOperationCounts.TSV_HEADER + "\n"
            ACTION_DUMP_TIMINGS -> if (timings.isFile) timings.readText() else TaskOperationCounts.TIMING_TSV_HEADER + "\n"
            ACTION_STATUS -> statusLine(counter)
            ACTION_MEMORY -> memoryLine()
            ACTION_NEURAL_SELECT -> {
                val key = intent.getStringExtra(EXTRA_MODEL).orEmpty()
                val spec = NeuralModelSpec.fromKey(key)
                if (spec == null && key != MOZC_ONLY) return reject("unknown model")
                if (!NeuralSelection.select(context, spec)) return reject("selection disabled")
                "selected\t${spec?.key ?: MOZC_ONLY}"
            }
            ACTION_CLEAR -> {
                counter.finish()
                judge.clear()
                file.delete()
                timings.delete()
                "cleared"
            }
            else -> return reject("unknown action")
        }
        setResult(Activity.RESULT_OK, message, null)
    }

    /**
     * IMEの状態を`名前=値`のタブ区切り1行で返す。値は数値と真偽（1・0）だけで、本文・読み・候補は含まない。
     * 自動測定の道具が、固定の待ち時間の代わりにこれを短い間隔で読み、変換と押下の処理が終わるまで待つ。
     * IMEが起動していなければ`ime=0`だけを返す。
     */
    private fun statusLine(counter: EvaluationCounter): String {
        val status = EvaluationStatusSource.provider?.invoke() ?: return "ime=0"
        val progress = status.progress
        fun flag(value: Boolean) = if (value) "1" else "0"
        return listOf(
            "ime=1",
            "input_active=${flag(status.inputActive)}",
            "task_field=${flag(status.editorInOwnApp && status.editorFieldId == R.id.phase2c_task_field)}",
            "live=${flag(status.live)}",
            "composing=${flag(status.composing)}",
            "recording=${flag(counter.isRecording)}",
            "presses=${counter.recordedPresses}",
            "worker_tasks=${status.workerTasks}",
            "conversion_pending=${flag(progress.conversionPending)}",
            "revision=${progress.revision}",
            "live_requested_revision=${progress.liveRequestedRevision}",
            "live_result_revision=${progress.liveResultRevision}",
            "candidates_outstanding=${flag(progress.candidatesOutstanding)}",
            "neural_selected=${flag(status.neural.selected)}",
            "neural_ready=${flag(status.neural.ready)}",
            "neural_load_reason=${status.neural.loadReason}",
        ).joinToString("\t")
    }

    /**
     * IMEのプロセスのPSSと、最後に受け取った`:neural`のPSS（KB）、cold startの時間を返し、`:neural`へPSSを問い合わせ直す。
     * `:neural`の値は非同期に届くため、次の呼び出しで新しい値になる。値は数値だけ。
     */
    private fun memoryLine(): String {
        val status = EvaluationStatusSource.provider?.invoke()
        EvaluationStatusSource.neuralMemoryRequester?.invoke()
        return listOf(
            "ime_pss_kb=${Debug.getPss()}",
            "neural_pss_kb=${status?.neural?.lastNeuralPssKb ?: -1}",
            "neural_cold_start_ms=${status?.neural?.coldStartMillis ?: -1}",
        ).joinToString("\t")
    }

    /** Base64のUTF-8の文字列を戻す。無い、または壊れていればnull。 */
    private fun decodeBase64(value: String?): String? =
        value?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }

    /** 課題1件の時間の記録を追記する。ファイルが無ければ見出し行から書く。 */
    private fun appendTimings(file: File, result: TaskOperationCounts) {
        if (!file.isFile) file.writeText(TaskOperationCounts.TIMING_TSV_HEADER + "\n")
        val rows = result.timingRows()
        if (rows.isNotEmpty()) file.appendText(rows.joinToString(separator = "\n", postfix = "\n"))
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
        const val ACTION_STATUS = "dev.uzumi.ime.debug.EVAL_STATUS"
        const val ACTION_DUMP_TIMINGS = "dev.uzumi.ime.debug.EVAL_DUMP_TIMINGS"
        const val ACTION_MEMORY = "dev.uzumi.ime.debug.EVAL_MEMORY"
        const val ACTION_NEURAL_SELECT = "dev.uzumi.ime.debug.NEURAL_SELECT"
        const val EXTRA_TASK = "task"
        const val EXTRA_ACCEPTED_B64 = "accepted_b64"
        const val EXTRA_FINAL_B64 = "final_b64"
        const val EXTRA_MODEL = "model"

        /** NEURAL_SELECTでMozcだけ（評価条件M）を選ぶ名前。 */
        const val MOZC_ONLY = "M"

        /** 時間の記録を追記するファイル名（Phase 3a）。 */
        const val TIMING_FILE_NAME = "phase3a-timings.tsv"

        /** 終えた課題の行を追記するファイル名。`run-as dev.uzumi.ime cat no_backup/<この名前>`でも読める。 */
        const val FILE_NAME = "phase2c-counts.tsv"
    }
}
