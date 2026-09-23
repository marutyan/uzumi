package dev.uzumi.ime.live

import dev.uzumi.ime.editor.GraphemeClusters

/**
 * 決まった語彙で最長一致の分割を返す、試験用の変換器。
 * 保護範囲で必ず区切るという変換器の契約を守り、受け取った要求を記録する。
 */
class FakeLiveConverter(
    /** 読み → 候補（先頭が第一候補）。載っていない読みはそのまま一segmentとして返す。 */
    private val lexicon: Map<String, List<String>>,
) : LiveConverter {
    /** 受け取った要求。試験で要求の内容を確かめるために残す。 */
    val requests = mutableListOf<ConversionRequest>()

    override fun convert(request: ConversionRequest): ConversionResult {
        requests += request
        val identity = request.identity
        val clusters = GraphemeClusters.split(identity.reading)
        val result = mutableListOf<ResultSegment>()
        var position = identity.targetStart
        while (position < identity.targetEnd) {
            val protected = identity.protectedRanges.firstOrNull { it.readingStart == position }
            if (protected != null) {
                val reading = clusters.subList(protected.readingStart, protected.readingEnd).joinToString("")
                result += ResultSegment(reading, protected.surface)
                position = protected.readingEnd
                continue
            }
            // 保護範囲の先頭と入力カーソルの位置で必ず区切るという変換器の契約を守る。
            val chunkEnd = (identity.protectedRanges.map { it.readingStart } + identity.inputCursor)
                .filter { it > position && it < identity.targetEnd }
                .minOrNull() ?: identity.targetEnd
            result += segmentGreedy(clusters.subList(position, chunkEnd))
            position = chunkEnd
        }
        return ConversionResult(identity, result)
    }

    /** 最長一致で語彙を当て、当たらない書記素はまとめて一segmentにする。 */
    private fun segmentGreedy(clusters: List<String>): List<ResultSegment> {
        val result = mutableListOf<ResultSegment>()
        val unknown = StringBuilder()
        var index = 0
        while (index < clusters.size) {
            val match = (clusters.size downTo index + 1)
                .map { clusters.subList(index, it).joinToString("") }
                .firstOrNull { it in lexicon }
            if (match == null) {
                unknown.append(clusters[index])
                index += 1
                continue
            }
            if (unknown.isNotEmpty()) {
                result += ResultSegment(unknown.toString(), unknown.toString())
                unknown.clear()
            }
            val candidates = lexicon.getValue(match)
            result += ResultSegment(match, candidates.first(), candidates)
            index += GraphemeClusters.split(match).size
        }
        if (unknown.isNotEmpty()) result += ResultSegment(unknown.toString(), unknown.toString())
        return result
    }

    companion object {
        /** 試験で使う語彙。 */
        val LEXICON: Map<String, List<String>> = mapOf(
            "きょうは" to listOf("今日は", "京は", "きょうは"),
            "てんき" to listOf("天気", "転機", "てんき"),
            "てんきが" to listOf("天気が", "転機が", "てんきが"),
            "いいですね" to listOf("いいですね", "良いですね"),
            "よい" to listOf("良い", "酔い", "よい"),
            "よ" to listOf("世", "よ"),
            "か" to listOf("蚊", "か"),
        )
    }
}

/**
 * コマンドを受けて文字列を組み立てる、試験用のEditor。確定済みの文字列とcompositionを分けて持つ。
 */
class FakeLiveEditor {
    val committed = StringBuilder()
    var composing: String = ""
        private set
    var cursorInComposition: Int = 0
        private set
    val actions = mutableListOf<Int>()

    /** 表示中の全文。 */
    val text: String
        get() = committed.toString() + composing

    /** コアの命令を順に適用する。 */
    fun apply(update: LiveUpdate) {
        for (command in update.commands) {
            when (command) {
                is EditorCommand.SetComposition -> {
                    composing = command.text
                    cursorInComposition = command.cursorUtf16
                }

                is EditorCommand.Commit -> {
                    committed.append(command.text)
                    composing = ""
                    cursorInComposition = 0
                }

                is EditorCommand.PerformEditorAction -> actions += command.actionCode
            }
        }
    }
}

