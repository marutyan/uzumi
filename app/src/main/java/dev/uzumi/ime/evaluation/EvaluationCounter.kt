package dev.uzumi.ime.evaluation

/**
 * 評価モードの間だけ、課題ごとに操作数を数える。既定では止まっていて[record]は何もしない。
 * 開始と終了はdebugビルドだけに入る受信口から行うため、releaseビルドでは動かない。
 * 課題IDと件数だけを持ち、本文・読み・候補の文字列は受け取らない。IMEと受信口はどちらもUIスレッドから呼ぶ。
 */
class EvaluationCounter {
    // 数えている課題のID。nullなら評価モードではない。
    private var taskId: String? = null
    private var counts = OperationCounts()

    /** 数えている課題のID。評価モードでなければnull。 */
    val activeTaskId: String?
        get() = taskId

    /**
     * 課題[taskId]の計数を0から始める。数えている課題があれば捨てる。
     * IDが英数字・`_`・`-`の16文字以内でなければ始めず、IDの欄へ本文が入らないようにする。
     */
    fun start(taskId: String): Boolean {
        if (!TASK_ID_PATTERN.matches(taskId)) return false
        this.taskId = taskId
        counts = OperationCounts()
        return true
    }

    /** 評価モードの間だけ、押下1回を種類[kind]として数える。 */
    fun record(kind: OperationKind) {
        if (taskId != null) counts += kind
    }

    /** 課題の計数を終えて結果を返し、評価モードを切る。数えている課題が無ければnull。 */
    fun finish(): TaskOperationCounts? {
        val id = taskId ?: return null
        val result = TaskOperationCounts(id, counts)
        taskId = null
        counts = OperationCounts()
        return result
    }

    companion object {
        /** IMEと受信口が共有する、プロセスで一つの計数器。 */
        val shared = EvaluationCounter()

        // 課題IDとして受け付ける形。phase2c-tasks.tsvのid（N01など）を含み、文を入れられない長さに限る。
        private val TASK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,16}")
    }
}

/** 1課題の計数結果。取り出すときはTSVの1行にする。 */
data class TaskOperationCounts(val taskId: String, val counts: OperationCounts) {
    /** [TSV_HEADER]の列順の1行を返す。 */
    fun toTsvRow(): String = listOf(
        FORMAT_VERSION,
        taskId,
        counts.keys,
        counts.commits,
        counts.corrections,
        counts.terminators,
    ).joinToString("\t")

    companion object {
        /** 計数の版。操作の分類や列を変えたら上げ、結果に記録する（評価条件の「評価用計数の版」）。 */
        const val FORMAT_VERSION = 1

        /** 取り出すTSVの見出し行。 */
        const val TSV_HEADER = "counter_version\ttask_id\tkeys\tcommits\tcorrections\tterminators"
    }
}
