package dev.uzumi.ime.conversion

import dev.uzumi.ime.editor.GraphemeClusters
import java.io.File
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand

/**
 * Mozc公式JNIの4つのmethodを包む境界。Android上ではJNIを呼び、JVMテストでは偽の実装へ差し替える。
 */
interface MozcNativeBridge {
    /** libmozc.soを読み込みnative methodを登録する。libraryが無ければfalseを返す。 */
    fun loadLibrary(): Boolean

    /** user profileと辞書fileの場所を渡してSessionHandlerを作る。 */
    fun onPostLoad(profileDirectory: String, dataFile: String): Boolean

    /** 直列化したCommandを評価し、出力を含むCommandを返す。 */
    fun evalCommand(command: ByteArray): ByteArray

    /** 読み込んだ辞書のversionを返す。 */
    fun dataVersion(): String
}

/**
 * Mozcのprotocol（commands.proto）で、明示変換に必要な最小の命令列を組み立てる。
 * 読みはIME側が所有し、変換のたびにエンジン側のcompositionを読みから作り直す。
 */
class MozcConversionEngine(
    private val native: MozcNativeBridge,
    // 学習履歴などMozcが書き込む場所。バックアップ対象外の領域を渡す。
    private val profileDirectory: () -> File,
    // 辞書fileを用意して返す。APKに辞書が無ければnullを返す。
    private val dataFile: () -> File?,
) : ConversionEngine {
    // 直前の応答にpreeditが残っていたsession。worker threadだけが読み書きする。
    private val sessionsWithPreedit = mutableSetOf<Long>()

    override fun load(): EngineHealth {
        if (!native.loadLibrary()) {
            return EngineHealth.Unavailable(EngineUnavailableReason.NATIVE_LIBRARY_MISSING)
        }
        val profile = profileDirectory().apply { mkdirs() }
        // 辞書が無い場合も存在しない場所を渡し、nativeのminimal engine fallbackをversionで検出する。
        val data = dataFile() ?: File(profile, MISSING_DATA_FILE_NAME)
        if (!native.onPostLoad(profile.absolutePath, data.absolutePath)) {
            return EngineHealth.Unavailable(EngineUnavailableReason.INITIALIZATION_FAILED)
        }
        val version = native.dataVersion()
        if (version.isBlank() || version == MINIMAL_ENGINE_DATA_VERSION) {
            return EngineHealth.Unavailable(EngineUnavailableReason.MINIMAL_ENGINE)
        }
        return EngineHealth.Ready(version)
    }

    override fun createSession(): Long? {
        val output = eval(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION)) ?: return null
        return output.id.takeIf { output.hasId() }
    }

    override fun deleteSession(sessionId: Long) {
        sessionsWithPreedit -= sessionId
        eval(Input.newBuilder().setType(Input.CommandType.DELETE_SESSION).setId(sessionId))
    }

    override fun setIncognito(incognito: Boolean): Boolean {
        val input = Input.newBuilder()
            .setType(Input.CommandType.SET_REQUEST)
            .setRequest(Request.newBuilder().setIsIncognitoMode(incognito))
        return eval(input) != null
    }

    override fun convert(sessionId: Long, reading: String): EngineConversion? {
        if (reading.isEmpty()) return null
        // REVERTは変換中なら変換の取り消しだが、確定後の待機状態では直前の確定の学習を取り消すため、
        // preeditが残っている場合だけ送る。
        if (sessionId in sessionsWithPreedit) {
            sendCommand(sessionId, SessionCommand.CommandType.REVERT) ?: return null
        }
        for (character in GraphemeClusters.split(reading)) {
            // AS_ISで読みを変えずにcompositionへ積み、ローマ字表などによる書き換えを避ける。
            val key = KeyEvent.newBuilder()
                .setKeyString(character)
                .setInputStyle(KeyEvent.InputStyle.AS_IS)
            val output = sendKey(sessionId, key) ?: return null
            if (!output.consumed) return null
        }
        val output = sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.SPACE))
            ?: return null
        return toConversion(sessionId, output)
    }

    override fun commitCandidate(sessionId: Long, candidateId: Int): Boolean {
        val output = sendCommand(sessionId, SessionCommand.CommandType.SUBMIT_CANDIDATE, candidateId)
        return output?.consumed == true
    }

    override fun commitAll(sessionId: Long): Boolean {
        return sendCommand(sessionId, SessionCommand.CommandType.SUBMIT)?.consumed == true
    }

    /**
     * 変換状態のOutputから文節と先頭文節の候補を取り出す。変換状態でなければnullを返す。
     * 候補には複数の文節をまとめて置き換えるもの（全文候補など）が混ざり、候補の情報だけでは
     * 覆う読みを確実に判別できない。そこで各候補を一時的に選び、preeditの先頭文節の読みを
     * その候補が確定する読みとして記録し、最後に元の候補へ選択を戻す。
     */
    private fun toConversion(sessionId: Long, output: Output): EngineConversion? {
        if (!output.hasPreedit() || !output.hasAllCandidateWords()) return null
        val segments = output.preedit.segmentList.map { ConversionSegment(reading = it.key, value = it.value) }
        val words = output.allCandidateWords.candidatesList
            .filter { it.hasValue() && it.value.isNotEmpty() }
        val focusedId = output.allCandidateWords.candidatesList
            .getOrNull(output.allCandidateWords.focusedIndex)
            ?.id
        val candidates = words.mapNotNull { word ->
            val selected = sendCommand(sessionId, SessionCommand.CommandType.SELECT_CANDIDATE, word.id)
            val headReading = selected?.preedit?.segmentList?.firstOrNull()?.key
            headReading?.let { ConversionCandidate(id = word.id, value = word.value, reading = it) }
        }.distinctBy { it.value to it.reading }
        if (focusedId != null) {
            sendCommand(sessionId, SessionCommand.CommandType.SELECT_CANDIDATE, focusedId) ?: return null
        }
        return EngineConversion(segments = segments, headCandidates = candidates)
    }

    /** 指定sessionへキー入力を一つ送る。 */
    private fun sendKey(sessionId: Long, key: KeyEvent.Builder): Output? {
        return eval(
            Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(key),
        )?.also { recordPreedit(sessionId, it) }
    }

    /** 応答にpreeditがあるかを記録し、次の変換でREVERTを送るべきかの判断に使う。 */
    private fun recordPreedit(sessionId: Long, output: Output) {
        if (output.hasPreedit()) sessionsWithPreedit += sessionId else sessionsWithPreedit -= sessionId
    }

    /** 指定sessionへSessionCommandを送る。候補を扱う命令ではcandidateIdを付ける。 */
    private fun sendCommand(
        sessionId: Long,
        type: SessionCommand.CommandType,
        candidateId: Int? = null,
    ): Output? {
        val command = SessionCommand.newBuilder().setType(type)
        candidateId?.let(command::setId)
        return eval(
            Input.newBuilder()
                .setType(Input.CommandType.SEND_COMMAND)
                .setId(sessionId)
                .setCommand(command),
        )?.also { recordPreedit(sessionId, it) }
    }

    /** Commandを一つ評価し、失敗の応答や解析できない応答ならnullを返す。 */
    private fun eval(input: Input.Builder): Output? {
        val request = Command.newBuilder().setInput(input).build().toByteArray()
        val response = runCatching { Command.parseFrom(native.evalCommand(request)) }.getOrNull() ?: return null
        if (!response.hasOutput()) return null
        val output = response.output
        if (output.errorCode != Output.ErrorCode.SESSION_SUCCESS) return null
        return output
    }

    private companion object {
        /** Mozcのminimal engineが返すdata version。辞書を読み込めなかったことを表す。 */
        const val MINIMAL_ENGINE_DATA_VERSION = "0.0.0"

        /** APKに辞書が無い場合にnativeへ渡す、存在しないfileの名前。 */
        const val MISSING_DATA_FILE_NAME = "mozc-data-missing"
    }
}
