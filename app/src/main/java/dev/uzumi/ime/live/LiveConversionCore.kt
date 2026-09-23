package dev.uzumi.ime.live

import dev.uzumi.ime.editor.GraphemeClusters

/**
 * ライブ変換の状態機械。読み・segment・Undo履歴を持ち、入力と変換結果からEditorへの書込み命令を返す。
 * Android frameworkと変換エンジンに依存せず、結果の適用と破棄をこの一か所で順序付ける。
 * スレッド安全ではないため、統合担当は一つのスレッド（通常はUIスレッド）からだけ呼ぶ。
 */
class LiveConversionCore(initialConverterGeneration: Long = 0L) {
    /** フィールド・接続・機密方針が変わるたびに進め、古い書込みを拒否する。 */
    var sessionEpoch: Long = 0L
        private set

    /** 読み、候補選択、カーソル、Undo/Redo、境界、設定の変更で進める。 */
    var revision: Long = 0L
        private set

    /** 辞書とモデルの世代。変わると古い世代の結果を捨てる。 */
    var converterGeneration: Long = initialConverterGeneration
        private set

    /** 有効な入力欄に結び付いているか。 */
    var isActive: Boolean = false
        private set

    /** ライブ変換がONか。OFFの間、入力操作はhandled=falseを返す。 */
    var isLiveEnabled: Boolean = true
        private set

    /** 現在の入力欄の変換・学習方針。 */
    var fieldPolicy: LiveFieldPolicy = LiveFieldPolicy.NORMAL
        private set

    private var state = CompositionState.EMPTY

    // 読みの変更だけで進む版。stable判定で「独立した読みrevision」を数えるために使う。
    private var readingVersion = 0L
    private var nextSegmentId = 1L

    // Undo/Redoで復元したprovisional segment。次の読み編集まで自動更新から守る。
    private val guardedSegmentIds = mutableSetOf<Long>()
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    /** composition全体の読み。 */
    val reading: String
        get() = state.clusters.joinToString(separator = "")

    /** Editorへ表示しているcomposition。 */
    val display: String
        get() = state.segments.joinToString(separator = "") { it.surface }

    /** 読みを先頭から覆うsegment列。 */
    val segments: List<LiveSegment>
        get() = state.segments

    /** 次の読みを入れる書記素位置（末尾入力位置）。過去segmentの訂正中も動かない。 */
    val inputCursor: Int
        get() = state.inputCursor

    /** 候補バーの対象segment。明示的に選んでいなければカーソル直前のsegment。 */
    val focusedSegment: LiveSegment?
        get() = state.segments.firstOrNull { it.id == state.focusedSegmentId } ?: defaultFocus()

    /**
     * 候補バーの対象が末尾入力位置の既定segmentか。falseなら過去segmentを訂正中で、
     * 統合担当は「末尾へ戻る」操作と対象segmentの強調を表示する。
     */
    val isFocusAtInput: Boolean
        get() = state.focusedSegmentId == null || focusedSegment?.id == defaultFocus()?.id

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    val undoDepth: Int
        get() = undoStack.size

    val redoDepth: Int
        get() = redoStack.size

    /**
     * 新しい入力欄へ接続する。epochを進め、旧compositionを新しい接続へ送らずに捨てる。
     */
    fun startField(policy: LiveFieldPolicy): LiveUpdate {
        sessionEpoch += 1
        revision += 1
        isActive = true
        fieldPolicy = policy
        resetComposition()
        return LiveUpdate.NO_CHANGE
    }

    /**
     * 入力欄から離れる。epochを進め、以後の結果と操作を受け付けない。Editorへは何も送らない。
     */
    fun finishField() {
        sessionEpoch += 1
        revision += 1
        isActive = false
        resetComposition()
    }

    /**
     * ライブ変換のON/OFFを切り替える。OFFにするときは表示を一度確定し、以後の入力を明示変換へ渡す。
     */
    fun setLiveEnabled(enabled: Boolean): LiveUpdate {
        if (enabled == isLiveEnabled) return LiveUpdate.NO_CHANGE
        val commands = if (isActive && !enabled) commitCommands() else emptyList()
        isLiveEnabled = enabled
        revision += 1
        resetComposition()
        return LiveUpdate(handled = true, commands = commands)
    }

    /**
     * 辞書やモデルの世代を更新する。以前の世代の結果を捨て、現在の読みで要求を出し直す。
     */
    fun setConverterGeneration(generation: Long): LiveUpdate {
        if (generation == converterGeneration) return LiveUpdate.NO_CHANGE
        converterGeneration = generation
        return LiveUpdate(handled = true, request = buildRequest())
    }

