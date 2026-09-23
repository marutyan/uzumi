package dev.uzumi.ime.conversion

import dev.uzumi.ime.editor.GraphemeClusters
import java.io.File
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWord
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
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
 * Mozcのprotocol（commands.proto）で、明示変換とライブ変換に必要な最小の命令列を組み立てる。
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

    override fun convert(sessionId: Long, reading: String, headLength: Int?): EngineConversion? {
        var output = composeAndConvert(sessionId, reading, requestSuggestion = true) ?: return null
        if (headLength != null) {
            val head = GraphemeClusters.split(reading).take(headLength).joinToString(separator = "")
            output = alignFocusedSegment(sessionId, output, 0, head) ?: return null
        }
        return toConversion(sessionId, output)
    }

    override fun convertSegment(sessionId: Long, preceding: String, reading: String, following: String): EngineSegment? {
        if (reading.isEmpty()) return null
        var output = composeAndConvert(sessionId, preceding + reading + following, requestSuggestion = false)
            ?: return null
        if (!output.hasPreedit()) return null
        // 前の読みを文脈として一文節に合わせ、focusを対象の文節へ移す。
        val index = if (preceding.isEmpty()) 0 else 1
        if (preceding.isNotEmpty()) {
            output = alignFocusedSegment(sessionId, output, 0, preceding) ?: return null
            output = sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.RIGHT)) ?: return null
        }
        output = alignFocusedSegment(sessionId, output, index, reading) ?: return null
        val keys = output.preedit.segmentList.map { it.key }
        val value = output.preedit.getSegment(index).value
        val words = output.allCandidateWords.candidatesList
        val focusedId = words.getOrNull(output.allCandidateWords.focusedIndex)?.id
        // 明示変換と同じく各候補を一時的に選び、文節の区切りが変わらない（対象の読みだけを置き換える）候補だけを残す。
        val candidates = words.filter { it.hasValue() && it.value.isNotEmpty() }.mapNotNull { word ->
            val selected = sendCommand(sessionId, SessionCommand.CommandType.SELECT_CANDIDATE, word.id)
            word.value.takeIf { selected?.preedit?.segmentList?.map { it.key } == keys }
        }
        if (focusedId != null) {
            sendCommand(sessionId, SessionCommand.CommandType.SELECT_CANDIDATE, focusedId) ?: return null
        }
        return EngineSegment(reading = reading, value = value, candidates = (listOf(value) + candidates).distinct())
    }

    override fun convertSegments(sessionId: Long, reading: String): List<EngineSegment>? {
        var output = composeAndConvert(sessionId, reading, requestSuggestion = false) ?: return null
        if (!output.hasPreedit()) return null
        val preedit = output.preedit.segmentList
        // 文節ごとの候補。focusを右へ一つずつ動かし、強調された文節の候補一覧を読み取る。
        // focusの移動は文節を仮に固定するだけで確定しないため、学習は起きない。
        val candidates = arrayOfNulls<List<String>>(preedit.size)
        for (step in preedit.indices) {
            val focused = focusedSegmentIndex(output)
            if (focused !in preedit.indices || candidates[focused] != null) break
            candidates[focused] = segmentCandidates(preedit[focused].key, output)
            if (step == preedit.lastIndex) break
            output = sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.RIGHT)) ?: break
        }
        return preedit.mapIndexed { index, segment ->
            EngineSegment(
                reading = segment.key,
                value = segment.value,
                candidates = candidates[index] ?: listOf(segment.value),
            )
        }
    }

    override fun learnSegments(sessionId: Long, segments: List<LearnedSegment>): Boolean {
        if (segments.isEmpty() || segments.any { it.reading.isEmpty() || it.surface.isEmpty() }) return false
        val reading = segments.joinToString(separator = "") { it.reading }
        var output = composeAndConvert(sessionId, reading, requestSuggestion = false) ?: return false
        for ((index, target) in segments.withIndex()) {
            output = alignFocusedSegment(sessionId, output, index, target.reading) ?: return revertLearning(sessionId)
            val focused = output.preedit.getSegment(index)
            if (focused.value != target.surface) {
                val word = output.allCandidateWords.candidatesList.firstOrNull {
                    it.value == target.surface && replacesOnlySegment(it, target.reading)
                } ?: return revertLearning(sessionId)
                output = sendCommand(sessionId, SessionCommand.CommandType.SELECT_CANDIDATE, word.id)
                    ?: return revertLearning(sessionId)
            }
            if (index < segments.lastIndex) {
                output = sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.RIGHT))
                    ?: return revertLearning(sessionId)
            }
        }
        val finalSegments = output.preedit.segmentList
        if (finalSegments.map { it.key } != segments.map { it.reading } ||
            finalSegments.map { it.value } != segments.map { it.surface }
        ) {
            return revertLearning(sessionId)
        }
        return commitAll(sessionId)
    }

    /**
     * 読みの各文字をcompositionへ積み、SPACEで変換した応答を返す。
     * requestSuggestionがfalseなら入力途中の予測を求めず、ライブ変換の一回あたりの処理を減らす。
     */
    private fun composeAndConvert(sessionId: Long, reading: String, requestSuggestion: Boolean): Output? {
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
            val output = sendKey(sessionId, key, requestSuggestion) ?: return null
            if (!output.consumed) return null
        }
        return sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.SPACE))
    }

    /**
     * index番目の文節にfocusがある状態で、その文節の読みがtargetReadingになるまで幅を伸縮する。
     * 幅の変更はMozcの変換キー（Shift+右で伸ばす、Shift+左で縮める）で行う。合わせられなければnull。
     */
    private fun alignFocusedSegment(sessionId: Long, start: Output, index: Int, targetReading: String): Output? {
        var output = start
        val targetLength = targetReading.codePointCount(0, targetReading.length)
        // 伸縮は一回で一文字ずつ動くため、読み全体の長さを超えて繰り返す必要はない。
        var remainingSteps = output.preedit.segmentList.sumOf { it.key.codePointCount(0, it.key.length) }
        while (true) {
            if (focusedSegmentIndex(output) != index) return null
            val key = output.preedit.getSegment(index).key
            if (key == targetReading) return output
            val length = key.codePointCount(0, key.length)
            if (length == targetLength || remainingSteps-- <= 0) return null
            val direction = if (length < targetLength) KeyEvent.SpecialKey.RIGHT else KeyEvent.SpecialKey.LEFT
            output = sendKey(
                sessionId,
                KeyEvent.newBuilder().setSpecialKey(direction).addModifierKeys(KeyEvent.ModifierKey.SHIFT),
            ) ?: return null
        }
    }

    /** 学習用の変換を確定せずに取り消し、失敗を返す。 */
    private fun revertLearning(sessionId: Long): Boolean {
        if (sessionId in sessionsWithPreedit) sendCommand(sessionId, SessionCommand.CommandType.REVERT)
        return false
    }

    /** preeditのうち、Mozcがfocusを置いて強調している文節の位置。無ければ-1。 */
    private fun focusedSegmentIndex(output: Output): Int {
        return output.preedit.segmentList.indexOfFirst { it.annotation == Preedit.Segment.Annotation.HIGHLIGHT }
    }

    /** focus中の文節の候補一覧から、その文節の読みだけを置き換える候補の表記を取り出す。 */
    private fun segmentCandidates(segmentReading: String, output: Output): List<String> {
        return output.allCandidateWords.candidatesList
            .filter { it.hasValue() && it.value.isNotEmpty() && replacesOnlySegment(it, segmentReading) }
            .map { it.value }
            .distinct()
    }

    /**
     * 候補が文節の読みだけを置き換えるかを、候補の情報だけで安く判定する。
     * Mozcは候補の自立語部分の読み（content_key、「講演に」なら「こうえん」）が文節の読みと違うとkeyへ入れる
     * （engine/engine_output.ccのFillCandidateWord）。そのためkeyは普通の変換候補でも付き、文節の読みの接頭辞になる。
     * 接頭辞でないkeyは読みが入力と異なる候補（予測など）として除き、num_segments_in_candidateが2以上の
     * 複数の文節をまとめる候補も除く。どちらもsegment境界を壊すためである。
     */
    private fun replacesOnlySegment(word: CandidateWord, segmentReading: String): Boolean {
        val sameReading = !word.hasKey() || segmentReading.startsWith(word.key)
        return sameReading && word.numSegmentsInCandidate <= 1
    }

    /** 文節履歴（CLEAR_USER_HISTORY）と予測の履歴（CLEAR_USER_PREDICTION）を、Mozcのメモリと保存ファイルから消す。 */
    override fun clearLearning(): Boolean {
        val history = eval(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_HISTORY)) != null
        val prediction = eval(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_PREDICTION)) != null
        return history && prediction
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

    /** 指定sessionへキー入力を一つ送る。requestSuggestionがfalseなら入力途中の予測を求めない。 */
    private fun sendKey(sessionId: Long, key: KeyEvent.Builder, requestSuggestion: Boolean = true): Output? {
        val input = Input.newBuilder()
            .setType(Input.CommandType.SEND_KEY)
            .setId(sessionId)
            .setKey(key)
        if (!requestSuggestion) input.setRequestSuggestion(false)
        return eval(input)?.also { recordPreedit(sessionId, it) }
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
