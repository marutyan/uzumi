package dev.uzumi.ime.conversion

import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.editor.KanaModifier
import java.io.File
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWord
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context
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
    // SET_REQUESTは全sessionへ適用されるため、予測要求の一時設定を戻す際にincognitoを保つ。
    private var incognitoMode = false
    // 直前のsetIncognitoで決めた表記ゆれ変換の有無。予測の一時設定とその後の復元でも同じ値を使う。
    private var kanaModifierInsensitiveMode = false

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
        if (!enableKanaModifierInsensitiveConfig()) {
            return EngineHealth.Unavailable(EngineUnavailableReason.INITIALIZATION_FAILED)
        }
        return EngineHealth.Ready(version)
    }

    /**
     * 表記ゆれ変換は、Mozcの設定（Config）と要求（Request）の両方が有効なときだけ働く（論理積）。
     * 設定は保存されるため一度だけ有効にし、使うかどうかは要求の側で切り替える。
     */
    private fun enableKanaModifierInsensitiveConfig(): Boolean {
        val current = eval(Input.newBuilder().setType(Input.CommandType.GET_CONFIG)) ?: return false
        if (current.config.useKanaModifierInsensitiveConversion) return true
        val config = current.config.toBuilder().setUseKanaModifierInsensitiveConversion(true)
        return eval(Input.newBuilder().setType(Input.CommandType.SET_CONFIG).setConfig(config)) != null
    }

    override fun createSession(): Long? {
        val output = eval(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION)) ?: return null
        return output.id.takeIf { output.hasId() }
    }

    override fun deleteSession(sessionId: Long) {
        sessionsWithPreedit -= sessionId
        eval(Input.newBuilder().setType(Input.CommandType.DELETE_SESSION).setId(sessionId))
    }

    /**
     * 学習の有無と表記ゆれ変換の有無だけの要求へ戻し、予測用requestの全sessionへの残留を防ぐ。
     * 表記ゆれ変換の有無は次のsetIncognitoまでの変換・予測・復元で同じ値を使う。
     */
    override fun setIncognito(incognito: Boolean, kanaModifierInsensitive: Boolean): Boolean {
        if (!setRequest(request(incognito, kanaModifierInsensitive))) return false
        incognitoMode = incognito
        kanaModifierInsensitiveMode = kanaModifierInsensitive
        return true
    }

    /**
     * 明示変換する。segmentLengthsがあれば先頭から順に各文節の幅を合わせ、利用者が伸縮した区切りを再現する。
     * 最後に各文節へfocusを移して、その文節の読みだけを置き換える候補を集める。
     */
    override fun convert(sessionId: Long, reading: String, segmentLengths: List<Int>?): EngineConversion? {
        var output = composeAndConvert(sessionId, reading, requestSuggestion = true) ?: return null
        if (segmentLengths != null) output = alignSegments(sessionId, output, reading, segmentLengths) ?: return null
        if (!output.hasPreedit()) return null
        val candidates = collectSegmentCandidates(sessionId, output) ?: return null
        val segments = output.preedit.segmentList.mapIndexed { index, segment ->
            ConversionSegment(reading = segment.key, value = segment.value, candidates = candidates[index])
        }
        return EngineConversion(segments = segments, headCandidates = segments.first().candidates)
    }

    /**
     * 変換直後（先頭文節にfocus）の状態から、先頭からi番目の文節の読みをlengths[i]書記素へ順に合わせる。
     * Mozcは伸縮した文節より後ろだけを区切り直すため、前の文節から順に合わせれば先に合わせた区切りは保たれる。
     */
    private fun alignSegments(sessionId: Long, start: Output, reading: String, lengths: List<Int>): Output? {
        val clusters = GraphemeClusters.split(reading)
        var output = start
        var offset = 0
        for ((index, length) in lengths.withIndex()) {
            if (length < 1 || offset + length > clusters.size) return null
            if (index > 0) {
                output = sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.RIGHT)) ?: return null
            }
            val target = clusters.subList(offset, offset + length).joinToString(separator = "")
            output = alignFocusedSegment(sessionId, output, index, target) ?: return null
            offset += length
        }
        return output
    }

    /**
     * 読みを入力し、入力した読みを補う予測だけを候補IDとともに返す。予測の計算は重いため、
     * 途中のキーではsuggestionを求めず、最後のキーでだけ求める。
     */
    override fun suggest(sessionId: Long, reading: String): List<PredictionCandidate> {
        if (reading.isEmpty()) return emptyList()
        if (sessionId in sessionsWithPreedit) {
            sendCommand(sessionId, SessionCommand.CommandType.REVERT) ?: return emptyList()
        }
        if (!setRequest(request(incognitoMode, kanaModifierInsensitiveMode, prediction = true, zeroQuery = false))) {
            return emptyList()
        }
        val candidates = typeForSuggestion(sessionId, reading)?.let { predictionCandidates(it, reading) }.orEmpty()
        return candidates.takeIf { restoreRequest() }.orEmpty()
    }

    /**
     * 予測用のrequestを設定した後に、読みを一文字ずつcompositionへ積み、最後のキーの応答（候補窓に予測を含む）を返す。
     * 予測の計算は重いため、途中のキーではsuggestionを求めない。キーを受け付けなければnull。
     */
    private fun typeForSuggestion(sessionId: Long, reading: String): Output? {
        var output: Output? = null
        val characters = GraphemeClusters.split(reading)
        for ((index, character) in characters.withIndex()) {
            val key = KeyEvent.newBuilder()
                .setKeyString(character)
                .setInputStyle(KeyEvent.InputStyle.AS_IS)
            output = sendKey(sessionId, key, requestSuggestion = index == characters.lastIndex)
            if (output == null || !output.consumed) return null
        }
        return output
    }

    /** 直前の確定文字をcontextに設定し、Mozcの専用NWP命令で次の語を求める。 */
    override fun requestNextWordPrediction(sessionId: Long, precedingText: String): List<PredictionCandidate> {
        if (precedingText.isBlank()) return emptyList()
        if (sessionId in sessionsWithPreedit) {
            sendCommand(sessionId, SessionCommand.CommandType.REVERT) ?: return emptyList()
        }
        if (!setRequest(request(incognitoMode, kanaModifierInsensitiveMode, prediction = true, zeroQuery = true))) {
            return emptyList()
        }
        val output = sendCommand(
            sessionId = sessionId,
            type = SessionCommand.CommandType.REQUEST_NWP,
            context = Context.newBuilder().setPrecedingText(precedingText),
            requestSuggestion = true,
        )
        val candidates = output?.let { predictionCandidates(it, reading = null) }.orEmpty()
        return candidates.takeIf { restoreRequest() }.orEmpty()
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

    /** Mozcの候補窓から、入力途中の予測として出せる候補を最大50件先読みする。readingがnullなら次の語の予測。 */
    private fun predictionCandidates(output: Output, reading: String?): List<PredictionCandidate> {
        if (!output.hasAllCandidateWords()) return emptyList()
        return output.allCandidateWords.candidatesList
            .asSequence()
            .filter { it.hasId() && it.hasValue() && it.value.isNotBlank() }
            .mapNotNull { word ->
                if (reading == null) PredictionCandidate(word.id, word.value, word.key.takeIf { word.hasKey() })
                else readingPrediction(word, reading)
            }
            .distinctBy { it.value }
            .take(MAX_PREDICTIONS)
            .toList()
    }

    /**
     * 入力途中の候補を残すか決める。keyが入力読みより長ければ続きを含む候補、短ければ選択後のMozc応答を確認する候補として残す。
     * Mozcのkeyは候補のcontent_keyで、部分消費を単独では示さないため、実際に残る読みはSUBMIT_CANDIDATEの応答で決める。
     * keyが無い候補は入力全体の変換結果として扱う。表記ゆれ変換中は小書き・濁点・半濁点の違いを除いて比べる。
     * 打ったとおりのひらがなは候補にしない。
     */
    private fun readingPrediction(word: CandidateWord, reading: String): PredictionCandidate? {
        if (word.value == reading) return null
        if (!word.hasKey()) return PredictionCandidate(word.id, word.value, reading)
        val key = comparableReading(word.key)
        val typed = comparableReading(reading)
        return when {
            key.startsWith(typed) -> PredictionCandidate(word.id, word.value, word.key)
            typed.startsWith(key) -> {
                PredictionCandidate(word.id, word.value, partialKey = word.key)
            }
            else -> null
        }
    }

    /** 読み同士を比べる形。表記ゆれ変換が有効な間だけ、小書き・濁点・半濁点の違いを除く。 */
    private fun comparableReading(reading: String): String =
        if (kanaModifierInsensitiveMode) KanaModifier.stripModifiers(reading) else reading

    /**
     * Uzumiが送るRequestを1か所で組み立てる。通常は学習の有無と表記ゆれ変換の有無だけを指定し、
     * 予測の間だけAndroid向けの混合候補（mixed_conversion）、部分予測、ゼロクエリの取得方式を加える。
     */
    private fun request(
        incognito: Boolean,
        kanaModifierInsensitive: Boolean,
        prediction: Boolean = false,
        zeroQuery: Boolean = false,
    ): Request {
        val builder = Request.newBuilder()
            .setIsIncognitoMode(incognito)
            .setKanaModifierInsensitiveConversion(kanaModifierInsensitive)
        if (prediction) {
            builder.setZeroQuerySuggestion(zeroQuery).setMixedConversion(true)
            builder.setAutoPartialSuggestion(true)
        }
        return builder.build()
    }

    /** 予測の一時設定を、直前のsetIncognitoと同じ通常の要求へ戻す。 */
    private fun restoreRequest(): Boolean = setRequest(request(incognitoMode, kanaModifierInsensitiveMode))

    /** SET_REQUESTでMozc全sessionのrequest設定を差し替える。予測設定は直後に通常設定へ戻す。 */
    private fun setRequest(request: Request): Boolean = eval(
        Input.newBuilder()
            .setType(Input.CommandType.SET_REQUEST)
            .setRequest(request),
    ) != null

    /**
     * focusを先頭文節へ戻してから右へ一つずつ動かし、各文節の読みだけを置き換える候補を集める。
     * focusの移動は文節を仮に固定するだけで確定しないため、学習は起きない。候補が無い文節は表示中の表記だけにする。
     */
    private fun collectSegmentCandidates(sessionId: Long, start: Output): List<List<ConversionCandidate>>? {
        val segmentCount = start.preedit.segmentCount
        var output = start
        // 戻す回数は文節数を上限にし、Mozcがfocusを動かさない場合も止まるようにする。
        repeat(segmentCount) {
            if (focusedSegmentIndex(output) <= 0) return@repeat
            output = sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.LEFT)) ?: return null
        }
        val candidates = mutableListOf<List<ConversionCandidate>>()
        for (index in 0 until segmentCount) {
            if (index > 0) {
                output = sendKey(sessionId, KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.RIGHT)) ?: return null
            }
            if (focusedSegmentIndex(output) != index) return null
            val segment = output.preedit.getSegment(index)
            val words = output.allCandidateWords.candidatesList
                .filter { it.hasValue() && it.value.isNotEmpty() && replacesOnlySegment(it, segment.key) }
                .map { ConversionCandidate(id = it.id, value = it.value, reading = segment.key) }
                .distinctBy { it.value }
            candidates += words.ifEmpty { listOf(ConversionCandidate(ConversionCandidate.DISPLAYED_VALUE_ID, segment.value, segment.key)) }
        }
        return candidates
    }

    /**
     * 候補が文節の読みだけを置き換えるかを、候補の情報だけで安く判定する。
     * Mozcは候補の自立語部分の読み（content_key、「講演に」なら「こうえん」）が文節の読みと違うとkeyへ入れる
     * （engine/engine_output.ccのFillCandidateWord）。そのためkeyは普通の変換候補でも付き、文節の読みの接頭辞になる。
     * 接頭辞でないkeyは読みが入力と異なる候補（予測など）として除き、num_segments_in_candidateが2以上の
     * 複数の文節をまとめる候補も除く。どちらもsegment境界を壊すためである。
     */
    private fun replacesOnlySegment(word: CandidateWord, segmentReading: String): Boolean {
        // 表記ゆれ変換の候補（入力「りようかいし」に対するkey「りょうかい」）も、文節の読みを置き換える候補として扱う。
        val sameReading = !word.hasKey() || comparableReading(segmentReading).startsWith(comparableReading(word.key))
        return sameReading && word.numSegmentsInCandidate <= 1
    }

    /** 文節履歴（CLEAR_USER_HISTORY）と予測の履歴（CLEAR_USER_PREDICTION）を、Mozcのメモリと保存ファイルから消す。 */
    override fun clearLearning(): Boolean {
        val history = eval(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_HISTORY)) != null
        val prediction = eval(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_PREDICTION)) != null
        return history && prediction
    }

    /**
     * 読みと表記の組をMozcの履歴（文節履歴と予測の履歴）から消す。読み全体を一文節にした変換で表記が第一候補なら、
     * その候補IDでDELETE_CANDIDATE_FROM_HISTORYを送る。第一候補でなければ、同じ読みの入力途中の予測で同じ判定をする。
     * 第一候補に限るのは、Mozcの文節履歴の削除がIDの候補ではなく文節の第一候補の表記を消すためである
     * （rewriter/user_segment_history_rewriter.ccのClearHistoryEntry）。ほかの語の学習を消さないよう、それ以外では送らない。
     * 確定はしないため新たな学習は起きない。送ったかを返す（Mozcは消せたかを応答で区別しない）。
     */
    override fun deleteFromHistory(sessionId: Long, reading: String, surface: String): Boolean {
        if (reading.isEmpty() || surface.isEmpty()) return false
        val deleted = deleteTopConversionCandidate(sessionId, reading, surface) ||
            deleteTopSuggestion(sessionId, reading, surface)
        if (sessionId in sessionsWithPreedit) sendCommand(sessionId, SessionCommand.CommandType.REVERT)
        return deleted
    }

    /** 読み全体を一文節に合わせて変換し、表記が第一候補なら履歴から消す。 */
    private fun deleteTopConversionCandidate(sessionId: Long, reading: String, surface: String): Boolean {
        val converted = composeAndConvert(sessionId, reading, requestSuggestion = false) ?: return false
        if (!converted.hasPreedit()) return false
        val output = alignFocusedSegment(sessionId, converted, 0, reading) ?: return false
        return deleteIfTopCandidate(sessionId, output, surface)
    }

    /** 読みを予測用のrequestで入力し、予測の第一候補が表記と同じなら履歴から消す。requestは通常の設定へ戻す。 */
    private fun deleteTopSuggestion(sessionId: Long, reading: String, surface: String): Boolean {
        if (sessionId in sessionsWithPreedit) {
            sendCommand(sessionId, SessionCommand.CommandType.REVERT) ?: return false
        }
        if (!setRequest(request(incognitoMode, kanaModifierInsensitiveMode, prediction = true, zeroQuery = false))) {
            return false
        }
        val deleted = typeForSuggestion(sessionId, reading)?.let { deleteIfTopCandidate(sessionId, it, surface) } == true
        return restoreRequest() && deleted
    }

    /** 候補窓の先頭（文節の第一候補）が表記と同じ場合だけ、そのIDで履歴からの削除を送る。 */
    private fun deleteIfTopCandidate(sessionId: Long, output: Output, surface: String): Boolean {
        val top = output.allCandidateWords.candidatesList.firstOrNull() ?: return false
        if (!top.hasId() || top.value != surface) return false
        return sendCommand(sessionId, SessionCommand.CommandType.DELETE_CANDIDATE_FROM_HISTORY, top.id) != null
    }

    override fun commitAll(sessionId: Long): Boolean {
        return sendCommand(sessionId, SessionCommand.CommandType.SUBMIT)?.consumed == true
    }

    /** 予測sessionの候補窓にある予測候補をSUBMIT_CANDIDATEで確定し、Mozcの履歴へ学習させる。 */
    override fun commitPrediction(sessionId: Long, candidateId: Int): Boolean {
        return submitPrediction(sessionId, candidateId) != null
    }

    /** SUBMIT_CANDIDATEの結果から、Mozcが実際に残した未変換読みを返す。 */
    override fun submitPrediction(sessionId: Long, candidateId: Int): EnginePredictionCommit? {
        val output = sendCommand(sessionId, SessionCommand.CommandType.SUBMIT_CANDIDATE, candidateId)
            ?.takeIf { it.consumed }
            ?: return null
        val result = output.result.takeIf { output.hasResult() && it.hasKey() } ?: return null
        val remaining = if (output.hasPreedit()) {
            output.preedit.segmentList.joinToString(separator = "") { segment ->
                if (segment.hasKey()) segment.key else segment.value
            }
        } else {
            ""
        }
        return EnginePredictionCommit(
            remainingReading = remaining,
            resultReading = result.key,
        )
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
        context: Context.Builder? = null,
        requestSuggestion: Boolean? = null,
    ): Output? {
        val command = SessionCommand.newBuilder().setType(type)
        candidateId?.let(command::setId)
        val input = Input.newBuilder()
            .setType(Input.CommandType.SEND_COMMAND)
            .setId(sessionId)
            .setCommand(command)
        context?.let(input::setContext)
        requestSuggestion?.let(input::setRequestSuggestion)
        return eval(input)?.also { recordPreedit(sessionId, it) }
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

        /** 表示中・通常候補との重複を除いた後も候補を揃えられるようMozcから先読みする件数。 */
        private const val MAX_PREDICTIONS = 50
    }
}
