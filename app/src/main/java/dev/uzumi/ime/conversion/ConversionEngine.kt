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

    /** sessionの状態を読みだけから作り直して変換し、文節と先頭文節の候補を返す。 */
    fun convert(sessionId: Long, reading: String): EngineConversion?

    /** 直前の変換の先頭文節を指定候補で確定し、エンジンへ選択を学習させる。 */
    fun commitCandidate(sessionId: Long, candidateId: Int): Boolean

    /** 直前の変換の全文節を第一候補のまま確定し、エンジンへ学習させる。 */
    fun commitAll(sessionId: Long): Boolean
}
