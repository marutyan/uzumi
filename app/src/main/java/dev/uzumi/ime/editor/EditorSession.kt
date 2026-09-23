package dev.uzumi.ime.editor

import dev.uzumi.ime.conversion.ConversionClient
import dev.uzumi.ime.conversion.ConversionOutcome
import dev.uzumi.ime.conversion.ConversionRequest
import dev.uzumi.ime.conversion.ConversionResult
import dev.uzumi.ime.conversion.LiveConversionClient
import dev.uzumi.ime.conversion.SegmentedLiveConverter
import dev.uzumi.ime.conversion.liveLearningUnits
import dev.uzumi.ime.live.CandidateChoice
import dev.uzumi.ime.live.DisplaySpan
import dev.uzumi.ime.live.EditorCommand
import dev.uzumi.ime.live.EnterKind
import dev.uzumi.ime.live.LiveConversionCore
import dev.uzumi.ime.live.LiveConversionRules
import dev.uzumi.ime.live.LiveFieldPolicy
import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.LiveUpdate
import dev.uzumi.ime.live.ResultSegment
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import dev.uzumi.ime.live.ConversionResult as LiveResult

/**
 * 一つのEditorInfoとInputConnectionに結び付いた編集セッションを管理する。
 * 切替後の接続へcompositionや遅延イベントを送らない境界として機能する。
 * liveCoreを渡すとライブ変換で動き、読みとsegmentはコアが持つ。コアの書込み命令は
 * applyLiveUpdateだけがInputConnectionへ送る。渡さなければ従来の明示変換で動く。
 */