/**
 * ある文を入力し終えるまでの操作数。Phase 2cでライブON/OFFや既存IMEと比べるために数える。
 */
data class OperationCounts(
    /** 文字キー（句読点を含む）と削除キー。 */
    val keys: Int = 0,
    /** 変換を確定するためだけの操作。 */
    val commits: Int = 0,
    /** 過去segmentの選択、候補選択、末尾への復帰、Undo/Redo、カーソル移動。 */
    val corrections: Int = 0,
    /** 改行やSend等、本来の終端操作。確定操作とは分けて数える。 */
    val terminators: Int = 0,
)

/**
 * コアを操作し、変換要求を変換器で即座に処理して結果を返しながら操作数を数える試験用の補助。
 * deliverImmediatelyをfalseにすると要求をpendingへためるため、結果の到着順を試験で決められる。
 */
class LiveSessionDriver(
    val core: LiveConversionCore = LiveConversionCore(),
    val converter: LiveConverter = FakeLiveConverter(FakeLiveConverter.LEXICON),
    var deliverImmediately: Boolean = true,
) {
    val editor = FakeLiveEditor()
    /** deliverImmediatelyがfalseのときにためた、まだ結果を返していない要求。 */
    val pending = mutableListOf<ConversionRequest>()
    val rejections = mutableListOf<RejectReason>()
    var counts = OperationCounts()
        private set

    init {
        if (!core.isActive) core.startField(LiveFieldPolicy.NORMAL)
    }

    /** 文字列を一文字（書記素）ずつ打つ。各文字を一回のキー操作として数える。 */
    fun type(text: String): LiveSessionDriver {
        for (cluster in GraphemeClusters.split(text)) {
            counts = counts.copy(keys = counts.keys + 1)
            handle(core.inputText(cluster))
        }
        return this
    }

    /** 削除キーを一回押す。 */
    fun backspace(): LiveUpdate {
        counts = counts.copy(keys = counts.keys + 1)
        return handle(core.deleteBackward())
    }

    /** 表示中の順番でsegmentを選び、候補バーの対象にする。 */
    fun focus(segmentIndex: Int): LiveUpdate {
        correction()
        return handle(core.focusSegment(core.segments[segmentIndex].id))
    }

    /** 候補バーから表記を選ぶ。 */
    fun select(value: String): LiveUpdate {
        correction()
        val choice = core.candidateBar()!!.choices.first { it.value == value }
        return handle(core.selectCandidate(choice))
    }

    /** 候補バーの対象を末尾入力位置へ戻す。 */
    fun returnToInput(): LiveUpdate {
        correction()
        return handle(core.returnToInputPosition())
    }

    /** Undoを一回行う。 */
    fun undo(): LiveUpdate {
        correction()
        return handle(core.undo())
    }

    /** Redoを一回行う。 */
    fun redo(): LiveUpdate {
        correction()
        return handle(core.redo())
    }

    /** 確定キーを押す。 */
    fun commit(): LiveUpdate {
        counts = counts.copy(commits = counts.commits + 1)
        return handle(core.commitComposition())
    }

    /** Enter（改行またはEditor action）を押す。 */
    fun enter(kind: EnterKind): LiveUpdate {
        counts = counts.copy(terminators = counts.terminators + 1)
        return handle(core.enter(kind))
    }

    /** 保留した要求を変換器へ渡し、結果をコアへ返す。 */
    fun deliver(request: ConversionRequest): LiveUpdate {
        val result = converter.convert(request) ?: return LiveUpdate.NO_CHANGE
        val update = core.onConversionResult(result)
        update.rejection?.let(rejections::add)
        editor.apply(update)
        return update
    }

    /** コアの返り値をEditorへ適用し、要求を処理または保留する。 */
    fun handle(update: LiveUpdate): LiveUpdate {
        editor.apply(update)
        update.rejection?.let(rejections::add)
        val request = update.request ?: return update
        if (deliverImmediately) deliver(request) else pending += request
        return update
    }

    /** 訂正操作を一回数える。 */
    private fun correction() {
        counts = counts.copy(corrections = counts.corrections + 1)
    }
}
