package dev.uzumi.ime.conversion

/**
 * かな漢字変換エンジンの最小操作。実装はglobalな状態を持ち得るため、
 * ConversionWorkerの一つのthreadからだけ呼ぶ。JVMテストではfakeへ差し替える。
 */
interface ConversionEngine {
    /** native libraryと辞書を読み込み、辞書versionを返す。読み込めなければUnavailableを返す。 */
    fun load(): EngineHealth

    /** エンジン側のsessionを作り、そのIDを返す。失敗時はnull。 */
    fun createSession(): Long?

    /** エンジン側のsessionを破棄する。 */
    fun deleteSession(sessionId: Long)

    /**
     * 以後の変換で学習・履歴を使うかと、小書き・濁点・半濁点の打ち分けを区別しない変換（表記ゆれ変換）を使うかを
     * 切り替える。変換を送る前に呼ぶ。表記ゆれ変換は12キーだけで入れた読みに使う。
     */
    fun setIncognito(incognito: Boolean, kanaModifierInsensitive: Boolean = false): Boolean

    /**
     * sessionの状態を読みだけから作り直して変換し、文節と各文節の候補を返す。
     * segmentLengthsを渡すと、先頭から順に各文節をその書記素数へ伸縮してから候補を集める（明示変換の文節の伸縮）。
     */
    fun convert(sessionId: Long, reading: String, segmentLengths: List<Int>? = null): EngineConversion?

    /** 入力途中の読み全体に対するMozc予測を返す。変換候補と予測候補を分けるため別sessionで呼ぶ。 */
    fun suggest(sessionId: Long, reading: String): List<PredictionCandidate>

    /** 確定済みの周辺文字列から、次の語のゼロクエリ予測を返す。 */
    fun requestNextWordPrediction(sessionId: Long, precedingText: String): List<PredictionCandidate>

    /**
     * 予測sessionで直前に返した予測候補を確定し、エンジンの履歴へ選択を学習させる。
     * 明示変換の候補確定はcommitAllとlearnSegmentsで行うため、予測の選択だけに使う。
     */
    fun commitPrediction(sessionId: Long, candidateId: Int): Boolean

    /** 直前の変換の全文節を第一候補のまま確定し、エンジンへ学習させる。 */
    fun commitAll(sessionId: Long): Boolean

    /**
     * ライブ変換用に、読みから変換して全文節とそれぞれの候補を返す。確定はせず、学習もさせない。
     * 読みの連結が入力と一致しない場合も含め、結果の照合は呼び出し側が行う。失敗時はnull。
     */
    fun convertSegments(sessionId: Long, reading: String): List<EngineSegment>?

    /**
     * ライブ変換の一segmentを、前後の読みを文脈にして必ず一文節として変換し、その文節の表記と候補を返す。
     * 候補は明示変換と同じく、各候補を一時的に選んで文節の読みが変わらないものだけを集める。確定はせず、学習もさせない。
     * 読みを一文節に合わせられなければnull。
     */
    fun convertSegment(sessionId: Long, preceding: String, reading: String, following: String): EngineSegment?

    /**
     * 読みを変換し、segmentsと同じ区切りと表記へ合わせてから全体を確定し、エンジンへ学習させる。
     * 区切りか表記を合わせられなければ確定せずに取り消し、falseを返す。
     */
    fun learnSegments(sessionId: Long, segments: List<LearnedSegment>): Boolean

    /** エンジンが確定から学習した内容（文節履歴と予測の履歴）をすべて消す。 */
    fun clearLearning(): Boolean
}
