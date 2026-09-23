package dev.uzumi.ime.conversion

/**
 * 一つの変換要求を識別する世代情報。
 * 応答はこの値が編集セッションの現在値と一致する場合だけ適用し、古い応答による上書きを防ぐ。
 */
data class ConversionRequest(
    val sessionEpoch: Long,
    val revision: Long,
    // 変換対象の読み。対象範囲は常にこの読みの全体で、IMEが所有するcomposition全体に一致する。
    val reading: String,
    // 学習・履歴の利用を止めるべき欄か。trueならエンジンへ変換を送る前にincognitoを指定する。
    val incognito: Boolean,
)

/** 変換結果の一文節。readingはその文節が占める読み、valueは現在の第一候補の表記。 */
data class ConversionSegment(
    val reading: String,
    val value: String,
    // 表記がユーザー辞書の登録語か。エンジンの変換結果ではないため、確定してもエンジンへ学習させない。
    val fromUserDictionary: Boolean = false,
)

/**
 * 先頭文節の候補。idはエンジン内部の候補識別子で、確定時の学習通知にだけ使う。
 * readingはこの候補を選んだときに確定される読みで、複数の文節をまとめる候補では先頭文節より長い。
 */
data class ConversionCandidate(
    val id: Int,
    val value: String,
    val reading: String,
    // ユーザー辞書の登録語か。エンジンの候補ではないため、確定してもエンジンへ学習を通知しない。
    val fromUserDictionary: Boolean = false,
)

/**
 * ライブ変換でエンジンが返す一文節。readingは文節の読み、valueは第一候補、
 * candidatesはその文節だけを置き換える候補（複数文節をまとめる候補と予測候補は除く）。
 */
data class EngineSegment(
    val reading: String,
    val value: String,
    val candidates: List<String>,
)

/**
 * ライブ変換で確定した一segmentの読みと表記。確定時にエンジンへ同じ区切りと表記を学習させるために使う。
 */
data class LearnedSegment(
    val reading: String,
    val surface: String,
)

/** エンジンが返した文節と先頭文節の候補。要求の世代とは独立した生の結果。 */
data class EngineConversion(
    val segments: List<ConversionSegment>,
    val headCandidates: List<ConversionCandidate>,
)

/**
 * 要求の世代に結び付けた変換結果。編集セッションはisConsistentを確かめてから表示へ反映する。
 */
data class ConversionResult(
    val request: ConversionRequest,
    val segments: List<ConversionSegment>,
    val headCandidates: List<ConversionCandidate>,
) {
    /**
     * 文節の読みを連結したものが要求した読み全体と一致し、候補が一つ以上あり、
     * 各候補が確定する読みが要求した読みの空でない接頭辞であるかを返す。
     */
    val isConsistent: Boolean
        get() = segments.isNotEmpty() &&
            headCandidates.isNotEmpty() &&
            segments.all { it.reading.isNotEmpty() && it.value.isNotEmpty() } &&
            segments.joinToString(separator = "") { it.reading } == request.reading &&
            headCandidates.all { it.reading.isNotEmpty() && request.reading.startsWith(it.reading) }

    /** 全文節の第一候補を連結した、compositionへ表示する文字列。 */
    val display: String
        get() = segments.joinToString(separator = "") { it.value }
}

/** 辞書を使えない理由。表示と記録のために区別する。 */
enum class EngineUnavailableReason {
    NATIVE_LIBRARY_MISSING,
    INITIALIZATION_FAILED,
    MINIMAL_ENGINE,
}

/**
 * 変換エンジンの利用可否。Ready以外では変換要求を送らず、既存のかな・カナ候補だけを使う。
 */
sealed interface EngineHealth {
    /** 初期化と既知の変換例による確認がまだ終わっていない。 */
    data object Loading : EngineHealth

    /** 辞書を読み込み、既知の変換例が期待どおりに変換された。 */
    data class Ready(val dataVersion: String) : EngineHealth

    /** 辞書が無い・壊れている、またはnativeを読み込めない。 */
    data class Unavailable(val reason: EngineUnavailableReason) : EngineHealth
}

/** workerから編集セッションへ返す一回分の応答。 */
sealed interface ConversionOutcome {
    /** 応答が対応する要求。 */
    val request: ConversionRequest

    /** エンジンが結果を返した。適用前に世代と整合性を照合する。 */
    data class Converted(val result: ConversionResult) : ConversionOutcome {
        override val request: ConversionRequest
            get() = result.request
    }

    /** エンジンが使えない、sessionを作れない、または結果が不正だった。読みへ戻す。 */
    data class Failed(override val request: ConversionRequest) : ConversionOutcome
}
