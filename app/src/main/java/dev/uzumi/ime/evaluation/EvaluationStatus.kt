package dev.uzumi.ime.evaluation

/**
 * 編集セッションの変換の進み具合。自動測定の道具が「変換結果が届き終えたか」を確かめるためだけに使う。
 * revisionと真偽だけを持ち、本文・読み・候補の文字列は持たない。
 */
data class ConversionProgress(
    /** 現在のrevision。ライブ変換ではコアの、明示変換では編集セッションのrevision。 */
    val revision: Long = -1,
    /** 明示変換で、応答を待っている変換要求があるか。 */
    val conversionPending: Boolean = false,
    /** 最後に送ったライブ変換の要求のrevision。送っていなければ-1。 */
    val liveRequestedRevision: Long = -1,
    /** 最後に受けたライブ変換の結果（適用したものと、古いため捨てたものの両方）の要求時のrevision。無ければ-1。 */
    val liveResultRevision: Long = -1,
    /**
     * 候補の取り直しを依頼し、まだ結果を受けていないか。エンジンが結果を返さない依頼（英数字だけの読みなど）では、
     * 次の依頼か結果まで真のまま残るため、待ち合わせには変換の直列threadの未処理数を使う。
     */
    val candidatesOutstanding: Boolean = false,
)

/**
 * IMEの状態の要約。debugビルドの受信口が`EVAL_STATUS`で返す。数値と真偽だけを持ち、本文を持たない。
 * 自動測定の道具は、固定の待ち時間の代わりにこれを短い間隔で読み、処理が終わるまで待つ。
 */
data class ImeEvaluationStatus(
    /** 入力を受け付ける編集セッションがあるか。 */
    val inputActive: Boolean,
    /** 入力先の欄を開いたアプリがUzumi自身か。 */
    val editorInOwnApp: Boolean,
    /** 入力先の欄のview id（EditorInfo.fieldId）。受信口が試験欄のidと比べる。 */
    val editorFieldId: Int,
    /** ライブ変換で動いているか。 */
    val live: Boolean,
    /** 未確定の表示（composition）があるか。 */
    val composing: Boolean,
    /** 変換の直列threadへ積んだ仕事のうち、まだ終わっていない数。 */
    val workerTasks: Int,
    /** 編集セッションの変換の進み具合。セッションが無ければ既定値。 */
    val progress: ConversionProgress,
    /** ニューラル変換の状態。Mozcだけの条件では既定値。 */
    val neural: NeuralRuntimeStatus = NeuralRuntimeStatus(),
)

/**
 * ニューラル変換の状態の要約（数値と真偽だけ）。自動測定の道具が、モデルの準備を待ってから課題を始めるために読む。
 * PSSはこの要約では測らず（測るのに時間がかかるため）、`EVAL_MEMORY`で別に問い合わせる。
 */
data class NeuralRuntimeStatus(
    /** モデルを選んでいるか（debugの選択）。falseならMozcだけ。 */
    val selected: Boolean = false,
    /**
     * 選んでいるモデルの識別子（`NeuralModelSpec.generation`、モデルのSHA-256の先頭48 bitとプロンプト形式の版から作る数値）。
     * Mozcだけなら0。自動測定の道具が、条件のモデルと一致することを確かめるために読む。
     */
    val modelGeneration: Long = 0,
    /** モデルの読み込みを終え、推論を受け付けるか。 */
    val ready: Boolean = false,
    /** モデルの読み込みの結果の番号（`NeuralRuntimeService`の定数）。未完了なら-1。 */
    val loadReason: Int = -1,
    /** bindから最初の推論結果までの時間（ミリ秒）。未計測なら-1。 */
    val coldStartMillis: Long = -1,
    /** 最後に受け取った`:neural`のPSS（KB）。未取得なら-1。 */
    val lastNeuralPssKb: Int = -1,
)

/**
 * IMEの状態を返す関数の置き場。IMEが起動中だけ登録し、debugビルドの受信口が`EVAL_STATUS`で呼ぶ。
 * 計数器（本文を持たないことをテストで固定している）とは分けて置く。releaseビルドには受信口が無いため呼ばれない。
 * UIスレッドから読み書きする。
 */
object EvaluationStatusSource {
    /** 登録中のIMEの状態を返す関数。IMEが起動していなければnull。 */
    var provider: (() -> ImeEvaluationStatus)? = null

    /** `:neural`へPSSを問い合わせる関数（結果は後から[NeuralRuntimeStatus.lastNeuralPssKb]へ入る）。IMEが起動していなければnull。 */
    var neuralMemoryRequester: (() -> Unit)? = null
}