class EditorSession(
    private val connection: EditorConnectionPort,
    val policy: InputFieldPolicy,
    initialSelectionStart: Int = 0,
    initialSelectionEnd: Int = initialSelectionStart,
    // フィールドや接続ごとに異なる番号。変換応答を別のフィールドへ適用しないために要求へ含める。
    val sessionEpoch: Long = 0,
    // 変換エンジンへの非同期窓口。nullまたは利用不可なら、かな・カナ候補だけで動く。
    private val conversionClient: ConversionClient? = null,
    // ライブ変換の状態機械。nullなら明示変換で動く。この編集セッション専用で、共有しない。
    private val liveCore: LiveConversionCore? = null,
    // ライブ変換の要求と確定時の学習の送り先。nullまたは利用不可なら、かな・カナ候補だけで動く。
    private val liveClient: LiveConversionClient? = null,
    // composition表示へ訂正中のsegmentの強調を付ける。JVMテストでは文字列をそのまま返す既定値を使う。
    private val compositionStyler: (String, DisplaySpan?) -> CharSequence = { text, _ -> text },
) {
    private val buffer = CompositionBuffer()
    // compositionの内容が変わるたびに増える番号。変換応答が現在の読みに対するものかを照合する。
    private var revision = 0L
    private var pendingConversion: ConversionRequest? = null
    private var appliedConversion: ConversionResult? = null
    // 変換が失敗・時間超過したrevision。同じ読みでの再度の変換操作はカナ候補へ戻す。
    private var failedConversionRevision: Long? = null
    private var active = true
    private var selectionStart: Int? = if (initialSelectionStart >= 0 && initialSelectionEnd >= 0) {
        minOf(initialSelectionStart, initialSelectionEnd)
    } else {
        null
    }
    private var selectionEnd: Int? = if (initialSelectionStart >= 0 && initialSelectionEnd >= 0) {
        maxOf(initialSelectionStart, initialSelectionEnd)
    } else {
        null
    }
    private var compositionStart: Int? = null
    private var expectedComposition: CompositionGeometry? = null
    private val staleCompositions = mutableListOf<CompositionGeometry>()
    // 確定操作の遅延通知を、後から始まったcompositionと区別するために保持する。
    private val pendingCommittedSelections = mutableListOf<SelectionTransition>()

    // ライブ変換で最後にEditorへ送ったカーソル位置（composition先頭からのUTF-16 offset）。
    private var liveCursorUtf16 = 0

    // 変換エンジンが使えない間のライブ変換の結果。部分範囲ごとに読みとカタカナを候補にする。
    private val kanaFallbackConverter = SegmentedLiveConverter(
        convertRange = { chunk ->
            listOf(ResultSegment(chunk, chunk, listOf(chunk, BasicCandidateProvider.toKatakana(chunk)).distinct()))
        },
    )

    init {
        liveCore?.startField(
            LiveFieldPolicy(
                conversionAllowed = !policy.suppressSuggestions,
                learningAllowed = !policy.suppressLearning,
            ),
        )
    }

    /** セッションがまだ編集を受け付けるかを返す。 */
    val isActive: Boolean
        get() = active

    /** compositionの現在状態をUIや候補行へ返す。 */
    fun compositionSnapshot(): CompositionSnapshot = buffer.snapshot()

    /** ライブ変換で動いているか。候補バーの表示を切り替えるために使う。 */
    val isLiveMode: Boolean
        get() = liveCore != null

    /**
     * 未確定の読み（ライブ変換では変換中の表示）があるか。キーボードが「カナ」「変換」へ切り替えるために使う。
     */
    val hasComposition: Boolean
        get() = active && (liveCore?.display?.isNotEmpty() ?: !buffer.isEmpty)

    /** 応答を待っている変換要求。UIが時間超過を判定するために使う。 */
    val pendingConversionRequest: ConversionRequest?
        get() = pendingConversion

    /** 変換結果を表示中なら変換候補、そうでなければ現在の読みから基本候補を作る。 */
    fun candidateOptions(): List<CandidateOption> {
        if (liveCore != null) return emptyList()
        currentConversion()?.let { conversion ->
            return conversion.headCandidates.map { candidate ->
                CandidateOption(
                    value = candidate.value,
                    label = "",
                    conversionChoice = ConversionChoice(conversion.request, candidate.id),
                )
            }
        }
        return BasicCandidateProvider.candidates(buffer.reading, policy.suppressSuggestions)
    }

    /**
     * 文字入力をcompositionへ追加する。接続結果が不明なら同じ本文を再送しない。
     */
    fun inputText(value: String): Boolean {
        if (!active || value.isEmpty()) return false

        if (policy.isTypeNull) {
            val accepted = connection.commitText(value, 1)
            if (accepted) updateExternalSelectionAfterCommit(value)
            return accepted
        }
        if (liveCore != null) return performLive { it.inputText(value) } ?: false

        // 変換結果の表示中に続けて入力した場合は、表示中の変換を確定してから新しい読みを始める。
        if (currentConversion() != null && !commitComposition()) return false
        if (compositionStart == null && selectionStart != null && selectionEnd != null) {
            compositionStart = minOf(selectionStart!!, selectionEnd!!)
        }
        markCompositionChanged()
        buffer.insert(value)
        if (synchronizeComposition()) return true
        failClosed()
        return false
    }

    /**
     * 読み末尾または外部カーソル直前の書記素を削除する。
     */
    fun deleteBackward(): Boolean {
        if (!active) return false
        liveCore?.let { core ->
            // composition先頭での削除は、明示変換と同じく何もしない（Editor側の位置の追跡を崩さないため）。
            if (core.display.isNotEmpty()) return performLive { it.deleteBackward() } ?: false
            return deleteOutsideComposition()
        }
        if (!buffer.isEmpty) {
            if (!buffer.deleteBackward()) return false
            markCompositionChanged()
            if (synchronizeComposition()) return true
            failClosed()
            return false
        }
        return deleteOutsideComposition()
    }

    /** compositionが無いときの削除。選択範囲、またはカーソル直前の一書記素を消す。 */
    private fun deleteOutsideComposition(): Boolean {
        if (selectionStart != null && selectionEnd != null && selectionStart != selectionEnd) {
            val accepted = connection.commitText("", 1)
            if (accepted) selectionEnd = selectionStart
            return accepted
        }

        val beforeCursor = connection.textBeforeCursor(64)?.toString() ?: return false
        val deleteLength = GraphemeClusters.previousUtf16Length(beforeCursor)
        if (deleteLength == 0) return false
        return connection.deleteSurroundingText(deleteLength, 0)
    }

    /**
     * composition内または外部テキスト上で書記素単位にカーソルを動かす。
     */
    fun moveCursor(delta: Int): Boolean {
        if (!active || delta == 0) return false
        liveCore?.let { core ->
            if (core.display.isEmpty()) return moveOutsideComposition(delta)
            val length = core.segments.lastOrNull()?.readingEnd ?: 0
            val target = (core.inputCursor + delta).coerceIn(0, length)
            if (target == core.inputCursor) return false
            return performLive { it.moveCursorTo(target) } ?: false
        }
        if (!buffer.isEmpty) {
            val before = buffer.snapshot()
            if (!buffer.moveCursor(delta)) return false
            markCompositionChanged()
            if (before.display != before.reading) {
                if (synchronizeComposition()) return true
                failClosed()
                return false
            }

            val start = compositionStart ?: run {
                failClosed()
                return false
            }
            val target = start + buffer.cursorUtf16Offset()
            if (connection.setSelection(target, target)) {
                selectionStart = target
                selectionEnd = target
                recordExpectedComposition(
                    CompositionGeometry(
                        start = start,
                        displayLength = buffer.display.length,
                        cursorOffset = target - start,
                    ),
                )
                return true
            }
            failClosed()
            return false
        }
        return moveOutsideComposition(delta)
    }

    /** compositionが無いときのカーソル移動。周辺文字列の書記素幅だけ外部selectionを動かす。 */
    private fun moveOutsideComposition(delta: Int): Boolean {
        val context = if (delta < 0) {
            connection.textBeforeCursor(64)?.toString() ?: return false
        } else {
            connection.textAfterCursor(64)?.toString() ?: return false
        }
        val clusters = GraphemeClusters.split(context)
        val count = kotlin.math.abs(delta)
        if (clusters.size < count) return false
        val width = if (delta < 0) {
            clusters.takeLast(count).sumOf(String::length)
        } else {
            clusters.take(count).sumOf(String::length)
        }
        val currentSelectionEnd = selectionEnd ?: return false
        val target = currentSelectionEnd + if (delta < 0) -width else width
        if (target < 0) return false
        val accepted = connection.setSelection(target, target)
        if (accepted) {
            selectionStart = target
            selectionEnd = target
        }
        return accepted
    }

    /**
     * 読みを確定し、エディタ側のcompositionを消す。
     */
    fun commitComposition(): Boolean {
        if (!active) return false
        liveCore?.let { core ->
            if (core.display.isEmpty()) return true
            return performLive { it.commitComposition() } ?: false
        }
        if (buffer.isEmpty) return true
        val conversion = currentConversion()
        if (!commitCompositionAs(buffer.display)) return false
        // 登録語を表示していた場合、エンジン側の変換とは表記が違うため、確定をエンジンへ学習させない。
        conversion?.takeIf { result -> result.segments.none { it.fromUserDictionary } }
            ?.let { conversionClient?.commitAll(it.request) }
        return true
    }

    /**
     * 表示中の変換候補を選び、先頭文節をその候補で確定する。
     * 残りの読みは新しいcompositionとして続けて変換を依頼する。表示元の要求が古ければ拒否する。
     */
    fun selectConversionCandidate(choice: ConversionChoice): Boolean {
        if (!active) return false
        val conversion = currentConversion() ?: return false
        if (conversion.request != choice.request) return false
        val candidate = conversion.headCandidates.firstOrNull { it.id == choice.candidateId } ?: return false
        // isConsistentにより、候補が確定する読みは現在の読みの接頭辞であることが保証されている。
        // 複数の文節をまとめる候補では、その候補が覆う文節すべてが確定される。
        val remainingReading = buffer.reading.substring(candidate.reading.length)
        if (!commitCompositionAs(candidate.value)) return false
        // 登録語はエンジンの候補ではないため、エンジンへ確定を通知しない。
        if (!candidate.fromUserDictionary) conversionClient?.commitCandidate(conversion.request, candidate.id)
        if (remainingReading.isEmpty()) return true
        if (!inputText(remainingReading)) return false
        return convert()
    }

    /**
     * workerから届いた変換応答を、待機中の要求・revision・読みと一致する場合だけ反映する。
     * 失敗応答や不整合な結果では読みの表示を保ち、かな・カナ候補へ戻す。
     */
    fun applyConversionOutcome(outcome: ConversionOutcome): Boolean {
        val pending = pendingConversion ?: return false
        if (!active || outcome.request != pending) return false
        pendingConversion = null
        if (pending.revision != revision || pending.reading != buffer.reading) return false
        val result = (outcome as? ConversionOutcome.Converted)?.result?.takeIf(ConversionResult::isConsistent)
        if (result == null) {
            failedConversionRevision = revision
            return false
        }
        buffer.setDisplay(result.display)
        if (!synchronizeComposition()) {
            failClosed()
            return false
        }
        appliedConversion = result
        return true
    }

    /** 時間超過した要求を取り下げ、遅れて届いた応答を適用しないようにする。 */
    fun abandonConversion(request: ConversionRequest): Boolean {
        if (pendingConversion != request) return false
        pendingConversion = null
        failedConversionRevision = revision
        return true
    }

    /**
     * compositionを確定して空白を一つ入力する。
     */
    fun insertSpace(): Boolean {
        if (!commitComposition()) return false
        val accepted = connection.commitText(" ", 1)
        if (accepted) updateExternalSelectionAfterCommit(" ")
        return accepted
    }

    /**
     * 候補行から選択した表記をcompositionへ反映する。機密欄では拒否する。
     */
    fun applyCandidate(value: String): Boolean {
        if (!active || liveCore != null || policy.suppressSuggestions || value.isEmpty() || buffer.isEmpty) return false
        markCompositionChanged()
        buffer.setDisplay(value)
        if (synchronizeComposition()) return true
        failClosed()
        return false
    }

    /**
     * 読みをカタカナにする。ライブ変換では対象の文節を、そのカタカナ表記の候補へ切り替える（候補に無ければ何もしない）。
     * 明示変換では、変換結果を表示していない読みをカタカナ表記へ置き換える。
     */
    fun toKatakana(): Boolean {
        if (!active) return false
        val core = liveCore
        if (core != null) {
            val focused = core.focusedSegment ?: return false
            val katakana = BasicCandidateProvider.toKatakana(focused.reading)
            if (focused.surface == katakana) return true
            val choice = core.candidateBar()?.choices?.firstOrNull { it.value == katakana } ?: return false
            return selectLiveCandidate(choice)
        }
        if (buffer.isEmpty || pendingConversion != null || currentConversion() != null) return false
        return applyCandidate(BasicCandidateProvider.toKatakana(buffer.reading))
    }

    /** カーソル直前のかなを小文字・濁点・半濁点へ循環変換する。 */
    fun transformKana(): Boolean {
        if (!active || policy.isTypeNull) return false
        if (liveCore != null) return performLive { it.transformBeforeCursor(KanaModifier::transform) } ?: false
        if (buffer.isEmpty) return false
        if (!buffer.transformBeforeCursor(KanaModifier::transform)) return false
        markCompositionChanged()
        if (synchronizeComposition()) return true
        failClosed()
        return false
    }

    /**
     * 明示変換キーを処理する。変換エンジンが使えれば読みの変換を非同期に依頼し、結果を待たずに戻る。
     * 機密欄・互換入力欄では要求自体を送らない。エンジンが使えない、または同じ読みで失敗済みなら
     * 従来どおりカタカナ候補を選ぶ。
     */
    fun convert(): Boolean {
        if (liveCore != null) return selectNextLiveCandidate()
        if (active && !buffer.isEmpty && (pendingConversion != null || currentConversion() != null)) return true
        val client = conversionClient
        if (active && !buffer.isEmpty && client != null && client.isAvailable &&
            !policy.suppressSuggestions && failedConversionRevision != revision
        ) {
            val request = ConversionRequest(
                sessionEpoch = sessionEpoch,
                revision = revision,
                reading = buffer.reading,
                incognito = policy.suppressLearning,
            )
            pendingConversion = request
            client.requestConversion(request)
            return true
        }
        val katakana = BasicCandidateProvider.toKatakana(buffer.reading)
        return if (katakana == buffer.display) {
            buffer.reading.isNotEmpty()
        } else {
            applyCandidate(katakana)
        }
    }

    /**
     * Enterを改行またはeditor actionへ振り分ける。
     */
    fun handleEnter(): Boolean {
        if (liveCore != null) {
            val kind = if (EditorActionPolicy.shouldInsertNewline(policy)) {
                EnterKind.Newline
            } else {
                EnterKind.EditorAction(policy.imeAction)
            }
            return performLive { it.enter(kind) } ?: false
        }
        if (!commitComposition()) return false
        return if (EditorActionPolicy.shouldInsertNewline(policy)) {
            val accepted = connection.commitText("\n", 1)
            if (accepted) updateExternalSelectionAfterCommit("\n")
            accepted
        } else {
            connection.performEditorAction(policy.imeAction)
        }
    }

    /**
     * Editorのselectionとcomposing spanを照合し、現在の通知だけを反映する。
     * 以前送ったcompositionへの遅延通知は現在のcursorを巻き戻さない。
     */
    fun updateSelection(
        oldSelectionStart: Int,
        oldSelectionEnd: Int,
        newSelectionStart: Int,
        newSelectionEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        if (!active) return
        val hasComposingSpan = candidatesStart >= 0 && candidatesEnd >= candidatesStart
        if (!hasComposingSpan && consumeCommittedSelection(
                oldSelectionStart,
                oldSelectionEnd,
                newSelectionStart,
                newSelectionEnd,
            )
        ) {
            return
        }
        if (ownedDisplay.isEmpty()) {
            compositionStart = null
            clearCompositionExpectations()
            pendingCommittedSelections.clear()
            updateKnownSelection(newSelectionStart, newSelectionEnd)
            return
        }

        if (!hasComposingSpan) {
            // hostがspanを終了済みなら、旧readingを次の入力で再挿入しない。
            discardCompositionWithoutEditorCall()
            updateKnownSelection(newSelectionStart, newSelectionEnd)
            return
        }

        if (newSelectionStart < 0 || newSelectionEnd < 0 || newSelectionStart != newSelectionEnd) {
            finishAndDiscardComposition(newSelectionStart, newSelectionEnd)
            return
        }

        val expected = expectedComposition
        if (expected?.matches(candidatesStart, candidatesEnd, newSelectionStart) == true) {
            if (acceptCompositionSelection(candidatesStart, newSelectionStart)) {
                expectedComposition = expected.copy(start = candidatesStart)
                staleCompositions.clear()
                return
            }
        }
        val staleIndex = staleCompositions.indexOfFirst {
            it.matches(candidatesStart, candidatesEnd, newSelectionStart)
        }
        if (staleIndex >= 0) {
            staleCompositions.removeAt(staleIndex)
            return
        }

        val sameCurrentSpan = expected?.matchesSpan(candidatesStart, candidatesEnd) == true ||
            (expected == null && candidatesEnd - candidatesStart == ownedDisplay.length)
        if (sameCurrentSpan && acceptCompositionSelection(candidatesStart, newSelectionStart)) {
            recordExpectedComposition(
                CompositionGeometry(
                    start = candidatesStart,
                    displayLength = ownedDisplay.length,
                    cursorOffset = newSelectionStart - candidatesStart,
                ),
            )
            return
        }

        // host側に残るspanを確定してから内部readingを捨て、次入力の誤置換を防ぐ。
        finishAndDiscardComposition(newSelectionStart, newSelectionEnd)
    }

    /**
     * セッションを無効化し、内部compositionだけを捨てる。
     * 旧InputConnectionへfinishやcommitを送らないことが切替時の安全条件である。
     */
    fun close() {
        if (!active) return
        active = false
        buffer.clear()
        liveCore?.finishField()
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        pendingCommittedSelections.clear()
        connection.invalidate()
    }

    /** ライブ変換の候補バーに出す内容。明示変換ではnull。 */
    fun liveCandidateState(): LiveCandidateState? {
        val core = liveCore ?: return null
        if (!active) return null
        val focused = core.focusedSegment
        val segments = core.segments
        val focusedIndex = segments.indexOfFirst { it.id == focused?.id }
        return LiveCandidateState(
            choices = core.candidateBar()?.choices.orEmpty(),
            currentValue = focused?.surface,
            canUndo = core.canUndo,
            canFocusPrevious = segments.take(focusedIndex.coerceAtLeast(0)).any(::isFocusTarget),
            focusAtInput = core.isFocusAtInput,
        )
    }

    /** ライブ変換の候補を選ぶ。表示時と状態が変わっていれば拒否する。対象segmentはchosenになる。 */
    fun selectLiveCandidate(choice: CandidateChoice): Boolean = performLive { it.selectCandidate(choice) } ?: false

    /**
     * 候補バーの対象を前（direction<0）または次のsegmentへ移す。読点は飛ばし、最後のsegmentの次は
     * 末尾入力位置へ戻す。入力カーソルは動かさないため、訂正後に末尾から入力を続けられる。
     */
    fun moveLiveFocus(direction: Int): Boolean {
        val core = liveCore ?: return false
        if (!active || direction == 0) return false
        val segments = core.segments
        var index = segments.indexOfFirst { it.id == core.focusedSegment?.id }
        if (index < 0) return false
        do {
            index += direction
        } while (index in segments.indices && !isFocusTarget(segments[index]))
        val update = when {
            index in segments.indices -> core.focusSegment(segments[index].id)
            direction > 0 -> core.returnToInputPosition()
            else -> return false
        }
        return update.handled && refreshLiveHighlight()
    }

    /** 候補バーの対象を末尾入力位置のsegmentへ戻す。 */
    fun returnLiveFocusToInput(): Boolean {
        val core = liveCore ?: return false
        if (!active) return false
        return core.returnToInputPosition().handled && refreshLiveHighlight()
    }

    /** ライブ変換の直前の操作（候補の誤選択など）を取り消す。取り消せる操作が無ければfalse。 */
    fun undoLive(): Boolean = performLive { it.undo() } ?: false

    /** workerから届いたライブ変換の結果を、コアが現在の状態と照合できた場合だけ表示へ反映する。 */
    fun applyLiveResult(result: LiveResult): Boolean {
        val core = liveCore ?: return false
        if (!active) return false
        val update = core.onConversionResult(result)
        if (update.rejection != null || update.commands.isEmpty()) return false
        return applyLiveUpdate(update, core.segments)
    }

    /** ライブ変換の表示（明示変換では読みの表示）。selection通知の照合で使う。 */
    private val ownedDisplay: String
        get() = liveCore?.display ?: buffer.display

    /**
     * コアの操作を実行し、その書込み命令と変換要求を適用する。コアが扱わない操作ならnullを返し、
     * 呼び出し側が従来の経路へ回すか失敗として扱う。候補のタップが古い場合などはfalseを返す。
     */
    private fun performLive(operation: (LiveConversionCore) -> LiveUpdate): Boolean? {
        val core = liveCore ?: return null
        if (!active) return false
        // 確定の前のsegment列。確定した内容をエンジンへ学習させるために使う。
        val before = core.segments
        val update = operation(core)
        if (!update.handled) return null
        if (update.rejection != null) return false
        return applyLiveUpdate(update, before)
    }

    /**
     * コアのEditorCommandを順にInputConnectionへ送る唯一の経路。書込みに失敗したら接続を閉じ、
     * 同じ本文を再送しない。確定した場合は確定前のsegment列を学習へ回し、新しい要求を変換へ送る。
     */
    private fun applyLiveUpdate(update: LiveUpdate, before: List<LiveSegment>): Boolean {
        var learned = false
        for (command in update.commands) {
            when (command) {
                is EditorCommand.SetComposition -> if (!writeLiveComposition(command.text, command.cursorUtf16)) {
                    return false
                }

                is EditorCommand.Commit -> {
                    if (!commitCompositionAs(command.text)) return false
                    liveCursorUtf16 = 0
                    if (!learned) learnCommitted(before)
                    learned = true
                }

                is EditorCommand.PerformEditorAction -> if (!connection.performEditorAction(command.actionCode)) {
                    return false
                }
            }
        }
        update.request?.let(::dispatchLiveRequest)
        return true
    }

    /** ライブ変換のcompositionを送る。訂正中のsegmentがあれば表示へ強調を付ける。 */
    private fun writeLiveComposition(text: String, cursorUtf16: Int): Boolean {
        if (compositionStart == null && text.isNotEmpty()) compositionStart = knownSelectionStart()
        liveCursorUtf16 = cursorUtf16
        if (writeComposition(styleLiveComposition(text), cursorUtf16)) return true
        failClosed()
        return false
    }

    /** 候補バーの対象が変わったときに、同じ表示とカーソルで強調だけを付け直す。 */
    private fun refreshLiveHighlight(): Boolean {
        val core = liveCore ?: return false
        if (core.display.isEmpty()) return true
        return writeLiveComposition(core.display, liveCursorUtf16)
    }

    /** 訂正中（末尾入力位置以外）のsegmentがあれば、その表示範囲を強調した文字列を返す。 */
    private fun styleLiveComposition(text: String): CharSequence {
        val core = liveCore ?: return text
        if (core.isFocusAtInput || text != core.display) return text
        return compositionStyler(text, core.displaySpans().firstOrNull { it.focused })
    }

    /** 変換要求を送る。エンジンが使えない間は、かな・カナ候補の結果をその場で返す。 */
    private fun dispatchLiveRequest(request: LiveRequest) {
        val client = liveClient
        if (client != null && client.isAvailable) {
            client.requestLiveConversion(sessionEpoch, request)
            return
        }
        kanaFallbackConverter.convert(request)?.let(::applyLiveResult)
    }

    /** 確定したsegment列をエンジンへ学習させる。学習禁止欄とエンジンが使えない場合は送らない。 */
    private fun learnCommitted(segments: List<LiveSegment>) {
        if (policy.suppressLearning) return
        val client = liveClient ?: return
        if (!client.isAvailable) return
        val units = liveLearningUnits(segments)
        if (units.isNotEmpty()) client.learnCommitted(sessionEpoch, units)
    }

    /** 候補バーの対象にできるsegmentか。読点だけのsegmentは訂正の対象にしない。 */
    private fun isFocusTarget(segment: LiveSegment): Boolean = segment.reading != LiveConversionRules.SOFT_BOUNDARY

    /** 変換キーでは、対象segmentの次の候補を選ぶ（最後の候補の次は先頭へ戻る）。 */
    private fun selectNextLiveCandidate(): Boolean {
        val core = liveCore ?: return false
        if (!active) return false
        val choices = core.candidateBar()?.choices.orEmpty()
        if (choices.isEmpty()) return false
        val current = core.focusedSegment?.surface
        val next = choices[(choices.indexOfFirst { it.value == current } + 1) % choices.size]
        if (next.value == current) return true
        return selectLiveCandidate(next)
    }

    /**
     * Editorが通知したcomposition内のカーソルをライブ変換へ反映する。読みの位置へ対応付けられれば
     * 入力カーソルを移し、変換済み表示の内部なら、そのsegmentを候補バーの対象にしてカーソルを元へ戻す。
     */
    private fun acceptLiveSelection(core: LiveConversionCore, start: Int, cursor: Int): Boolean {
        val offset = cursor - start
        compositionStart = start
        selectionStart = cursor
        selectionEnd = cursor
        if (offset == liveCursorUtf16) return true
        val index = core.clusterIndexForDisplayOffset(offset)
        if (index != null) {
            liveCursorUtf16 = offset
            return performLive { it.moveCursorTo(index) } ?: false
        }
        val span = core.displaySpans().firstOrNull { offset > it.start && offset < it.end } ?: return false
        if (!core.focusSegment(span.segmentId).handled) return false
        return refreshLiveHighlight()
    }

    /** compositionを確定済みの表記へ置き換えて確定し、内部の読みを空にする。 */
    private fun commitCompositionAs(text: String): Boolean {
        val start = compositionStart ?: knownSelectionStart()
        val accepted = connection.commitText(text, 1)
        if (!accepted) {
            failClosed()
            return false
        }
        updateExternalSelectionAfterCommit(text, start)
        buffer.clear()
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        return true
    }

    /** compositionの変更を記録し、待機中の要求と表示中の変換結果を無効にする。 */
    private fun markCompositionChanged() {
        revision += 1
        pendingConversion = null
        appliedConversion = null
    }

    /** 表示中の変換結果が現在のrevisionと読みに一致する場合だけ返す。 */
    private fun currentConversion(): ConversionResult? {
        val conversion = appliedConversion ?: return null
        if (conversion.request.revision != revision || conversion.request.reading != buffer.reading) return null
        return conversion
    }

    /** 明示変換のcompositionをEditorへ送る。未変換なら読みのカーソル、変換表示中なら末尾へカーソルを置く。 */
    private fun synchronizeComposition(): Boolean {
        val cursorOffset = if (buffer.display == buffer.reading) {
            buffer.cursorUtf16Offset()
        } else {
            buffer.display.length
        }
        return writeComposition(buffer.display, cursorOffset)
    }

    /**
     * compositionの表示とカーソル（composition先頭からのUTF-16 offset）をEditorへ送り、
     * 後から届くselection通知と照合する期待値を記録する。明示変換とライブ変換の共通の書込み経路である。
     */
    private fun writeComposition(text: CharSequence, cursorOffset: Int): Boolean {
        if (!active) return false
        val accepted = connection.setComposingText(text, 1)
        if (!accepted) return false

        val start = compositionStart ?: selectionStart
        if (text.isEmpty()) {
            compositionStart = null
            clearCompositionExpectations()
            selectionStart = start
            selectionEnd = start
            return true
        }

        recordExpectedComposition(
            CompositionGeometry(
                start = start,
                displayLength = text.length,
                cursorOffset = text.length,
            ),
        )
        if (start == null) {
            selectionStart = null
            selectionEnd = null
            return true
        }

        val target = start + cursorOffset
        val end = start + text.length
        val selectionAccepted = if (target == end) {
            true
        } else {
            connection.setSelection(target, target)
        }
        if (selectionAccepted) {
            selectionStart = target
            selectionEnd = target
            if (target != end) {
                recordExpectedComposition(
                    CompositionGeometry(
                        start = start,
                        displayLength = text.length,
                        cursorOffset = cursorOffset,
                    ),
                )
            }
        }
        return selectionAccepted
    }

    private fun updateExternalSelectionAfterCommit(
        text: String,
        start: Int? = knownSelectionStart(),
    ) {
        val previousStart = selectionStart
        val previousEnd = selectionEnd
        if (start == null) {
            selectionStart = null
            selectionEnd = null
            return
        }
        val destination = start + text.length
        if (previousStart != null && previousEnd != null) {
            recordCommittedSelection(
                SelectionTransition(
                    oldStart = minOf(previousStart, previousEnd),
                    oldEnd = maxOf(previousStart, previousEnd),
                    newStart = destination,
                    newEnd = destination,
                ),
            )
        }
        selectionStart = destination
        selectionEnd = destination
    }

    /** 現在のselectionが既知なら、正規化済みの開始位置を返す。 */
    private fun knownSelectionStart(): Int? {
        val start = selectionStart ?: return null
        val end = selectionEnd ?: return null
        return minOf(start, end)
    }

    /** 不明値を0へ変換せず、既知のselectionだけを正規化して保存する。 */
    private fun updateKnownSelection(start: Int, end: Int) {
        if (start < 0 || end < 0) {
            selectionStart = null
            selectionEnd = null
            return
        }
        selectionStart = minOf(start, end)
        selectionEnd = maxOf(start, end)
    }

    /** 現在送信したcompositionを保持し、一つ前までの通知を遅延判定へ残す。 */
    private fun recordExpectedComposition(geometry: CompositionGeometry) {
        expectedComposition?.takeIf { it != geometry }?.let(staleCompositions::add)
        while (staleCompositions.size > MAX_STALE_COMPOSITIONS) {
            staleCompositions.removeAt(0)
        }
        expectedComposition = geometry
    }

    /** 現在のcomposing span内にある書記素境界だけをcursorとして受け入れる。 */
    private fun acceptCompositionSelection(start: Int, cursor: Int): Boolean {
        liveCore?.let { return acceptLiveSelection(it, start, cursor) }
        val offset = cursor - start
        val clusterIndex = if (buffer.display == buffer.reading) {
            GraphemeClusters.clusterIndexAtUtf16(buffer.reading, offset)
        } else {
            when (offset) {
                0 -> 0
                buffer.display.length -> GraphemeClusters.split(buffer.reading).size
                else -> null
            }
        } ?: return false

        if (buffer.setCursor(clusterIndex)) markCompositionChanged()
        compositionStart = start
        selectionStart = cursor
        selectionEnd = cursor
        return true
    }

    /** host側のspanを確定し、成功した場合だけ内部compositionを破棄する。 */
    private fun finishAndDiscardComposition(selectionStart: Int, selectionEnd: Int) {
        if (!connection.finishComposingText()) {
            failClosed()
            return
        }
        discardCompositionWithoutEditorCall()
        updateKnownSelection(selectionStart, selectionEnd)
    }

    /** host側にspanがない場合に、Editorへ再送せず内部compositionだけを破棄する。 */
    private fun discardCompositionWithoutEditorCall() {
        buffer.clear()
        liveCore?.discardComposition()
        liveCursorUtf16 = 0
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        pendingCommittedSelections.clear()
    }

    /** composition通知の現在値と遅延照合履歴を破棄する。 */
    private fun clearCompositionExpectations() {
        expectedComposition = null
        staleCompositions.clear()
    }

    /** 確定操作後にhostから届くはずのselection遷移を順番に保持する。 */
    private fun recordCommittedSelection(transition: SelectionTransition) {
        pendingCommittedSelections += transition
        while (pendingCommittedSelections.size > MAX_STALE_COMPOSITIONS) {
            pendingCommittedSelections.removeAt(0)
        }
    }

    /** 遅延した確定通知なら、それ以前の確定通知とともに消費する。 */
    private fun consumeCommittedSelection(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
    ): Boolean {
        val index = pendingCommittedSelections.indexOfFirst {
            it.matches(oldStart, oldEnd, newStart, newEnd)
        }
        if (index < 0) return false
        repeat(index + 1) { pendingCommittedSelections.removeAt(0) }
        return true
    }

    /** 接続結果が不明な場合に状態を破棄し、同じ本文を再送できなくする。 */
    private fun failClosed() {
        active = false
        buffer.clear()
        liveCore?.finishField()
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        pendingCommittedSelections.clear()
        connection.invalidate()
    }

    private companion object {
        /**
         * 遅延通知の照合に残す過去composition数の上限。ライブ変換では一打鍵で読みの表示と変換結果の表示の
         * 2回（カーソルが末尾以外なら位置の指定を加えて最大4回）書き込むため、明示変換で使っていた8では
         * 2〜4打鍵分の遅れしか照合できない。照合できない遅延通知は外部の変更とみなしてcompositionを
         * 確定・破棄するので、速い連続入力でも誤って確定しないよう32（8打鍵以上）にする。
         */
        const val MAX_STALE_COMPOSITIONS = 32
    }
}

