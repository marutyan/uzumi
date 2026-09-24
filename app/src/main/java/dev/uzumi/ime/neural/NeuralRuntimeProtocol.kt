package dev.uzumi.ime.neural

import dev.uzumi.ime.conversion.NeuralCallOutcome
import dev.uzumi.ime.conversion.NeuralFailure
import dev.uzumi.ime.conversion.NeuralModelOutput

/**
 * IMEのプロセスから見た、別プロセス（`:neural`）の推論serviceへの窓口。実装はAIDLのproxyを包んだもので、
 * JVMテストではfakeのserviceへ差し替える。どの呼び出しもonewayで、結果は[NeuralRuntimeListener]へ届く。
 */
interface NeuralRuntimePort {
    /** 要求番号付きで変換を頼む。promptはUTF-8のbyte列（モデルごとの形式で組み立て済み）。 */
    fun convert(requestId: Long, prompt: ByteArray, parseSpecial: Boolean, maxTokens: Int)

    /** 要求番号の推論を中断する。既に終わっていれば何もしない。 */
    fun cancel(requestId: Long)
}

/** 推論serviceからの結果を受け取る窓口。binderのthreadから呼ばれる。 */
interface NeuralRuntimeListener {
    /** モデルの読み込みを終えた。okがfalseならモデルを使えない。 */
    fun onLoaded(ok: Boolean)

    /** 要求番号の結果。terminationは[NeuralTermination]の値、textは出力のUTF-8のbyte列、inferenceMicrosは推論時間。 */
    fun onResult(requestId: Long, termination: Int, text: ByteArray, inferenceMicros: Long)
}

/**
 * native側の推論の終わり方の番号。JNIの橋渡し（`uzumi_neural_jni.cpp`）と同じ値を使う。
 * 番号はAIDLとJNIを通すための約束で、Kotlin側の意味は[toOutput]で決める。
 */
object NeuralTermination {
    const val EOG = 0
    const val MARKER = 1
    const val TOKEN_LIMIT = 2
    const val CANCELLED = 3
    const val CONTEXT_LIMIT = 4
    const val DECODE_ERROR = 5
    const val NOT_LOADED = 6
    const val TOKENIZE_FAILED = 7

    /**
     * 終わり方と出力を、変換器へ渡す結果へ写す。EOGと区切り記号での停止は正常な終わり、最大出力tokenへの到達は
     * 打ち切り（検査2で捨てる）とする。壊れたUTF-8は置換文字になり、検査1で捨てられる。
     */
    fun toOutput(termination: Int, text: ByteArray): NeuralModelOutput = when (termination) {
        EOG, MARKER -> NeuralModelOutput.Completed(String(text, Charsets.UTF_8))
        TOKEN_LIMIT -> NeuralModelOutput.Completed(String(text, Charsets.UTF_8), truncated = true)
        CANCELLED -> NeuralModelOutput.Cancelled
        NOT_LOADED -> NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE)
        else -> NeuralModelOutput.Failed(NeuralFailure.ERROR)
    }

    /** 終わり方を、評価用の記録の種類へ写す。 */
    fun toCallOutcome(termination: Int): NeuralCallOutcome = when (termination) {
        EOG, MARKER -> NeuralCallOutcome.COMPLETED
        TOKEN_LIMIT -> NeuralCallOutcome.TRUNCATED
        CANCELLED -> NeuralCallOutcome.CANCELLED
        NOT_LOADED -> NeuralCallOutcome.UNAVAILABLE
        else -> NeuralCallOutcome.ERROR
    }
}
