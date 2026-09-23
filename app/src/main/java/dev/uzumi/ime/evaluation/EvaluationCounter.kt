package dev.uzumi.ime.evaluation

/**
 * 評価モードの間だけ、課題ごとに操作数、ライブ変換の表示と文節状態の変化、時刻を数える。
 * 既定では止まっていて[record]と[recordLive]は何もしない。
 * 開始と終了はdebugビルドだけに入る受信口から行うため、releaseビルドでは動かない。
 * 課題IDと件数・時刻だけを持ち、本文・読み・候補の文字列は受け取らない。IMEと受信口はどちらもUIスレッドから呼ぶ。
 */
class EvaluationCounter(
    // 時刻（epochからのミリ秒）を返す。JVMテストで決まった時刻を与えるために差し替えられる。
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // 数えている課題のID。nullなら評価モードではない。変換workerのthreadからも評価モードかを読むためvolatileにする。
    @Volatile
    private var taskId: String? = null
    private var counts = OperationCounts()
    private var live = LiveDisplayCounts()
    private var timing = TaskTiming()
    private var neural = NeuralCounts()
    private var samples = mutableListOf<TimingSample>()


    /** 数えている課題のID。評価モードでなければnull。 */
    val activeTaskId: String?
        get() = taskId

    /** 評価モードか。ライブ変換の前後比較は、評価モードの間だけ行うためにこれを見る。 */
    val isRecording: Boolean
        get() = taskId != null

    /**
     * 数えている課題で、IMEが受け取った押下の数（キー操作数と終端操作数の和）。評価モードでなければ-1。
     * 自動測定の道具が、送った押下をIMEが処理し終えたかを確かめるために読む。
     */
    val recordedPresses: Int
        get() = if (taskId == null) -1 else counts.keys + counts.terminators
    /**
     * 課題[taskId]の計数を0から始める。数えている課題があれば捨てる。
     * IDが英数字・`_`・`-`の16文字以内でなければ始めず、IDの欄へ本文が入らないようにする。
     */
    fun start(taskId: String): Boolean {
        if (!TASK_ID_PATTERN.matches(taskId)) return false
        this.taskId = taskId
        counts = OperationCounts()
        live = LiveDisplayCounts()
        timing = TaskTiming(startedMs = clock())
        neural = NeuralCounts()
        samples = mutableListOf()
        return true
    }

    /** 評価モードの間だけ、押下1回を種類[kind]として数え、最初の押下と最後の終端操作の時刻を残す。 */
    fun record(kind: OperationKind) {
        if (taskId == null) return
        counts += kind
        val now = clock()
        if (timing.firstInputMs < 0) timing = timing.copy(firstInputMs = now)
        if (kind == OperationKind.TERMINATOR) timing = timing.copy(lastTerminatorMs = now)
    }

    /** 評価モードの間だけ、ライブ変換の表示と文節状態の変化[change]を足す。 */
    fun recordLive(change: LiveDisplayCounts) {
        if (taskId != null) live += change
    }

    /** 評価モードの間だけ、ニューラル側とH3の計数[change]と時間の記録[newSamples]を足す。 */
    fun recordNeural(change: NeuralCounts, newSamples: List<TimingSample> = emptyList()) {
        if (taskId == null) return
        neural += change
        samples += newSamples
    }

    /**
     * 課題の計数を終えて結果を返し、評価モードを切る。数えている課題が無ければnull。
     * [digitViolation]は最終文の数字の並びの判定（[CorrectnessJudge.digitViolation]の値、判定しなければ-1）。
     */
    fun finish(digitViolation: Int = -1): TaskOperationCounts? {
        val id = taskId ?: return null
        val result = TaskOperationCounts(id, counts, live, timing.copy(finishedMs = clock()), neural, digitViolation, samples.toList())
        taskId = null
        counts = OperationCounts()
        live = LiveDisplayCounts()
        timing = TaskTiming()
        neural = NeuralCounts()
        samples = mutableListOf()
        return result
    }

    companion object {
        /** IMEと受信口が共有する、プロセスで一つの計数器。 */
        val shared = EvaluationCounter()

        // 課題IDとして受け付ける形。phase2c-tasks.tsvのid（N01など）を含み、文を入れられない長さに限る。
        private val TASK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,16}")
    }
}

/**
 * 1課題の時刻（epochからのミリ秒）。記録が無い項目は-1。時刻だけを持ち、どの文字を打った時刻かは持たない。
 * 押下の時刻は、IMEが操作を受け取った時刻であり、画面に指が触れた時刻ではない。
 */
data class TaskTiming(
    val startedMs: Long = -1,
    val firstInputMs: Long = -1,
    val lastTerminatorMs: Long = -1,
    val finishedMs: Long = -1,
) {
    /** 最初の押下から最後の終端操作までのミリ秒（評価条件の完了時間）。どちらかが無ければ-1。 */
    val elapsedMs: Long
        get() = if (firstInputMs < 0 || lastTerminatorMs < 0) -1 else lastTerminatorMs - firstInputMs
}

/**
 * 1課題の計数結果。取り出すときはTSVの1行にする。digitViolationは最終文の数字の並びの違反（1）、一致（0）、
 * 判定していない（-1）。samplesは時間の記録で、[timingRows]で別のTSVへ出す。
 */
data class TaskOperationCounts(
    val taskId: String,
    val counts: OperationCounts,
    val live: LiveDisplayCounts = LiveDisplayCounts(),
    val timing: TaskTiming = TaskTiming(),
    val neural: NeuralCounts = NeuralCounts(),
    val digitViolation: Int = -1,
    val samples: List<TimingSample> = emptyList(),
) {
    /** [TSV_HEADER]の列順の1行を返す。 */
    fun toTsvRow(): String = listOf(
        FORMAT_VERSION,
        taskId,
        counts.keys,
        counts.commits,
        counts.corrections,
        counts.terminators,
        live.displayChanges,
        live.flicker,
        live.stableOverwrites,
        live.chosenOverwrites,
        live.staleResultsDiscarded,
        live.toStable,
        live.toChosen,
        live.toProvisional,
        timing.startedMs,
        timing.finishedMs,
        timing.elapsedMs,
    ).plus(neural.values()).plus(digitViolation).joinToString("\t")

    /** 時間の記録を[TIMING_TSV_HEADER]の列順の行にする。 */
    fun timingRows(): List<String> = samples.map { sample ->
        listOf(FORMAT_VERSION, taskId, sample.kind.label, "%.2f".format(java.util.Locale.ROOT, sample.millis), sample.outcome?.label ?: "-")
            .joinToString("\t")
    }

    companion object {
        /** 計数の版。操作の分類や列を変えたら上げ、結果に記録する（評価条件の「評価用計数の版」）。 */
        const val FORMAT_VERSION = 3

        /** 取り出すTSVの見出し行。版2の列の後ろへ、Phase 3aのニューラル側とH3の列を足した。 */
        val TSV_HEADER = "counter_version\ttask_id\tkeys\tcommits\tcorrections\tterminators" +
            "\tdisplay_changes\tflicker\tstable_overwrites\tchosen_overwrites\tstale_results_discarded" +
            "\tto_stable\tto_chosen\tto_provisional\tstarted_ms\tfinished_ms\telapsed_ms\t" +
            NeuralCounts.TSV_COLUMNS.joinToString("\t") + "\tdigit_violation"

        /** 時間の記録のTSVの見出し行。 */
        const val TIMING_TSV_HEADER = "counter_version\ttask_id\tkind\tmillis\toutcome"
    }
}
