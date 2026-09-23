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

    /** 以後の変換で学習・履歴を使うかを切り替える。変換を送る前に呼ぶ。 */
    fun setIncognito(incognito: Boolean): Boolean

    /**
     * sessionの状態を読みだけから作り直して変換し、文節と先頭文節の候補を返す。
     * headLengthを渡すと、先頭文節の読みをその書記素数へ伸縮してから候補を集める（明示変換の文節の伸縮）。
     */
    fun convert(sessionId: Long, reading: String, headLength: Int? = null): EngineConversion?

    /** 直前の変換の先頭文節を指定候補で確定し、エンジンへ選択を学習させる。 */
    fun commitCandidate(sessionId: Long, candidateId: Int): Boolean

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
