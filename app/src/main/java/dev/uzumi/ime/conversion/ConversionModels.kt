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
    // 先頭から注目文節までの各文節の読みの書記素数。最後の値が伸縮後の注目文節の長さ。
    // ユーザーが文節を伸縮したときだけ指定し、nullならエンジンの区切りのまま変換する。
    val segmentLengths: List<Int>? = null,
    // 読みが12キーだけで入力されたか。trueなら小書き・濁点・半濁点の打ち分けを区別しない変換を使う。
    val kanaModifierInsensitive: Boolean = false,
) {
    /** 伸縮した注目文節の位置。応答を表示するとき、候補バーの対象をこの文節に保つ。 */
    val focusedSegmentIndex: Int
        get() = segmentLengths?.lastIndex ?: 0
}

/** 入力途中または入力後の予測要求。世代と読み、ゼロクエリ時の文脈を応答適用前に照合する。 */
data class PredictionRequest(
    val sessionEpoch: Long,
    val revision: Long,
    val reading: String = "",
    val precedingText: String = "",
    val incognito: Boolean = false,
    // 読みが12キーだけで入力されたか。trueなら小書き・濁点・半濁点の違いを除いて予測を照合する。
    val kanaModifierInsensitive: Boolean = false,
) {
    /** compositionが無い状態で周辺文字列から次の語を求める要求か。 */
    val isZeroQuery: Boolean
        get() = reading.isEmpty()
}

/** MozcまたはIME内の予測候補。idがnullの候補はMozcへ送らず、表記を直接確定する。 */
data class PredictionCandidate(
    val id: Int?,
    val value: String,
    val reading: String? = null,
    val englishVariant: Boolean = false,
)

/** 要求の世代に結び付いた予測候補。 */
data class PredictionResult(val request: PredictionRequest, val candidates: List<PredictionCandidate>)

/** 変換結果の一文節。readingはその文節が占める読み、valueは表示表記、candidatesはその文節だけの候補。 */
data class ConversionSegment(
    val reading: String,
    val value: String,
    // 表記がユーザー辞書の登録語か。エンジンの変換結果ではないため、確定してもエンジンへ学習させない。
    // エンジンの顔文字・絵文字を別の値へ差し替えた文節も、エンジンの選択と表記が違うためtrueにする。
    val fromUserDictionary: Boolean = false,
    // この文節の読みだけを置き換える候補。候補バーで注目したときに並べる。
    val candidates: List<ConversionCandidate> = emptyList(),
    // ユーザーが候補を選んだ文節か。選んだ文節だけを確定時にIME側の学習へ記録する。
    val chosen: Boolean = false,
    // IME側の学習語だけから作った候補を表示しているなら、その学習語の読み。エンジンはこの表記を知らない。
    val learnedReading: String? = null,
    // 同梱の英語辞書から追加した候補。確定時は英語語彙の学習規則を使う。
    val englishVariant: Boolean = false,
) {
    /** 表示中の表記がエンジンの候補か。falseの文節を含む確定は、エンジンへ区切りと表記を学習させない。 */
    val engineCandidate: Boolean
        get() = !fromUserDictionary && learnedReading == null
}

/**
 * 一文節の候補。idは候補欄の選択を識別し、readingはその候補が置き換える文節の読み。
 */
data class ConversionCandidate(
    val id: Int,
    val value: String,
    val reading: String,
    // ユーザー辞書の登録語か。エンジンの候補ではないため、確定してもエンジンへ学習を通知しない。
    val fromUserDictionary: Boolean = false,
    // IME側の学習語だけから作った候補なら、その学習語の読み。エンジンの候補ではないため、確定時はエンジンへ送らない。
    val learnedReading: String? = null,
    // 同梱の英語辞書から追加した候補。確定時は英語語彙の学習規則を使う。
    val englishVariant: Boolean = false,
) {
    companion object {
        /** エンジンの候補一覧に無い、表示中の表記そのものを候補にするときのID。エンジン・登録語・学習語のIDと重ならない。 */
        const val DISPLAYED_VALUE_ID = Int.MAX_VALUE
    }
}

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
 * chosenは、ユーザーが候補を選んだsegment（chosen）か。IME側の学習で新しい句を作るかの判定にだけ使う。
 */
data class LearnedSegment(
    val reading: String,
    val surface: String,
    val chosen: Boolean = false,
)

/**
 * エンジンが返した文節と先頭文節の候補。要求の世代とは独立した生の結果。
 * 各文節の候補はConversionSegment.candidatesに入り、headCandidatesは先頭文節の候補と同じ並びを持つ。
 */
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
     * 文節の読みを連結したものが要求した読み全体と一致し、各候補がその文節だけを置き換えるかを返す。
     */
    val isConsistent: Boolean
        get() = segments.isNotEmpty() &&
            headCandidates.isNotEmpty() &&
            segments.all { it.reading.isNotEmpty() && it.value.isNotEmpty() } &&
            segments.joinToString(separator = "") { it.reading } == request.reading &&
            headCandidates.all { it.reading.isNotEmpty() && it.reading == segments.first().reading } &&
            segments.all { segment ->
                segment.candidates.isEmpty() || segment.candidates.all { candidate ->
                    candidate.reading == segment.reading && candidate.value.isNotEmpty()
                }
            }

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