    /**
     * 文字を入力カーソルへ入れる。`。！？`は表示を確定し、`、`は直前をstableにする。
     */
    fun inputText(text: String): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        if (text.isEmpty()) return LiveUpdate.NO_CHANGE
        val parts = splitAtPunctuation(text)
        if (parts.size == 1) return inputPart(text)
        // 「よい。」のように句読点を含む文字列は、句読点ごとに分けて一文字ずつ入れた場合と同じ境界処理にする。
        val commands = mutableListOf<EditorCommand>()
        var request: ConversionRequest? = null
        for (part in parts) {
            val update = inputPart(part)
            commands += update.commands
            request = update.request
        }
        return LiveUpdate(handled = true, commands = commands, request = request)
    }

    /** 句読点一文字、または句読点を含まない文字列を入力カーソルへ入れる。 */
    private fun inputPart(text: String): LiveUpdate {
        val cursor = state.inputCursor
        return when (text) {
            in LiveConversionRules.TERMINAL_PUNCTUATION -> {
                applyReadingEdit(cursor, cursor, text, OperationKind.BOUNDARY, boundarySegment = true)
                val commands = commitCommands()
                revision += 1
                resetComposition()
                LiveUpdate(handled = true, commands = commands)
            }

            LiveConversionRules.SOFT_BOUNDARY -> {
                applyReadingEdit(cursor, cursor, text, OperationKind.BOUNDARY, boundarySegment = true)
                state = state.copy(segments = promoteSegments(state.segments))
                compositionUpdate(buildRequest())
            }

            else -> {
                if (!applyReadingEdit(cursor, cursor, text, OperationKind.INPUT)) return LiveUpdate.NO_CHANGE
                compositionUpdate(buildRequest())
            }
        }
    }

    /**
     * 入力カーソル直前の読みを書記素単位で削除する。変換済みsegmentの末尾を消すと、
     * そのsegmentだけを読みへ戻して再変換する（「良い」→「よ」）。
     * compositionが空、または入力カーソルがcompositionの先頭にある場合はhandled=falseを返す。
     * 統合担当は従来の経路で、Editorのカーソル（composition先頭）より前の確定済み文字列を一書記素消す。
     * compositionの読みと表示は変わらないため、コアの状態はそのまま使える。
     */
    fun deleteBackward(clusterCount: Int = 1): LiveUpdate {
        if (!acceptsInput() || state.clusters.isEmpty()) return LiveUpdate.NOT_HANDLED
        val cursor = state.inputCursor
        if (cursor == 0) return LiveUpdate.NOT_HANDLED
        if (clusterCount <= 0) return LiveUpdate.NO_CHANGE
        val start = (cursor - clusterCount).coerceAtLeast(0)
        if (!applyReadingEdit(start, cursor, "", OperationKind.DELETE)) return LiveUpdate.NO_CHANGE
        return compositionUpdate(buildRequest())
    }

    /**
     * 入力カーソル直前の書記素を、濁点・小文字などの変換で置き換える。変換できなければhandled=false。
     */
    fun transformBeforeCursor(transform: (String) -> String?): LiveUpdate {
        if (!acceptsInput() || state.clusters.isEmpty()) return LiveUpdate.NOT_HANDLED
        val cursor = state.inputCursor
        if (cursor == 0) return LiveUpdate.NOT_HANDLED
        val replacement = transform(state.clusters[cursor - 1]) ?: return LiveUpdate.NOT_HANDLED
        if (!applyReadingEdit(cursor - 1, cursor, replacement, OperationKind.KANA_TRANSFORM)) {
            return LiveUpdate.NO_CHANGE
        }
        return compositionUpdate(buildRequest())
    }

    /**
     * 入力カーソルを読みの書記素位置へ動かす。変換済みsegmentの内部へ入る場合は、
     * 表示内の位置と読みを対応付けられないため、そのsegmentを読みへ戻す（Undo可能な一操作）。
     */
    fun moveCursorTo(clusterIndex: Int): LiveUpdate {
        if (!acceptsInput() || state.clusters.isEmpty()) return LiveUpdate.NOT_HANDLED
        val target = clusterIndex.coerceIn(0, state.clusters.size)
        if (target == state.inputCursor) return LiveUpdate.NO_CHANGE
        val inside = state.segments.firstOrNull { it.readingStart < target && target < it.readingEnd }
        var segments = state.segments
        if (inside != null && !inside.isRaw) {
            pushUndo(OperationKind.READING_REVERT)
            segments = segments.map {
                if (it.id == inside.id) rawSegment(state.clusters, it.readingStart, it.readingEnd) else it
            }
        }
        state = state.copy(segments = segments, inputCursor = target, focusedSegmentId = null)
        revision += 1
        return compositionUpdate(buildRequest())
    }

    /**
     * 候補バーの対象を過去segmentへ移す。入力カーソルは動かさないため、訂正後に末尾入力へ戻れる。
     * そのsegmentの候補をまだ取り直していなければ、候補の要求を添える。
     */
    fun focusSegment(segmentId: Long): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val segment = state.segments.firstOrNull { it.id == segmentId } ?: return LiveUpdate.NOT_HANDLED
        state = state.copy(focusedSegmentId = segmentId)
        return LiveUpdate(handled = true, candidateRequest = candidateRequestFor(segment))
    }

    /**
     * 候補バーの対象segmentの候補を、まだ取り直していなければ取り直す要求を返す。候補一覧を開いたときに使う。
     */
    fun requestFocusedCandidates(): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val segment = focusedSegment ?: return LiveUpdate.NOT_HANDLED
        return LiveUpdate(handled = true, candidateRequest = candidateRequestFor(segment))
    }

    /**
     * 候補バーの対象segmentの区切りを、終わりの側で一書記素だけ縮める（delta<0）か伸ばす（delta>0）。
     * 伸縮したsegmentはユーザーが決めた区切りとして読みへ戻し、変換器へ一segmentとしての変換を求める。
     * 結果が届くとchosenになり、以後の自動更新から守られる。縮めて外れた書記素は未変換のsegmentになり、
     * 後ろが自由なsegmentなら次の変換で一緒に変換される。伸ばして一部を取り込んだ後ろのsegmentは、残りを未変換へ戻す。
     * 入力カーソルを内部に含むことになる伸縮と、読点をまたぐ伸縮はしない。Undo可能な一操作である。
     */
    fun resizeFocusedSegment(delta: Int): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val target = focusedSegment ?: return LiveUpdate.NOT_HANDLED
        if (delta == 0 || target.reading == LiveConversionRules.SOFT_BOUNDARY) return LiveUpdate.NO_CHANGE
        val newEnd = target.readingEnd + if (delta < 0) -1 else 1
        if (newEnd <= target.readingStart || newEnd > state.clusters.size) return LiveUpdate.NO_CHANGE
        // カーソルが伸縮後のsegmentの内部に入ると、表示上のカーソルと読みの位置を対応付けられない。
        if (state.inputCursor > target.readingStart && state.inputCursor < newEnd) return LiveUpdate.NO_CHANGE
        val index = state.segments.indexOfFirst { it.id == target.id }
        val next = state.segments.getOrNull(index + 1)
        if (delta > 0 && (next == null || next.reading == LiveConversionRules.SOFT_BOUNDARY)) return LiveUpdate.NO_CHANGE
        val clusters = state.clusters
        val resized = rawSegment(clusters, target.readingStart, newEnd).copy(userBounded = true)
        val segments = if (delta < 0) {
            state.segments.take(index) + resized + rawSegment(clusters, newEnd, target.readingEnd) +
                state.segments.drop(index + 1)
        } else {
            // 伸ばした分を取り込んだ後ろのsegmentの残り。一書記素だけのsegmentなら残りは無い。
            val rest = next?.takeIf { it.readingEnd > newEnd }?.let { rawSegment(clusters, newEnd, it.readingEnd) }
            state.segments.take(index) + listOfNotNull(resized, rest) + state.segments.drop(index + 2)
        }
        pushUndo(OperationKind.SEGMENT_RESIZE)
        state = state.copy(segments = segments, focusedSegmentId = resized.id)
        revision += 1
        return compositionUpdate(buildRequest())
    }

    /**
     * 取り直した候補を、要求時と同じepoch・revision・segmentの場合だけ対象segmentへ入れる。表示は変えない。
     * 候補は増えるだけなので、表示済みの候補のタップはそのまま受け付けられる。
     */
    fun onCandidateResult(result: CandidateResult): LiveUpdate {
        val request = result.request
        val reason = when {
            !isActive -> RejectReason.NOT_ACTIVE
            !isLiveEnabled -> RejectReason.LIVE_DISABLED
            !fieldPolicy.conversionAllowed -> RejectReason.CONVERSION_NOT_ALLOWED
            request.sessionEpoch != sessionEpoch -> RejectReason.EPOCH_MISMATCH
            request.converterGeneration != converterGeneration -> RejectReason.GENERATION_MISMATCH
            request.revision != revision -> RejectReason.REVISION_MISMATCH
            else -> null
        }
        if (reason != null) return rejected(reason)
        val segment = state.segments.firstOrNull { it.id == request.segmentId }
        if (segment == null || !segment.converted || segment.reading != request.reading ||
            segment.readingStart != request.readingStart || segment.readingEnd != request.readingEnd
        ) {
            return rejected(RejectReason.STALE_CANDIDATE)
        }
        val merged = (result.candidates.filter { it.isNotEmpty() } + segment.candidates).distinct()
        val candidates = if (segment.surface in merged) merged else listOf(segment.surface) + merged
        val updated = segment.copy(candidates = candidates, candidatesComplete = true)
        state = state.copy(segments = state.segments.map { if (it.id == segment.id) updated else it })
        return LiveUpdate.NO_CHANGE
    }

    /**
     * 候補バーの対象を末尾入力位置へ戻す。
     */
    fun returnToInputPosition(): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        state = state.copy(focusedSegmentId = null)
        return LiveUpdate.NO_CHANGE
    }

    /**
     * 対象segmentの候補を、現在のepochとrevisionを付けて返す。compositionが空ならnull。
     */
    fun candidateBar(): CandidateBar? {
        if (!acceptsInput()) return null
        val segment = focusedSegment ?: return null
        return CandidateBar(
            segmentId = segment.id,
            choices = segment.candidates.map { CandidateChoice(sessionEpoch, revision, segment.id, it) },
        )
    }

    /**
     * 候補を選ぶ。表示時のepoch・revision・segmentと一致する場合だけ、そのsegmentをchosenにする。
     * 文全体は確定しない。
     */
    fun selectCandidate(choice: CandidateChoice): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val segment = state.segments.firstOrNull { it.id == choice.segmentId }
        if (choice.sessionEpoch != sessionEpoch ||
            choice.revision != revision ||
            segment == null ||
            choice.value !in segment.candidates
        ) {
            return LiveUpdate(handled = true, rejection = RejectReason.STALE_CANDIDATE)
        }
        pushUndo(OperationKind.CANDIDATE)
        val chosen = segment.copy(surface = choice.value, state = SegmentState.CHOSEN, converted = true)
        // カーソルが変換済み表示の内部に残らないよう、内部にあった場合はsegment末尾へ移す。
        val cursor = if (segment.readingStart < state.inputCursor && state.inputCursor < segment.readingEnd) {
            segment.readingEnd
        } else {
            state.inputCursor
        }
        state = state.copy(
            segments = state.segments.map { if (it.id == segment.id) chosen else it },
            inputCursor = cursor,
        )
        revision += 1
        return compositionUpdate(buildRequest())
    }

    /**
     * 直前の操作を取り消す。自動変換は起点の操作に含まれる。復元した範囲は次の読み編集まで保護する。
     */
    fun undo(): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val entry = undoStack.removeLastOrNull() ?: return LiveUpdate.NOT_HANDLED
        redoStack.addLast(HistoryEntry(entry.kind, state))
        restore(entry.state)
        return compositionUpdate(request = null)
    }

    /**
     * 取り消した操作をやり直す。新しい入力の後は使えない。
     */
    fun redo(): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val entry = redoStack.removeLastOrNull() ?: return LiveUpdate.NOT_HANDLED
        undoStack.addLast(HistoryEntry(entry.kind, state))
        restore(entry.state)
        return compositionUpdate(request = null)
    }

    /**
     * 明示的な確定キー。現在の表示を確定し、compositionとUndo範囲を終える。
     */
    fun commitComposition(): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val commands = commitCommands()
        revision += 1
        resetComposition()
        return LiveUpdate(handled = true, commands = commands)
    }

    /**
     * Editor側でcompositionが終わった（外部の確定、範囲選択、アプリ側の置換）ため、
     * Editorへ何も書かずに内部のcompositionとUndo範囲だけを捨てる。旧表示の再送を防ぐために使う。
     */
    fun discardComposition() {
        if (!isActive) return
        revision += 1
        resetComposition()
    }

    /**
     * Enterを処理する。表示を確定した後、改行を一度入れるかEditor actionを一度送る。
     */
    fun enter(kind: EnterKind): LiveUpdate {
        if (!acceptsInput()) return LiveUpdate.NOT_HANDLED
        val commands = commitCommands().toMutableList()
        commands += when (kind) {
            EnterKind.Newline -> EditorCommand.Commit("\n")
            is EnterKind.EditorAction -> EditorCommand.PerformEditorAction(kind.actionCode)
        }
        revision += 1
        resetComposition()
        return LiveUpdate(handled = true, commands = commands)
    }

    /**
     * 変換結果を照合し、現在の状態と一致する場合だけprovisional segmentへ適用する。
     * 一致しない結果はrejectionを付けて捨て、Editorへは何も書かない。
     */
    fun onConversionResult(result: ConversionResult): LiveUpdate {
        val identity = result.identity
        val reason = when {
            !isActive -> RejectReason.NOT_ACTIVE
            !isLiveEnabled -> RejectReason.LIVE_DISABLED
            !fieldPolicy.conversionAllowed -> RejectReason.CONVERSION_NOT_ALLOWED
            identity.sessionEpoch != sessionEpoch -> RejectReason.EPOCH_MISMATCH
            identity.converterGeneration != converterGeneration -> RejectReason.GENERATION_MISMATCH
            identity.revision != revision -> RejectReason.REVISION_MISMATCH
            else -> null
        }
        if (reason != null) return rejected(reason)

        val current = currentIdentity() ?: return rejected(RejectReason.TARGET_MISMATCH)
        when {
            identity.reading != current.reading -> return rejected(RejectReason.READING_MISMATCH)
            identity.targetStart != current.targetStart || identity.targetEnd != current.targetEnd ->
                return rejected(RejectReason.TARGET_MISMATCH)

            identity.protectedRanges != current.protectedRanges || identity.fixedRanges != current.fixedRanges ->
                return rejected(RejectReason.PROTECTED_MISMATCH)
        }

        val pieces = tileResult(result.segments, current) ?: return rejected(RejectReason.MALFORMED_SEGMENTS)
        val boundaries = pieces.map { it.start }.toSet() + current.targetEnd
        val protectedInTarget = state.segments.filter {
            isProtected(it) && it.readingStart >= current.targetStart && it.readingEnd <= current.targetEnd
        }
        if (protectedInTarget.any { it.readingStart !in boundaries || it.readingEnd !in boundaries }) {
            return rejected(RejectReason.CROSSES_PROTECTED)
        }
        // カーソルをまたいで結合すると、表示上のカーソルと読みの位置が対応しなくなる。
        if (current.inputCursor > current.targetStart &&
            current.inputCursor < current.targetEnd &&
            current.inputCursor !in boundaries
        ) {
            return rejected(RejectReason.CROSSES_CURSOR)
        }
        // ユーザーが伸縮で決めた範囲は、ちょうど一つのsegmentとして返っていなければならない。
        if (current.fixedRanges.any { fixed -> pieces.none { it.start == fixed.readingStart && it.end == fixed.readingEnd } }) {
            return rejected(RejectReason.CROSSES_FIXED)
        }

        state = state.copy(segments = promoteSegments(mergeResult(pieces, current, protectedInTarget)))
        // 明示的に注目しているsegmentが変換し直された場合は、その候補を取り直す。
        val focused = state.focusedSegmentId?.let { id -> state.segments.firstOrNull { it.id == id } }
        return compositionUpdate(request = null).copy(candidateRequest = focused?.let(::candidateRequestFor))
    }

    /**
     * UIで下線や対象segmentを描くための、表示上のsegment範囲を返す。
     */
    fun displaySpans(): List<DisplaySpan> {
        val focusedId = focusedSegment?.id
        var offset = 0
        return state.segments.map { segment ->
            val start = offset
            offset += segment.surface.length
            DisplaySpan(segment.id, start, offset, segment.state, segment.converted, segment.id == focusedId)
        }
    }

    /**
     * Editorから通知された表示上のUTF-16 offsetを、読みの書記素位置へ変換する。
     * 変換済み表示の内部など、読みと対応付けられない位置ではnullを返す。
     */
    fun clusterIndexForDisplayOffset(offset: Int): Int? {
        var displayStart = 0
        for (segment in state.segments) {
            val displayEnd = displayStart + segment.surface.length
            if (offset == displayStart) return segment.readingStart
            if (offset < displayEnd) {
                if (segment.surface != segment.reading) return null
                val local = GraphemeClusters.clusterIndexAtUtf16(segment.reading, offset - displayStart) ?: return null
                return segment.readingStart + local
            }
            displayStart = displayEnd
        }
        return if (offset == displayStart) state.clusters.size else null
    }

    /** 入力文字列を、境界となる句読点一文字と、それ以外の連続した文字列とに分ける。 */
    private fun splitAtPunctuation(text: String): List<String> {
        val parts = mutableListOf<String>()
        val run = StringBuilder()
        for (cluster in GraphemeClusters.split(text)) {
            if (cluster in LiveConversionRules.TERMINAL_PUNCTUATION || cluster == LiveConversionRules.SOFT_BOUNDARY) {
                if (run.isNotEmpty()) parts += run.toString()
                run.clear()
                parts += cluster
            } else {
                run.append(cluster)
            }
        }
        if (run.isNotEmpty()) parts += run.toString()
        return parts
    }

    /** 入力欄に接続中で、ライブ変換がONのときだけ操作を受け付ける。 */
    private fun acceptsInput(): Boolean = isActive && isLiveEnabled

    /** 結果や候補のタップを捨てたことを、Editorへの書込みなしで返す。 */
    private fun rejected(reason: RejectReason): LiveUpdate = LiveUpdate(handled = true, rejection = reason)

    /** 現在の表示を確定する命令。表示が空なら何も送らない。 */
    private fun commitCommands(): List<EditorCommand> {
        val text = display
        return if (text.isEmpty()) emptyList() else listOf(EditorCommand.Commit(text))
    }

    /** 現在の表示とカーソルをEditorへ送る命令を作り、新しい要求を添える。 */
    private fun compositionUpdate(request: ConversionRequest?): LiveUpdate {
        return LiveUpdate(
            handled = true,
            commands = listOf(EditorCommand.SetComposition(display, cursorUtf16())),
            request = request,
        )
    }

    /** compositionとUndo範囲を終える。revisionとepochは呼び出し側が進める。 */
    private fun resetComposition() {
        state = CompositionState.EMPTY
        undoStack.clear()
        redoStack.clear()
        guardedSegmentIds.clear()
    }

    /** 現在の状態を取り消し単位として積む。新しい操作なのでRedoを消す。 */
    private fun pushUndo(kind: OperationKind) {
        undoStack.addLast(HistoryEntry(kind, state))
        redoStack.clear()
    }

    /** Undo/Redoで状態を戻し、実行中の要求と表示済み候補を無効にする。 */
    private fun restore(restored: CompositionState) {
        state = restored
        revision += 1
        guardedSegmentIds.clear()
        guardedSegmentIds += restored.segments.filter { it.state == SegmentState.PROVISIONAL }.map { it.id }
    }

    /**
     * 読みの[removeStart, removeEnd)をinsertTextで置き換え、影響したsegmentだけを未変換へ戻す。
     * 結合文字やZWJで隣の書記素とつながる場合も、読み全体を分割し直して変化した範囲を求める。
     * boundarySegmentがtrueなら、segmentの境界へ入れた句読点を独立したstable segmentにする。
     */
    private fun applyReadingEdit(
        removeStart: Int,
        removeEnd: Int,
        insertText: String,
        kind: OperationKind,
        boundarySegment: Boolean = false,
    ): Boolean {
        val old = state
        val oldClusters = old.clusters
        val left = oldClusters.subList(0, removeStart).joinToString(separator = "")
        val right = oldClusters.subList(removeEnd, oldClusters.size).joinToString(separator = "")
        val newReading = left + insertText + right
        val newClusters = GraphemeClusters.split(newReading)

        // 共通の前後を編集位置で制限し、「ああ」への「あ」の挿入のような曖昧さを編集位置側へ寄せる。
        var prefix = 0
        while (prefix < removeStart && prefix < newClusters.size && oldClusters[prefix] == newClusters[prefix]) {
            prefix += 1
        }
        var suffix = 0
        val maxSuffix = oldClusters.size - removeEnd
        while (suffix < maxSuffix &&
            suffix < newClusters.size - prefix &&
            oldClusters[oldClusters.size - 1 - suffix] == newClusters[newClusters.size - 1 - suffix]
        ) {
            suffix += 1
        }
        val oldChangedEnd = oldClusters.size - suffix
        val newChangedEnd = newClusters.size - suffix
        if (prefix == oldChangedEnd && prefix == newChangedEnd) return false
        // 編集後の書記素数の増減。編集範囲より後ろのsegmentをこの分だけずらす。
        val delta = newClusters.size - oldClusters.size

        val pureInsertion = prefix == oldChangedEnd
        var affected = if (pureInsertion) {
            old.segments.filter { it.readingStart < prefix && prefix < it.readingEnd }
        } else {
            old.segments.filter { it.readingStart < oldChangedEnd && it.readingEnd > prefix }
        }
        val standaloneBoundary = boundarySegment && pureInsertion && affected.isEmpty()
        if (pureInsertion && affected.isEmpty() && !boundarySegment) {
            val neighbor = old.segments.firstOrNull { it.readingEnd == prefix && it.isRaw }
                ?: old.segments.firstOrNull { it.readingStart == prefix && it.isRaw }
            affected = listOfNotNull(neighbor)
        }
        val rangeStart = minOf(prefix, affected.minOfOrNull { it.readingStart } ?: prefix)
        val rangeEndOld = maxOf(oldChangedEnd, affected.maxOfOrNull { it.readingEnd } ?: oldChangedEnd)
        val rangeEndNew = rangeEndOld + delta

        val before = old.segments.filter { it.readingEnd <= rangeStart && it !in affected }
        val after = old.segments
            .filter { it.readingStart >= rangeEndOld && it !in affected }
            .map { it.copy(readingStart = it.readingStart + delta, readingEnd = it.readingEnd + delta) }
        val middle = when {
            rangeEndNew <= rangeStart -> emptyList()
            standaloneBoundary -> listOf(punctuationSegment(newClusters, rangeStart, rangeEndNew))
            else -> listOf(rawSegment(newClusters, rangeStart, rangeEndNew))
        }

        pushUndo(kind)
        readingVersion += 1
        revision += 1
        guardedSegmentIds.clear()
        state = CompositionState(
            clusters = newClusters,
            segments = before + middle + after,
            inputCursor = clusterIndexAtOrAfter(newClusters, left.length + insertText.length),
            focusedSegmentId = null,
        )
        return true
    }

    /** 現在の状態から変換要求を作る。変換不可の欄や、自動更新してよい範囲がなければnull。 */
    private fun buildRequest(): ConversionRequest? {
        if (!acceptsInput() || !fieldPolicy.conversionAllowed) return null
        val identity = currentIdentity() ?: return null
        return ConversionRequest(
            identity = identity,
            targetReading = state.clusters.subList(identity.targetStart, identity.targetEnd).joinToString(separator = ""),
            learningAllowed = fieldPolicy.learningAllowed,
        )
    }

    /** 現在の状態が要求として持つべき識別情報。保護されていないsegmentがなければnull。 */
    private fun currentIdentity(): RequestIdentity? {
        val free = state.segments.filterNot(::isProtected)
        if (free.isEmpty()) return null
        return RequestIdentity(
            sessionEpoch = sessionEpoch,
            revision = revision,
            reading = reading,
            targetStart = free.first().readingStart,
            targetEnd = free.last().readingEnd,
            protectedRanges = state.segments.filter(::isProtected).map {
                ProtectedRange(it.readingStart, it.readingEnd, it.surface)
            },
            inputCursor = state.inputCursor,
            converterGeneration = converterGeneration,
            fixedRanges = free.filter { it.userBounded }.map { FixedRange(it.readingStart, it.readingEnd) },
        )
    }

    /**
     * segmentの候補を取り直す要求を作る。変換済みで、まだ取り直しておらず、変換してよい欄の場合だけ返す。
     * 前後のsegmentの読みを文脈として添える（読点は添えない）。
     */
    private fun candidateRequestFor(segment: LiveSegment): CandidateRequest? {
        if (!fieldPolicy.conversionAllowed || !segment.converted || segment.candidatesComplete) return null
        if (segment.reading == LiveConversionRules.SOFT_BOUNDARY) return null
        val index = state.segments.indexOfFirst { it.id == segment.id }
        // 文脈として添える隣のsegmentの読み。読点は変換の文脈にしない。
        fun contextOf(neighbor: LiveSegment?): String =
            neighbor?.reading?.takeUnless { it == LiveConversionRules.SOFT_BOUNDARY }.orEmpty()
        return CandidateRequest(
            sessionEpoch = sessionEpoch,
            revision = revision,
            converterGeneration = converterGeneration,
            segmentId = segment.id,
            readingStart = segment.readingStart,
            readingEnd = segment.readingEnd,
            reading = segment.reading,
            preceding = contextOf(state.segments.getOrNull(index - 1)),
            following = contextOf(state.segments.getOrNull(index + 1)),
            learningAllowed = fieldPolicy.learningAllowed,
        )
    }

    /** 自動変換で書き換えてはいけないsegmentか。カーソルを内部に含むsegmentも読みのまま保つ。 */
    private fun isProtected(segment: LiveSegment): Boolean {
        return segment.state != SegmentState.PROVISIONAL ||
            segment.id in guardedSegmentIds ||
            (segment.readingStart < state.inputCursor && state.inputCursor < segment.readingEnd)
    }

    /**
     * 結果のsegmentを対象範囲の書記素位置へ並べる。読みの欠落・重複、書記素の途中での分割、
     * 空の表記があればnullを返す。
     */
    private fun tileResult(results: List<ResultSegment>, identity: RequestIdentity): List<ResultPiece>? {
        if (results.isEmpty()) return null
        val pieces = mutableListOf<ResultPiece>()
        var position = identity.targetStart
        for (result in results) {
            if (result.surface.isEmpty()) return null
            val clusters = GraphemeClusters.split(result.reading)
            if (clusters.isEmpty() || position + clusters.size > identity.targetEnd) return null
            if (state.clusters.subList(position, position + clusters.size) != clusters) return null
            val candidates = result.candidates.let { if (result.surface in it) it else listOf(result.surface) + it }
            pieces += ResultPiece(
                position,
                position + clusters.size,
                result.surface,
                candidates.distinct(),
                result.candidatesComplete,
            )
            position += clusters.size
        }
        return if (position == identity.targetEnd) pieces else null
    }

    /**
     * 照合済みの結果を対象範囲へ入れる。保護segmentはそのまま残し、同じ読み範囲の以前のsegmentは
     * 識別子と観測回数を引き継ぐ。
     */
    private fun mergeResult(
        pieces: List<ResultPiece>,
        identity: RequestIdentity,
        protectedInTarget: List<LiveSegment>,
    ): List<LiveSegment> {
        val before = state.segments.filter { it.readingEnd <= identity.targetStart }
        val after = state.segments.filter { it.readingStart >= identity.targetEnd }
        val previousFree = state.segments.filter {
            !isProtected(it) && it.readingStart >= identity.targetStart && it.readingEnd <= identity.targetEnd
        }
        val converted = pieces
            .filter { piece -> protectedInTarget.none { piece.start >= it.readingStart && piece.end <= it.readingEnd } }
            .map { piece ->
                val previous = previousFree.firstOrNull {
                    it.readingStart == piece.start && it.readingEnd == piece.end
                }
                // 同じ読み範囲・表記が続いた場合だけ数え、同じ読みrevisionへの二つ目の結果は数えない。
                val observations = if (previous != null && previous.converted && previous.surface == piece.surface) {
                    if (previous.lastObservedReadingVersion == readingVersion) {
                        previous.observations
                    } else {
                        previous.observations + 1
                    }
                } else {
                    1
                }
                // 同じ読み範囲・表記で候補を取り直し済みなら、その候補を引き継ぐ。
                val kept = previous?.takeIf { it.candidatesComplete && it.surface == piece.surface }
                // ユーザーが伸縮で決めた範囲の結果はchosenにして、以後の自動更新から守る。
                val userBounded = previous?.userBounded == true
                LiveSegment(
                    id = previous?.id ?: newSegmentId(),
                    readingStart = piece.start,
                    readingEnd = piece.end,
                    reading = state.clusters.subList(piece.start, piece.end).joinToString(separator = ""),
                    surface = piece.surface,
                    state = if (userBounded) SegmentState.CHOSEN else SegmentState.PROVISIONAL,
                    candidates = kept?.let { (piece.candidates + it.candidates).distinct() } ?: piece.candidates,
                    converted = true,
                    observations = observations,
                    lastObservedReadingVersion = readingVersion,
                    candidatesComplete = piece.candidatesComplete || kept != null,
                    userBounded = userBounded,
                )
            }
        return before + (converted + protectedInTarget).sortedBy { it.readingStart } + after
    }

    /**
     * 変換済みprovisional segmentを昇格させる。後続segmentがあり規定回数観測したもの、
     * または`、`より前にあるものをstableにする。
     */
    private fun promoteSegments(segments: List<LiveSegment>): List<LiveSegment> {
        val lastSoftBoundary = segments.indexOfLast {
            it.reading == LiveConversionRules.SOFT_BOUNDARY && it.converted && it.state == SegmentState.STABLE
        }
        return segments.mapIndexed { index, segment ->
            val eligible = segment.state == SegmentState.PROVISIONAL && segment.converted
            val observedEnough = index < segments.lastIndex &&
                segment.observations >= LiveConversionRules.STABLE_OBSERVATION_COUNT
            if (eligible && (observedEnough || index < lastSoftBoundary)) {
                segment.copy(state = SegmentState.STABLE)
            } else {
                segment
            }
        }
    }

    /** 読みをそのまま表示する未変換segmentを作る。 */
    private fun rawSegment(clusters: List<String>, start: Int, end: Int): LiveSegment {
        val reading = clusters.subList(start, end).joinToString(separator = "")
        return LiveSegment(
            id = newSegmentId(),
            readingStart = start,
            readingEnd = end,
            reading = reading,
            surface = reading,
            state = SegmentState.PROVISIONAL,
            candidates = listOf(reading),
            converted = false,
            observations = 0,
            lastObservedReadingVersion = -1L,
        )
    }

    /** 句読点を変換対象から外すため、独立したstable segmentとして作る。 */
    private fun punctuationSegment(clusters: List<String>, start: Int, end: Int): LiveSegment {
        return rawSegment(clusters, start, end).copy(state = SegmentState.STABLE, converted = true)
    }

    /** epochをまたいでも重複しないsegment識別子を払い出す。 */
    private fun newSegmentId(): Long = nextSegmentId++

    /** 候補バーの既定の対象。入力カーソル直前のsegment、カーソルが先頭なら最初のsegment。 */
    private fun defaultFocus(): LiveSegment? {
        val cursor = state.inputCursor
        return state.segments.firstOrNull { it.readingStart < cursor && cursor <= it.readingEnd }
            ?: state.segments.firstOrNull()
    }

    /** 入力カーソルの、composition先頭からのUTF-16 offset。 */
    private fun cursorUtf16(): Int {
        val cursor = state.inputCursor
        var offset = 0
        for (segment in state.segments) {
            when {
                segment.readingEnd <= cursor -> offset += segment.surface.length
                segment.readingStart < cursor -> {
                    // 内部にカーソルを持つsegmentは未変換なので、読みの書記素幅で数える。
                    offset += if (segment.isRaw) {
                        state.clusters.subList(segment.readingStart, cursor).sumOf(String::length)
                    } else {
                        segment.surface.length
                    }
                }
            }
        }
        return offset
    }

    /** UTF-16 offsetを、その位置以後で最初の書記素境界へ変換する。 */
    private fun clusterIndexAtOrAfter(clusters: List<String>, utf16Offset: Int): Int {
        var offset = 0
        for ((index, cluster) in clusters.withIndex()) {
            if (offset >= utf16Offset) return index
            offset += cluster.length
        }
        return clusters.size
    }

    /** Undo/Redoで戻す単位の状態。segmentは不変なので、参照を保持するだけで複製になる。 */
    private data class CompositionState(
        val clusters: List<String>,
        val segments: List<LiveSegment>,
        val inputCursor: Int,
        val focusedSegmentId: Long?,
    ) {
        companion object {
            val EMPTY = CompositionState(emptyList(), emptyList(), 0, null)
        }
    }

    /** Undo/Redo stackの一項目。操作の種類と、操作前（Redoでは取消前）の状態を持つ。 */
    private data class HistoryEntry(val kind: OperationKind, val state: CompositionState)

    /** 照合済みの結果segmentを、対象範囲の書記素位置へ置いたもの。 */
    private data class ResultPiece(
        val start: Int,
        val end: Int,
        val surface: String,
        val candidates: List<String>,
        val candidatesComplete: Boolean,
    )
}
