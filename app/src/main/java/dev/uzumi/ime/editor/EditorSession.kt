package dev.uzumi.ime.editor

import dev.uzumi.ime.conversion.ConversionClient
import dev.uzumi.ime.conversion.ConversionOutcome
import dev.uzumi.ime.conversion.ConversionRequest
import dev.uzumi.ime.conversion.ConversionResult

/**
 * 一つのEditorInfoとInputConnectionに結び付いた編集セッションを管理する。
 * 切替後の接続へcompositionや遅延イベントを送らない境界として機能する。
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

    /** セッションがまだ編集を受け付けるかを返す。 */
    val isActive: Boolean
        get() = active

    /** compositionの現在状態をUIや候補行へ返す。 */
    fun compositionSnapshot(): CompositionSnapshot = buffer.snapshot()

    /** 応答を待っている変換要求。UIが時間超過を判定するために使う。 */
    val pendingConversionRequest: ConversionRequest?
        get() = pendingConversion

    /** 変換結果を表示中なら変換候補、そうでなければ現在の読みから基本候補を作る。 */
    fun candidateOptions(): List<CandidateOption> {
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
        if (!buffer.isEmpty) {
            if (!buffer.deleteBackward()) return false
            markCompositionChanged()
            if (synchronizeComposition()) return true
            failClosed()
            return false
        }

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
        if (buffer.isEmpty) return true
        val conversion = currentConversion()
        if (!commitCompositionAs(buffer.display)) return false
        conversion?.let { conversionClient?.commitAll(it.request) }
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
        // isConsistentにより、先頭文節の読みは現在の読みの接頭辞であることが保証されている。
        val remainingReading = buffer.reading.substring(conversion.segments.first().reading.length)
        if (!commitCompositionAs(candidate.value)) return false
        conversionClient?.commitCandidate(conversion.request, candidate.id)
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
        if (!active || policy.suppressSuggestions || value.isEmpty() || buffer.isEmpty) return false
        markCompositionChanged()
        buffer.setDisplay(value)
        if (synchronizeComposition()) return true
        failClosed()
        return false
    }

    /** カーソル直前のかなを小文字・濁点・半濁点へ循環変換する。 */
    fun transformKana(): Boolean {
        if (!active || policy.isTypeNull || buffer.isEmpty) return false
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
        if (buffer.isEmpty) {
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
            (expected == null && candidatesEnd - candidatesStart == buffer.display.length)
        if (sameCurrentSpan && acceptCompositionSelection(candidatesStart, newSelectionStart)) {
            recordExpectedComposition(
                CompositionGeometry(
                    start = candidatesStart,
                    displayLength = buffer.display.length,
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
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        pendingCommittedSelections.clear()
        connection.invalidate()
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

    private fun synchronizeComposition(): Boolean {
        if (!active) return false
        val accepted = connection.setComposingText(buffer.display, 1)
        if (!accepted) return false

        val start = compositionStart ?: selectionStart
        if (buffer.isEmpty) {
            compositionStart = null
            clearCompositionExpectations()
            selectionStart = start
            selectionEnd = start
            return true
        }

        val cursorOffset = if (buffer.display == buffer.reading) {
            buffer.cursorUtf16Offset()
        } else {
            buffer.display.length
        }
        recordExpectedComposition(
            CompositionGeometry(
                start = start,
                displayLength = buffer.display.length,
                cursorOffset = buffer.display.length,
            ),
        )
        if (start == null) {
            selectionStart = null
            selectionEnd = null
            return true
        }

        val target = start + cursorOffset
        val end = start + buffer.display.length
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
                        displayLength = buffer.display.length,
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
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        pendingCommittedSelections.clear()
        connection.invalidate()
    }

    private companion object {
        /** 遅延通知の照合に残す過去composition数の上限。 */
        const val MAX_STALE_COMPOSITIONS = 8
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
