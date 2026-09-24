package dev.uzumi.ime.conversion

/**
 * 変換workerから見たニューラル変換の窓口。実装は別プロセスの推論serviceとの受け渡し（`NeuralRuntimeClient`）で、
 * JVMテストではfakeへ差し替える。ライブ変換の要求ごとに[modelFor]で期限付きのモデルを作り、
 * 新しい要求が来たら[cancelInFlight]で古い要求の推論を止める。
 */
interface NeuralBackend {
    /** 使っているモデル。変換結果の世代とプロンプト形式を決める。 */
    val spec: NeuralModelSpec

    /**
     * 一つのライブ変換要求のあいだ使うモデルを返す。
     * - [deadlineNanos]（`System.nanoTime`の値）を過ぎても結果が無ければ時間超過として返す。
     * - [owner]はライブ変換要求の通し番号で、[cancelInFlight]がどの要求の推論を止めるかの照合に使う。
     * - [isSuperseded]がtrueなら、新しい要求が待っているため推論せず中断として返す。
     * - [record]へ一回の呼び出しごとの記録（cache hit、推論時間、結果の種類）を渡す。
     */
    fun modelFor(
        deadlineNanos: Long,
        owner: Long,
        isSuperseded: () -> Boolean,
        record: (NeuralCallRecord) -> Unit,
    ): NeuralKanaKanjiModel

    /** [newOwner]より前のライブ変換要求の推論を止める。UIスレッドから呼ばれる。 */
    fun cancelInFlight(newOwner: Long)
}

/** モデルへの一回の呼び出しの結果の種類。 */
enum class NeuralCallOutcome(val label: String) {
    COMPLETED("completed"),
    TRUNCATED("truncated"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled"),
    UNAVAILABLE("unavailable"),
    ERROR("error"),
}

/**
 * モデルへの一回の呼び出しの記録。sentは別プロセスへ要求を送ったか（cache hitと、送る前に期限を過ぎた場合はfalse）。
 * millisは、推論したものは別プロセスが測った推論時間、時間超過は打ち切るまで待った時間、中断は中断までの時間。
 * 読みや出力の文字列は持たない。
 */
data class NeuralCallRecord(
    val sent: Boolean,
    val cacheHit: Boolean,
    val millis: Double,
    val outcome: NeuralCallOutcome,
)