/** 確定操作の前後で期待するselection範囲を表す。 */
private data class SelectionTransition(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val newEnd: Int,
) {
    /** host通知がこの確定操作のselection遷移と一致するかを返す。 */
    fun matches(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int): Boolean {
        if (oldStart < 0 || oldEnd < 0 || newStart < 0 || newEnd < 0) return false
        return this.oldStart == minOf(oldStart, oldEnd) &&
            this.oldEnd == maxOf(oldStart, oldEnd) &&
            this.newStart == minOf(newStart, newEnd) &&
            this.newEnd == maxOf(newStart, newEnd)
    }
}

/** Editorへ送ったcompositionの範囲長と期待cursorを表す。 */
private data class CompositionGeometry(
    val start: Int?,
    val displayLength: Int,
    val cursorOffset: Int,
) {
    /** composing spanとcursorがこの期待値に一致するかを返す。 */
    fun matches(candidatesStart: Int, candidatesEnd: Int, cursor: Int): Boolean {
        return matchesSpan(candidatesStart, candidatesEnd) &&
            cursor == candidatesStart + cursorOffset
    }

    /** cursorを除くcomposing spanがこの期待値に一致するかを返す。 */
    fun matchesSpan(candidatesStart: Int, candidatesEnd: Int): Boolean {
        return candidatesEnd - candidatesStart == displayLength &&
            (start == null || start == candidatesStart)
    }
}
