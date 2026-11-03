package dev.uzumi.ime.editor

import android.view.KeyEvent
import dev.uzumi.ime.conversion.ConversionCandidate
import dev.uzumi.ime.conversion.ConversionClient
import dev.uzumi.ime.conversion.ConversionOutcome
import dev.uzumi.ime.conversion.ConversionRequest
import dev.uzumi.ime.conversion.ConversionResult
import dev.uzumi.ime.conversion.ConversionSegment
import dev.uzumi.ime.conversion.LiveConversionClient
import dev.uzumi.ime.conversion.PredictionCandidate
import dev.uzumi.ime.conversion.PredictionClient
import dev.uzumi.ime.conversion.PredictionRequest
import dev.uzumi.ime.conversion.PredictionResult
import dev.uzumi.ime.conversion.SegmentedLiveConverter
import dev.uzumi.ime.conversion.liveLearningUnits
import dev.uzumi.ime.evaluation.ConversionProgress
import dev.uzumi.ime.evaluation.CorrectnessJudge
import dev.uzumi.ime.evaluation.EvaluationCounter
import dev.uzumi.ime.evaluation.LiveChangeClassifier
import dev.uzumi.ime.evaluation.NeuralCounts
import dev.uzumi.ime.live.CandidateChoice
import dev.uzumi.ime.live.CandidateRequest
import dev.uzumi.ime.live.CandidateResult
import dev.uzumi.ime.live.DisplaySpan
import dev.uzumi.ime.live.EditorCommand
import dev.uzumi.ime.live.EnterKind
import dev.uzumi.ime.live.LiveConversionCore
import dev.uzumi.ime.live.LiveConversionRules
import dev.uzumi.ime.live.LiveFieldPolicy
import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.LiveUpdate
import dev.uzumi.ime.live.SegmentState
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
    // Phase 2cの評価用計数。ライブ変換の表示と文節状態の変化を、評価モードの間だけ件数として渡す。
    private val evaluation: EvaluationCounter? = null,
    // Phase 3aの正→誤の遷移の判定。評価モードで課題の許容表記が与えられたときだけ使う。
    private val correctness: CorrectnessJudge? = null,
    // 英字配列のローマ字をかなにするか。欄ごとの可否はInputFieldPolicy.usesRomajiInputで呼び出し側が決める。
    romajiInputEnabled: Boolean = false,
    // 予測要求・結果と候補確定を、変換とは別のMozc sessionへ送る。
    private val predictionClient: PredictionClient? = null,
) {
    private val buffer = CompositionBuffer()
    // compositionの内容が変わるたびに増える番号。変換応答が現在の読みに対するものかを照合する。
    private var revision = 0L
    private var pendingConversion: ConversionRequest? = null
    // 予測も入力世代とsession epochが一致する場合だけ候補へ表示する。
    private var pendingPrediction: PredictionRequest? = null
    private var appliedPrediction: PredictionResult? = null
    private var appliedConversion: ConversionResult? = null
    // 候補の選択で画面用revisionを更新しても、変換エンジンが保持する要求との一致を保つ。
    private var appliedConversionCommitRequest: ConversionRequest? = null
    // 明示変換の注目文節の位置。候補バー、変換キーによる候補の循環、文節の伸縮がこの文節を対象にする。
    private var focusedConversionSegmentIndex = 0
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
    // 編集パネルの範囲選択で、固定端と移動端の向きを保つ。
    private var selectionAnchor: Int? = initialSelectionStart.takeIf { initialSelectionStart >= 0 && initialSelectionEnd >= 0 }
    private var selectionFocus: Int? = initialSelectionEnd.takeIf { initialSelectionStart >= 0 && initialSelectionEnd >= 0 }
    private var compositionStart: Int? = null
    private var expectedComposition: CompositionGeometry? = null
    private val staleCompositions = mutableListOf<CompositionGeometry>()
    // 確定操作の遅延通知を、後から始まったcompositionと区別するために保持する。
    private val pendingCommittedSelections = mutableListOf<SelectionTransition>()

    // 12キーの同じキーの連続タップと、英字配列の入力途中のローマ字。どちらもこの編集セッションの間だけ持つ。
    private val kanaToggle = KanaKeyToggle()
    private val romajiComposer = if (romajiInputEnabled) RomajiKanaComposer() else null

    // ライブ変換で最後にEditorへ送ったカーソル位置（composition先頭からのUTF-16 offset）。
    private var liveCursorUtf16 = 0

    // 自動測定の道具が変換の届き終わりを確かめるための記録。最後に送ったライブ変換の要求と、最後に受けた結果のrevision。
    private var liveRequestedRevision = -1L
    private var liveResultRevision = -1L
    // 候補の取り直しを依頼して、まだ結果を受けていないか。
    private var candidatesOutstanding = false

    // 今の未確定の読みが12キー（かな配列のフリック・トグル）だけで入力されたか。小書き・濁点・半濁点を
    // 区別しない変換はこのときだけ使う（Gboardと同じ）。ライブ変換のコアにも同じ値を渡して要求へ写す。
    private var twelveKeyOnlyComposition = false
        set(value) {
            field = value
            liveCore?.kanaModifierInsensitive = value
        }

    // 変換エンジンが使えない間のライブ変換の結果。部分範囲ごとに読みとカタカナを候補にする。
    private val kanaFallbackConverter = SegmentedLiveConverter(
        convertRange = { chunk, _ ->
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

    /**
     * 変換しない欄（互換入力・パスワード・数値・電話・日時）では、compositionを作らず一文字ずつ確定する。
     * 未確定が残らないため、「完了」「次へ」などのEnterが一回で欄へ届く（標準のキーボードと同じ）。
     */
    private val commitsDirectly: Boolean = policy.isTypeNull || policy.isPassword || policy.isNumeric

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

    /** 編集パネルの左右移動に使う、向きを保ったselectionを返す。 */
    val editPanelSelection: EditPanelSelection?
        get() = if (active && selectionAnchor != null && selectionFocus != null) {
            EditPanelSelection(selectionAnchor!!, selectionFocus!!)
        } else {
            null
        }

    /** Editor上で文字範囲を選択しているかを返す。 */
    val hasEditPanelSelection: Boolean
        get() = editPanelSelection?.hasRange == true

    // 直前の削除内容と復元位置。別操作・位置変更・外部編集の後は使わない。
    private var deletionUndo: DeletionUndo? = null

    /** 変換の進み具合（revisionと真偽だけ）。評価用の受信口が、自動測定の待ち合わせのために読む。 */
    fun conversionProgress(): ConversionProgress = ConversionProgress(
        revision = liveCore?.revision ?: revision,
        conversionPending = pendingConversion != null,
        liveRequestedRevision = liveRequestedRevision,
        liveResultRevision = liveResultRevision,
        candidatesOutstanding = candidatesOutstanding,
    )

    /** 応答を待っている変換要求。UIが時間超過を判定するために使う。 */
    val pendingConversionRequest: ConversionRequest?
        get() = pendingConversion

    /** 変換結果を表示中なら注目文節の変換候補、そうでなければ現在の読みから基本候補を作る。 */
    fun candidateOptions(): List<CandidateOption> {
        if (liveCore != null) return emptyList()
        currentConversion()?.let { conversion ->
            val segmentIndex = focusedConversionSegmentIndex
            val segment = conversion.segments.getOrNull(segmentIndex) ?: return emptyList()
            return segmentCandidates(conversion, segmentIndex).map { candidate ->
                CandidateOption(
                    value = candidate.value,
                    label = "",
                    conversionChoice = ConversionChoice(conversion.request, candidate.id, segmentIndex),
                    selected = candidate.value == segment.value,
                )
            }
        }
        return BasicCandidateProvider.candidates(buffer.reading, policy.suppressSuggestions)
    }

    /** 現在の読みまたは直前の確定文字列に対するMozc予測を依頼し、古い候補を直ちに外す。 */
    fun refreshPredictions(): Boolean {
        val client = predictionClient
        if (!active || client?.isPredictionAvailable != true) {
            clearPredictions()
            return false
        }
        val request = currentPredictionRequest()
        if (request == null) {
            clearPredictions()
            return false
        }
        if (pendingPrediction == request || appliedPrediction?.request == request) return false
        pendingPrediction = request
        appliedPrediction = null
        client.requestPrediction(request)
        return true
    }

    /** 現在の入力状態と世代が一致する予測候補だけを返す。 */
    fun predictionCandidates(): List<PredictionCandidate> {
        return predictionResult()?.candidates.orEmpty()
    }

    /**
     * 現在の入力状態に一致する予測結果を、候補IDの確定操作と一緒に返す。本文に表示中の未確定の表記と、
     * その右に並ぶ通常の候補（注目文節の候補）と同じ表記は、同じ語が候補バーに二度並ばないよう除く。
     */
    fun predictionResult(): PredictionResult? {
        val result = appliedPrediction?.takeIf { isCurrentPredictionRequest(it.request) } ?: return null
        val shown = regularCandidateValues().toMutableSet()
        if (hasComposition) shown += ownedDisplay
        return result.copy(candidates = result.candidates.filterNot { it.value in shown })
    }

    /** 予測の右に並ぶ通常の候補の表記。ライブ変換では注目segmentの候補、それ以外は明示変換・基本候補。 */
    private fun regularCandidateValues(): List<String> =
        liveCandidateState()?.choices?.map { it.value } ?: candidateOptions().map { it.value }

    /** workerの予測結果を要求世代・読み・ゼロクエリ文脈が一致するときだけ反映する。 */
    fun applyPredictionResult(result: PredictionResult): Boolean {
        if (!active || pendingPrediction != result.request || !isCurrentPredictionRequest(result.request)) return false
        pendingPrediction = null
        appliedPrediction = result
        return true
    }

    /** 読みの変更や入力欄の終了後、保留・表示中の予測候補を無効化する。 */
    fun clearPredictions() {
        pendingPrediction = null
        appliedPrediction = null
    }

    /**
     * 予測候補を選び、未確定全体（次の語の予測ではカーソル位置）をその語で確定する。
     * ライブ変換の表示中の文節は確定しないため学習へ送らず、予測語の確定だけを予測の窓口へ伝える。
     */
    fun selectPrediction(request: PredictionRequest, candidateId: Int): Boolean {
        val result = appliedPrediction ?: return false
        if (result.request != request || !isCurrentPredictionRequest(request)) return false
        val candidate = result.candidates.firstOrNull { it.id == candidateId } ?: return false
        // 予測は未確定全体を置き換えるため、入力途中のローマ字と連続タップは確定せずに捨てる。
        abandonPendingInput()
        deletionUndo = null
        if (request.isZeroQuery) {
            val start = knownSelectionStart()
            if (!connection.commitText(candidate.value, 1)) {
                failClosed()
                return false
            }
            updateExternalSelectionAfterCommit(candidate.value, start)
        } else {
            liveCore?.discardComposition()
            liveCursorUtf16 = 0
            if (!commitCompositionAs(candidate.value)) return false
        }
        predictionClient?.commitPrediction(request, candidate.id)
        clearPredictions()
        return true
    }

    /** 現在のcompositionまたは許可された待機文脈から、Mozcへ送る予測要求を作る。 */
    private fun currentPredictionRequest(): PredictionRequest? {
        if (!active || policy.suppressSuggestions || commitsDirectly) return null
        if (hasComposition) {
            val reading = predictionReading() ?: return null
            return PredictionRequest(
                sessionEpoch = sessionEpoch,
                revision = predictionRevision(),
                reading = reading,
                incognito = policy.suppressLearning,
                kanaModifierInsensitive = twelveKeyOnlyComposition,
            )
        }
        // 次の語の予測は確定済みの本文を読むため、学習禁止欄・URLやメールの欄・範囲選択中には読まない。
        if (policy.suppressLearning || policy.isAddressLike || selectionStart == null || selectionStart != selectionEnd) {
            return null
        }
        val preceding = connection.textBeforeCursor(PREDICTION_CONTEXT_MAX_CHARS)?.toString()
            // 上限で切った先頭がサロゲートペアの後半なら、壊れた文字をMozcへ送らないよう落とす。
            ?.let { text -> if (text.firstOrNull()?.isLowSurrogate() == true) text.substring(1) else text }
            ?: return null
        if (preceding.isBlank()) return null
        return PredictionRequest(
            sessionEpoch = sessionEpoch,
            revision = predictionRevision(),
            precedingText = preceding,
        )
    }

    /**
     * 予測へ渡す読み。入力途中のローマ字（「かんk」の「k」）はかなではないためMozcへ送らず、末尾から除く。
     * 保留が末尾に無い（読みの途中へ入力している）場合や、除くと空になる場合は予測しない。
     */
    private fun predictionReading(): String? {
        val reading = liveCore?.reading ?: buffer.reading
        val pending = romajiComposer?.pendingText.orEmpty()
        if (pending.isNotEmpty() && !reading.endsWith(pending)) return null
        return reading.removeSuffix(pending).ifEmpty { null }
    }

    /** 予測要求が今のcomposition・selection・確定文脈に一致するかを確かめる。 */
    private fun isCurrentPredictionRequest(request: PredictionRequest): Boolean =
        request.sessionEpoch == sessionEpoch && currentPredictionRequest() == request

    /** explicit/liveいずれの編集状態からも、現在のUI世代を予測要求へ渡す。 */
    private fun predictionRevision(): Long = liveCore?.revision ?: revision

    /** 明示変換の結果を表示中か。キーボードの←→を、カーソル移動ではなく注目文節の移動へ割り当てるために使う。 */
    val hasExplicitConversion: Boolean
        get() = active && liveCore == null && currentConversion() != null

    /**
     * 文字入力をcompositionへ追加する。接続結果が不明なら同じ本文を再送しない。
     * 12キーの連続タップとローマ字の入力途中は、別の文字が来た時点で区切る。
     * fromTwelveKeyは12キー（かな配列）のフリック入力か。小書き・濁点を区別しない変換を使うかの判定に使う。
     */
    fun inputText(value: String, fromTwelveKey: Boolean = false): Boolean {
        if (!active || value.isEmpty()) return false
        kanaToggle.reset()
        if (!finishRomajiInput()) return false
        return inputTextDirect(value, fromTwelveKey)
    }

    /**
     * 読みへ文字を加える直前に、その入力元を記録する。新しい未確定は入力元で決め、続く入力に12キー以外が
     * 一度でも混ざれば、その未確定では小書き・濁点を区別しない変換を使わない（安全側）。
     */
    private fun noteInputSource(fromTwelveKey: Boolean) {
        twelveKeyOnlyComposition = fromTwelveKey && (!hasComposition || twelveKeyOnlyComposition)
    }

    /**
     * 英字配列から届いた文字列を、ローマ字かな入力が有効な欄ではかなへ組み立てて入れる。無効な欄では[inputText]と同じ。
     * 入力途中のローマ字はcompositionの末尾にそのまま表示し、次のキーで差し替える。
     */
    fun inputRomajiText(value: String): Boolean {
        if (!active || value.isEmpty()) return false
        noteInputSource(fromTwelveKey = false)
        if (romajiComposer == null || value.any { it.code !in ASCII_PRINTABLE }) return inputText(value)
        kanaToggle.reset()
        return value.all(::inputRomajiCharacter)
    }

    /**
     * 12キーのタップ。同じキーを猶予時間内に続けて押すと、直前に入れた一文字を同じ行の次の文字（[values]の次）へ
     * 差し替える。差し替えは未確定の読みの末尾一文字の置き換えとして扱い、変換要求と「元に戻す」は通常の入力と同じ経路を通る。
     */
    fun inputKanaTap(values: List<String>, nowMillis: Long): Boolean {
        if (!active || values.isEmpty()) return false
        if (!finishRomajiInput()) return false
        // 一文字ずつ直接確定する欄には差し替える未確定文字が無いため、従来どおり先頭の文字を入れる。
        if (commitsDirectly) {
            kanaToggle.reset()
            return inputTextDirect(values.first())
        }
        // 明示変換の結果を表示中なら確定してから新しい読みを始める。確定の中で連続タップの状態も区切られる。
        if (liveCore == null && currentConversion() != null && !commitComposition()) return false
        val step = kanaToggle.next(values, nowMillis, compositionInputCursor())
        val previous = step.previous
        val accepted = if (previous == null) {
            inputTextDirect(step.value, fromTwelveKey = true)
        } else {
            noteInputSource(fromTwelveKey = true)
            replaceCompositionTail(previous, step.value)
        }
        if (!accepted) {
            kanaToggle.reset()
            // 末尾が想定と違って差し替えられなかった場合は、タップを失わないよう新しい文字として入れ直す。
            return previous != null && active && inputKanaTap(values, nowMillis)
        }
        kanaToggle.accept(values, step, nowMillis, compositionInputCursor())
        return true
    }

    /**
     * 12キーの連続タップの途中なら区切り、次のタップを新しい文字にする。区切った場合はtrue。
     * →キーをこの用途に使い、連続タップの途中ではカーソルや文節を動かさない（Gboard・iPhoneと同じ）。
     */
    fun endKanaToggle(nowMillis: Long): Boolean {
        if (!active || !hasComposition || !kanaToggle.isActive(nowMillis)) return false
        kanaToggle.reset()
        return true
    }

    /** 確定・変換・カーソル移動などの前に、入力途中のローマ字を確定し（末尾の「n」は「ん」）、連続タップを区切る。 */
    fun finishPendingInput(): Boolean {
        kanaToggle.reset()
        return finishRomajiInput()
    }

    /**
     * 表示を変えずに連続タップとローマ字の保留を捨てる。候補の選択や取り消しは表示中の状態に対して行うため、
     * 先にかなへ確定して状態を変えると、選んだ候補が古いとして拒否されてしまう。
     */
    private fun abandonPendingInput() {
        kanaToggle.reset()
        romajiComposer?.reset()
    }

    /** 外部のカーソル移動や接続の切替の後に、古い末尾を入力途中として扱わないよう状態を捨てる。 */
    private fun resetInputSequences() {
        abandonPendingInput()
    }

    /** この欄で英字配列のローマ字かな入力が有効か。有効な欄では英字配列の自動大文字化を止める。 */
    val isRomajiInputEnabled: Boolean
        get() = romajiComposer != null

    /** 文字入力をcompositionへ追加する共通本体。直接確定の欄では一文字ずつ確定する。 */
    private fun inputTextDirect(value: String, fromTwelveKey: Boolean = false): Boolean {
        if (commitsDirectly) {
            val accepted = connection.commitText(value, 1)
            if (accepted) updateExternalSelectionAfterCommit(value)
            return accepted
        }
        if (liveCore != null) {
            noteInputSource(fromTwelveKey)
            return performLive { it.inputText(value) } ?: false
        }

        // 変換結果の表示中に続けて入力した場合は、表示中の変換を確定してから新しい読みを始める。
        if (currentConversion() != null && !commitComposition()) return false
        noteInputSource(fromTwelveKey)
        if (compositionStart == null && selectionStart != null && selectionEnd != null) {
            compositionStart = minOf(selectionStart!!, selectionEnd!!)
        }
        markCompositionChanged()
        buffer.insert(value)
        if (synchronizeComposition()) return true
        failClosed()
        return false
    }

    /** ローマ字一文字を組み立て器へ渡し、compositionの末尾の入力途中のローマ字を差し替える。 */
    private fun inputRomajiCharacter(char: Char): Boolean {
        val composer = romajiComposer ?: return inputTextDirect(char.toString())
        // 明示変換の結果を表示中なら先に確定する。確定は保留を確定させるため、組み立て器へ渡す前に済ませる。
        if (liveCore == null && currentConversion() != null && !commitComposition()) return false
        if (applyRomajiEdit(composer.input(char))) return true
        composer.reset()
        return false
    }

    /** 入力途中のローマ字をかなへ確定する。差し替えられなくても、セッションが有効なら続く操作は止めない。 */
    private fun finishRomajiInput(): Boolean {
        val edit = romajiComposer?.finish() ?: return true
        return applyRomajiEdit(edit) || active
    }

    /** ローマ字の差し替えを適用する。共通の先頭（保留した「k」の後ろへ「t」を足す場合など）は書き換えない。 */
    private fun applyRomajiEdit(edit: RomajiEdit): Boolean {
        val common = edit.remove.commonPrefixWith(edit.insert).length
        val remove = edit.remove.substring(common)
        val insert = edit.insert.substring(common)
        return when {
            remove.isEmpty() && insert.isEmpty() -> true
            remove.isEmpty() -> inputTextDirect(insert)
            else -> replaceCompositionTail(remove, insert)
        }
    }

    /**
     * 読みの入力位置の直前にある[expected]を[replacement]へ一度の編集として差し替える。末尾が想定と違えば何もしない。
     * ライブ変換では句読点が文節の区切りや確定を意味するため、句読点を含む差し替えは、消してから通常の入力として入れる。
     */
    private fun replaceCompositionTail(expected: String, replacement: String): Boolean {
        if (!active || commitsDirectly || expected.isEmpty()) return false
        deletionUndo = null
        liveCore?.let {
            if (replacement.any { char -> char.toString() in LIVE_PUNCTUATION }) {
                val removed = performLive { core -> core.replaceBeforeCursor(expected, "") } ?: false
                return removed && (performLive { core -> core.inputText(replacement) } ?: false)
            }
            return performLive { core -> core.replaceBeforeCursor(expected, replacement) } ?: false
        }
        if (!buffer.replaceBeforeCursor(expected, replacement)) return false
        markCompositionChanged()
        if (synchronizeComposition()) return true
        failClosed()
        return false
    }

    /** 連続タップの照合に使う、composition上の入力位置（書記素）。未確定文字が無ければ-1。 */
    private fun compositionInputCursor(): Int {
        if (!hasComposition) return -1
        return liveCore?.inputCursor ?: buffer.cursorCluster
    }

    /**
     * 読み末尾または外部カーソル直前の書記素を削除する。連続した削除は一度のUndoにまとめる。
     * 明示変換の結果（エンジンなしで出したカタカナを含む）を表示中なら、文字は消さずに読みの表示へ戻す。
     */
    fun deleteBackward(): Boolean {
        if (!active) return false
        kanaToggle.reset()
        if (liveCore == null && (currentConversion() != null || buffer.display != buffer.reading)) {
            return cancelExplicitConversion()
        }
        val romajiPendingBefore = romajiComposer?.pendingText?.takeIf(String::isNotEmpty)
        if (romajiPendingBefore != null) romajiComposer?.deletePending()
        val previous = activeBackspaceDeletion()
        liveCore?.let { core ->
            if (core.display.isNotEmpty()) {
                if (core.inputCursor == 0) {
                    romajiComposer?.restorePending(romajiPendingBefore.orEmpty())
                    return deleteBeforeComposition()
                }
                if (policy.isSensitive) {
                    val accepted = performLive { it.deleteBackward() } ?: false
                    if (accepted) deletionUndo = null
                    if (!accepted) romajiComposer?.restorePending(romajiPendingBefore.orEmpty())
                    return accepted
                }
                val depthBefore = core.undoDepth
                val accepted = performLive { it.deleteBackward() } ?: false
                if (!accepted) {
                    romajiComposer?.restorePending(romajiPendingBefore.orEmpty())
                    return false
                }
                val addedUndoSteps = (core.undoDepth - depthBefore).coerceAtLeast(0)
                rememberBackspaceDeletion(
                    previous = previous,
                    explicitComposition = previous?.explicitComposition,
                    liveUndoSteps = (previous?.liveUndoSteps ?: 0) + addedUndoSteps,
                    originalCompositionStart = previous?.originalCompositionStart ?: compositionStart,
                    romajiPending = previous?.romajiPending ?: romajiPendingBefore,
                )
                return true
            }
            return deleteOutsideComposition(previous)
        }
        if (!buffer.isEmpty) {
            if (buffer.cursorCluster == 0 && !buffer.hasSelection) return deleteBeforeComposition()
            val snapshot = if (policy.isSensitive) null else previous?.explicitComposition ?: buffer.snapshot()
            val originalStart = previous?.originalCompositionStart ?: compositionStart
            if (!buffer.deleteBackward()) {
                romajiComposer?.restorePending(romajiPendingBefore.orEmpty())
                return false
            }
            markCompositionChanged()
            if (!synchronizeComposition()) {
                failClosed()
                return false
            }
            if (policy.isSensitive) {
                deletionUndo = null
            } else {
                rememberBackspaceDeletion(
                    previous = previous,
                    explicitComposition = snapshot,
                    liveUndoSteps = previous?.liveUndoSteps ?: 0,
                    originalCompositionStart = originalStart,
                    romajiPending = previous?.romajiPending ?: romajiPendingBefore,
                )
            }
            return true
        }
        return deleteOutsideComposition(previous)
    }

    /**
     * 明示変換の表示を取り消して読み全体の表示へ戻す（Mozcの変換キャンセルに当たる）。文字は消さないため削除の記録は作らず、
     * 直前の削除の記録も捨てる。続くBackspaceは読みの一字削除になり、その記録は読みだけを戻す。
     */
    private fun cancelExplicitConversion(): Boolean {
        deletionUndo = null
        buffer.setDisplay(buffer.reading)
        markCompositionChanged()
        if (synchronizeComposition()) return true
        failClosed()
        return false
    }

    /**
     * 未確定文字の先頭にカーソルがあるときのBackspace。compositionは残し、その直前の確定済みの一書記素を消す。
     * Editorではcomposing spanが削除幅だけ左へずれるため、開始位置・選択位置・期待する選択通知を同じだけずらす。
     * 未確定を残したまま前の確定文字だけを戻す経路はないため、「元に戻す」の記録は捨てる。
     */
    private fun deleteBeforeComposition(): Boolean {
        deletionUndo = null
        val beforeCursor = connection.textBeforeCursor(64)?.toString() ?: return false
        val deleteLength = GraphemeClusters.previousUtf16Length(beforeCursor)
        if (deleteLength == 0) return false
        if (!connection.deleteSurroundingText(deleteLength, 0)) return false
        val shiftedStart = compositionStart?.let { (it - deleteLength).coerceAtLeast(0) }
        compositionStart = shiftedStart
        setKnownSelection(shiftedStart, shiftedStart)
        recordExpectedComposition(CompositionGeometry(shiftedStart, ownedDisplay.length, cursorOffset = 0))
        return true
    }

    /**
     * 削除キーの左ドラッグで消える文字数（書記素）を返す。入力中なら未確定の表示全体、それ以外はカーソルから行頭まで。
     * 範囲選択中や周辺文字列を読めない場合はnull。
     */
    fun lineDeleteLength(): Int? {
        if (!active) return null
        if (hasComposition) return GraphemeClusters.split(ownedDisplay).size
        if (selectionStart != null && selectionStart != selectionEnd) return null
        val before = connection.textBeforeCursor(LINE_DELETE_MAX_CHARS)?.toString() ?: return null
        return GraphemeClusters.split(lineDeleteTarget(before)).size
    }

    /**
     * カーソルから同じ行の行頭までを一度に消す（Simejiの削除キーの左ドラッグ）。入力中なら未確定の読みだけを消す。
     * 行頭にいる場合は直前の改行一つを消す。消した文字列は、機密欄・学習禁止欄を除いて「元に戻す」のために覚える。
     */
    fun deleteToLineStart(): Boolean {
        if (!finishPendingInput()) return false
        deletionUndo = null
        if (!active) return false
        if (hasComposition) {
            val reading = liveCore?.segments?.joinToString("") { it.reading } ?: buffer.reading
            if (!clearComposition()) return false
            rememberLineDeletion(reading, fromComposition = true)
            return true
        }
        if (selectionStart != null && selectionEnd != null && selectionStart != selectionEnd) {
            return deleteOutsideComposition(previous = null)
        }
        val before = connection.textBeforeCursor(LINE_DELETE_MAX_CHARS)?.toString() ?: return false
        val target = lineDeleteTarget(before)
        if (target.isEmpty()) return false
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        if (!connection.deleteSurroundingText(target.length, 0)) return false
        // hostの選択通知より先に次の操作が来ても、消した後のカーソル位置を使う。
        if (oldStart != null && oldStart == oldEnd) {
            val cursor = (oldStart - target.length).coerceAtLeast(0)
            recordExpectedExternalSelection(oldStart, oldEnd, cursor)
        }
        rememberLineDeletion(target, fromComposition = false)
        return true
    }

    /** 直前の削除を戻せるか。候補バーの「元に戻す」の表示に使う。 */
    val canUndoDeletion: Boolean
        get() = active && deletionUndo != null

    /**
     * 直前に消した文字列を戻す。前後32文字とカーソル位置が削除直後のままの場合だけ復元する。
     * 外部選択や別の編集が入っていれば記録を捨て、利用者の別の文字列を壊さない。
     */
    fun undoDeletion(): Boolean {
        kanaToggle.reset()
        val record = deletionUndo ?: return false
        deletionUndo = null
        if (!active || !matchesDeletionLocation(record)) return false

        if (record.kind == DeletionUndoKind.LINE_START) {
            romajiComposer?.reset()
            if (hasComposition) return false
            if (record.fromComposition) return inputText(record.lineText)
            return restoreExternalText(record.lineText)
        }

        if (record.removedExternal.isNotEmpty()) {
            if (hasComposition) return false
            if ((record.explicitComposition != null || record.liveUndoSteps > 0) && !connection.finishComposingText()) {
                failClosed()
                return false
            }
            if (!restoreExternalText(record.removedExternal)) return false
        }
        record.explicitComposition?.let { snapshot ->
            if (liveCore != null) return false
            buffer.restore(snapshot)
            compositionStart = if (record.removedExternal.isEmpty()) {
                record.originalCompositionStart
            } else {
                knownSelectionStart()
            }
            markCompositionChanged()
            if (!synchronizeComposition()) {
                failClosed()
                return false
            }
            romajiComposer?.restorePending(record.romajiPending.orEmpty())
            return true
        }
        if (record.liveUndoSteps > 0) {
            val core = liveCore ?: return false
            if (!core.isActive || !core.isLiveEnabled || core.undoDepth < record.liveUndoSteps) return false
            val restored = performLive { live ->
                var update = LiveUpdate.NOT_HANDLED
                repeat(record.liveUndoSteps) { update = live.undo() }
                update
            } ?: false
            if (restored) romajiComposer?.restorePending(record.romajiPending.orEmpty())
            return restored
        }
        return record.removedExternal.isNotEmpty()
    }

    /** 削除のUndo記録を捨てる。削除以外の操作をしたときに呼ぶ。 */
    fun forgetDeletionUndo() {
        deletionUndo = null
    }

    /** 行頭削除で消した本文と削除直後の位置を記録する。機密欄・学習禁止欄では本文を保持しない。 */
    private fun rememberLineDeletion(removed: String, fromComposition: Boolean) {
        if (policy.isSensitive || removed.isEmpty()) return
        val location = captureDeletionLocation() ?: return
        deletionUndo = DeletionUndo(
            kind = DeletionUndoKind.LINE_START,
            lineText = removed,
            fromComposition = fromComposition,
            before = location.before,
            after = location.after,
            cursor = location.cursor,
            composition = location.composition,
        )
    }

    /** 同じ連続Backspaceで削除した本文と、削除前のcomposition状態を保持する。 */
    private fun rememberBackspaceDeletion(
        previous: DeletionUndo?,
        explicitComposition: CompositionSnapshot?,
        liveUndoSteps: Int,
        originalCompositionStart: Int?,
        removedExternal: String = previous?.removedExternal.orEmpty(),
        romajiPending: String? = previous?.romajiPending,
    ) {
        if (policy.isSensitive) {
            deletionUndo = null
            return
        }
        val location = captureDeletionLocation() ?: run {
            deletionUndo = null
            return
        }
        deletionUndo = DeletionUndo(
            kind = DeletionUndoKind.BACKSPACE,
            removedExternal = removedExternal,
            explicitComposition = explicitComposition,
            liveUndoSteps = liveUndoSteps,
            originalCompositionStart = originalCompositionStart,
            romajiPending = romajiPending,
            before = location.before,
            after = location.after,
            cursor = location.cursor,
            composition = location.composition,
        )
    }

    /** 同じ入力位置で続いたBackspaceなら、既存の削除グループを返す。 */
    private fun activeBackspaceDeletion(): DeletionUndo? {
        val record = deletionUndo?.takeIf { it.kind == DeletionUndoKind.BACKSPACE } ?: return null
        return record.takeIf(::matchesDeletionLocation)
    }

    /** 現在の周辺文字列・カーソル・compositionが記録した削除直後の状態と一致するかを確かめる。 */
    private fun matchesDeletionLocation(record: DeletionUndo): Boolean {
        val location = captureDeletionLocation() ?: return false
        return location.before == record.before &&
            location.after == record.after &&
            location.cursor == record.cursor &&
            location.composition == record.composition
    }

    /** Undoの照合に使う削除直後の状態を読む。値が足りなければ復元記録を作らない。 */
    private fun captureDeletionLocation(): DeletionLocation? {
        val start = selectionStart ?: return null
        val end = selectionEnd ?: return null
        if (start != end) return null
        val before = connection.textBeforeCursor(DELETION_CONTEXT_CHARS)?.toString() ?: return null
        val after = connection.textAfterCursor(DELETION_CONTEXT_CHARS)?.toString() ?: return null
        return DeletionLocation(
            before = before,
            after = after,
            cursor = start,
            composition = currentCompositionGeometry(),
        )
    }

    /** compositionの範囲と表示上のカーソルを、Editorの通知照合と同じ座標で表す。 */
    private fun currentCompositionGeometry(): CompositionGeometry? {
        if (!hasComposition) return null
        val cursorOffset = when {
            liveCore != null -> liveCursorUtf16
            buffer.display == buffer.reading -> buffer.cursorUtf16Offset()
            else -> buffer.display.length
        }
        return CompositionGeometry(compositionStart, ownedDisplay.length, cursorOffset)
    }

    /** 自分が送った削除の通知は残し、別のselection移動や選択範囲はUndo対象から外す。 */
    private fun invalidateDeletionUndoUnlessSelectionMatches(
        start: Int,
        end: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        val record = deletionUndo ?: return
        val matchesCursor = start >= 0 && start == end && start == record.cursor
        val matchesComposition = when (val geometry = record.composition) {
            null -> candidatesStart < 0 || candidatesEnd < candidatesStart
            else -> candidatesStart >= 0 && geometry.matches(candidatesStart, candidatesEnd, start)
        }
        if (!matchesCursor || !matchesComposition) deletionUndo = null
    }

    /** 遅れて届くEditorの選択通知を、削除操作が送った選択遷移として照合する。 */
    private fun recordExpectedExternalSelection(oldStart: Int, oldEnd: Int, cursor: Int) {
        recordCommittedSelection(SelectionTransition(oldStart, oldEnd, cursor, cursor))
        setKnownSelection(cursor, cursor)
    }

    /** compositionの後ろに削除した確定本文を、削除直後のカーソル位置へ戻す。 */
    private fun restoreExternalText(text: String): Boolean {
        if (text.isEmpty()) return true
        if (!connection.commitText(text, 1)) {
            failClosed()
            return false
        }
        updateExternalSelectionAfterCommit(text)
        return true
    }

    /** Editorへの非同期結果による表示更新後も、Undoの位置を最新の状態へ合わせる。 */
    private fun refreshDeletionLocation() {
        val record = deletionUndo ?: return
        val location = captureDeletionLocation() ?: run {
            deletionUndo = null
            return
        }
        deletionUndo = record.copy(
            before = location.before,
            after = location.after,
            cursor = location.cursor,
            composition = location.composition,
        )
    }

    /** Editor上の未確定の表示を消し、内部のcompositionも捨てる。書込みに失敗したら接続を閉じる。 */
    private fun clearComposition(): Boolean {
        // 消した後のカーソルはcompositionの先頭になる
        val start = compositionStart
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        val oldGeometry = expectedComposition
        if (!connection.setComposingText("", 1)) {
            failClosed()
            return false
        }
        discardCompositionWithoutEditorCall()
        oldGeometry?.let(::rememberStaleComposition)
        connection.finishComposingText()
        if (start != null) {
            recordExpectedExternalSelection(oldStart ?: start, oldEnd ?: start, start)
        }
        return true
    }

    /** compositionが無いときの削除。選択範囲、またはカーソル直前の一書記素を消す。 */
    private fun deleteOutsideComposition(previous: DeletionUndo?): Boolean {
        // 互換入力欄（端末アプリなど）は周辺文字列を返さないことがあり、削除はキーイベントでしか届かない。
        // 標準のキーボードと同じくDeleteキーを送り、消える本文が分からないため「元に戻す」は記録しない。
        if (policy.isTypeNull) {
            deletionUndo = null
            return connection.sendDeleteKey()
        }
        if (selectionStart != null && selectionEnd != null && selectionStart != selectionEnd) {
            val selected = if (policy.isSensitive) null else connection.selectedText()?.toString()
            val accepted = connection.commitText("", 1)
            if (accepted) {
                updateExternalSelectionAfterCommit("")
                if (selected.isNullOrEmpty()) {
                    deletionUndo = null
                } else {
                    rememberBackspaceDeletion(
                        previous = null,
                        explicitComposition = null,
                        liveUndoSteps = 0,
                        originalCompositionStart = null,
                        removedExternal = selected,
                    )
                }
            }
            return accepted
        }

        val beforeCursor = connection.textBeforeCursor(64)?.toString() ?: return false
        val deleteLength = GraphemeClusters.previousUtf16Length(beforeCursor)
        if (deleteLength == 0) return false
        val removed = beforeCursor.substring(beforeCursor.length - deleteLength)
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        if (!connection.deleteSurroundingText(deleteLength, 0)) return false
        if (oldStart != null && oldStart == oldEnd) {
            val cursor = (oldStart - deleteLength).coerceAtLeast(0)
            recordExpectedExternalSelection(oldStart, oldEnd, cursor)
        }
        if (policy.isSensitive) {
            deletionUndo = null
        } else {
            rememberBackspaceDeletion(
                previous = previous,
                explicitComposition = previous?.explicitComposition,
                liveUndoSteps = previous?.liveUndoSteps ?: 0,
                originalCompositionStart = previous?.originalCompositionStart,
                removedExternal = removed + previous?.removedExternal.orEmpty(),
            )
        }
        return true
    }

    /**
     * composition内または外部テキスト上で書記素単位にカーソルを動かす。
     */
    fun moveCursor(delta: Int): Boolean {
        if (!active || delta == 0) return false
        if (!finishPendingInput()) return false
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
                setKnownSelection(target, target)
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

    /** 編集パネルの左右キーを処理し、選択中は固定端を保ったまま移動端を伸縮する。 */
    fun moveEditPanelHorizontally(direction: Int, extendSelection: Boolean): Boolean {
        if (!active || (direction != -1 && direction != 1) || !commitComposition()) return false
        if (policy.isSensitive) return sendHorizontalEditKey(direction, extendSelection)
        refreshSelectionFromEditor()
        val current = editPanelSelection ?: return false
        val adjacentLength = if (!extendSelection && current.hasRange) {
            0
        } else {
            adjacentGraphemeLength(current, direction, extendSelection)
                ?: return sendHorizontalEditKey(direction, extendSelection)
        }
        val next = EditPanelRules.moveHorizontally(current, direction, extendSelection, adjacentLength)
        if (next == current) return false
        if (!connection.setSelection(next.anchor, next.focus)) return false
        setKnownSelection(next.anchor, next.focus)
        deletionUndo = null
        clearPredictions()
        return true
    }

    /** ↑↓やHome/Endなどの標準キー操作を、未確定を確定してからEditorへ送る。 */
    fun sendEditPanelKeyStroke(keyCode: Int, metaState: Int = 0): Boolean {
        if (!active || !commitComposition()) return false
        deletionUndo = null
        clearPredictions()
        val accepted = connection.sendKeyStroke(keyCode, metaState)
        if (accepted) refreshSelectionFromEditor()
        return accepted
    }

    /** Android標準の全選択・コピー・切り取り・貼り付けを実行し、選択位置を同期する。 */
    fun performEditPanelContextAction(action: EditPanelContextAction): Boolean {
        if (!active || (policy.isPassword && action in setOf(EditPanelContextAction.COPY, EditPanelContextAction.CUT))) {
            return false
        }
        if (!commitComposition()) return false
        if (action == EditPanelContextAction.COPY || action == EditPanelContextAction.CUT) {
            refreshSelectionFromEditor()
            if (!hasEditPanelSelection) return false
        }
        resetInputSequences()
        clearPredictions()
        if (action != EditPanelContextAction.COPY) deletionUndo = null
        val actionId = when (action) {
            EditPanelContextAction.SELECT_ALL -> android.R.id.selectAll
            EditPanelContextAction.COPY -> android.R.id.copy
            EditPanelContextAction.CUT -> android.R.id.cut
            EditPanelContextAction.PASTE -> android.R.id.paste
        }
        val accepted = connection.performContextMenuAction(actionId)
        if (accepted) refreshSelectionFromEditor()
        return accepted
    }

    /** 編集パネルの削除を既存Backspace経路へ渡し、選択範囲の復元記録を維持する。 */
    fun deleteFromEditPanel(): Boolean {
        if (!active || !commitComposition()) return false
        refreshSelectionFromEditor()
        return deleteBackward()
    }

    /** 機密欄では本文を読まず、標準DPADキーへ修飾状態を付けて左右移動する。 */
    private fun sendHorizontalEditKey(direction: Int, extendSelection: Boolean): Boolean {
        deletionUndo = null
        clearPredictions()
        val keyCode = if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val metaState = if (extendSelection) KeyEvent.META_SHIFT_ON else 0
        return connection.sendKeyStroke(keyCode, metaState)
    }

    /** 現在のfocusに隣接する一書記素のUTF-16幅をEditorから得る。 */
    private fun adjacentGraphemeLength(
        selection: EditPanelSelection,
        direction: Int,
        extendSelection: Boolean,
    ): Int? {
        if (selection.hasRange && extendSelection) {
            val movingIntoSelection =
                (selection.focus > selection.anchor && direction < 0) ||
                    (selection.focus < selection.anchor && direction > 0)
            if (movingIntoSelection) {
                val selected = connection.selectedText()?.toString() ?: return null
                val clusters = GraphemeClusters.split(selected)
                return if (selection.focus > selection.anchor) {
                    clusters.lastOrNull()?.length ?: 0
                } else {
                    clusters.firstOrNull()?.length ?: 0
                }
            }
        }
        val context = if (direction < 0) {
            connection.textBeforeCursor(64)?.toString()
        } else {
            connection.textAfterCursor(64)?.toString()
        } ?: return null
        val clusters = GraphemeClusters.split(context)
        return if (direction < 0) clusters.lastOrNull()?.length ?: 0 else clusters.firstOrNull()?.length ?: 0
    }

    /** Editorから選択indexだけを取り直し、本文はcomposition・学習・ログへ渡さない。 */
    fun refreshSelectionFromEditor(): Boolean {
        if (!active || policy.isSensitive) return false
        val current = connection.currentSelection() ?: return false
        if (current.first < 0 || current.second < 0) return false
        if (editPanelSelection != EditPanelSelection(current.first, current.second)) {
            deletionUndo = null
            clearPredictions()
        }
        setKnownSelection(current.first, current.second)
        return true
    }

    /**
     * compositionが無いときのカーソル移動。範囲選択中はAndroid標準（Selection.moveLeft/moveRight）と同じく
     * ←で選択の開始、→で終了へ畳む。選択が無いときだけ、周辺文字列の書記素幅だけ外部selectionを動かす。
     */
    private fun moveOutsideComposition(delta: Int): Boolean {
        val selectedStart = selectionStart
        val selectedEnd = selectionEnd
        if (selectedStart != null && selectedEnd != null && selectedStart != selectedEnd) {
            val collapsed = if (delta < 0) selectedStart else selectedEnd
            val accepted = connection.setSelection(collapsed, collapsed)
            if (accepted) {
                setKnownSelection(collapsed, collapsed)
            }
            return accepted
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
            setKnownSelection(target, target)
        }
        return accepted
    }

    /**
     * 読みを確定し、エディタ側のcompositionを消す。
     */
    fun commitComposition(): Boolean {
        if (!active) return false
        if (!finishPendingInput()) return false
        liveCore?.let { core ->
            if (core.display.isEmpty()) return true
            return performLive { it.commitComposition() } ?: false
        }
        if (buffer.isEmpty) return true
        val conversion = currentConversion()
        val commitRequest = appliedConversionCommitRequest ?: conversion?.request
        if (!commitCompositionAs(buffer.display)) return false
        // 登録語・学習語の文節を学習から除くか、エンジンへ送るかはworkerが文節ごとに判定する。
        // ここで除くと、同じ確定で利用者が選んだ他の文節の学習まで失われる。
        if (conversion != null && commitRequest != null) {
            conversionClient?.commitAll(commitRequest, conversion.segments)
        }
        return true
    }

    /** 未確定の入力を先に確定し、貼り付け文字列を変換・学習へ渡さずそのまま入力する。 */
    fun pasteText(text: String): Boolean {
        if (!active || text.isEmpty()) return false
        if (!finishPendingInput()) return false
        if (hasComposition && !commitComposition()) return false
        deletionUndo = null
        val start = knownSelectionStart()
        if (!connection.commitText(text, 1)) {
            failClosed()
            return false
        }
        updateExternalSelectionAfterCommit(text, start)
        return true
    }

    /**
     * 表示中の候補を注目文節だけへ反映する。composition全体は未確定のまま保ち、確定はEnterや次の入力で行う。
     * 表示元の変換や注目文節が今と違う古い候補なら拒否する。
     */
    fun selectConversionCandidate(choice: ConversionChoice): Boolean {
        if (!active) return false
        abandonPendingInput()
        val conversion = currentConversion() ?: return false
        if (conversion.request != choice.request || choice.segmentIndex != focusedConversionSegmentIndex) return false
        val segment = conversion.segments.getOrNull(choice.segmentIndex) ?: return false
        val candidate = segmentCandidates(conversion, choice.segmentIndex)
            .firstOrNull { it.id == choice.candidateId } ?: return false
        deletionUndo = null
        val updated = segment.copy(
            value = candidate.value,
            fromUserDictionary = candidate.fromUserDictionary,
            chosen = true,
            learnedReading = candidate.learnedReading,
        )
        return updateExplicitConversionSegment(conversion, choice.segmentIndex, updated)
    }

    /**
     * workerから届いた変換応答を、待機中の要求・revision・読みと一致する場合だけ反映する。
     * 失敗応答や不整合な結果では読みの表示を保ち、かな・カナ候補へ戻す。
     * 文節を伸縮した応答では、伸縮した文節より前で利用者が選んだ候補を引き継ぎ、伸縮した文節を候補バーの対象に保つ。
     */
    fun applyConversionOutcome(outcome: ConversionOutcome): Boolean {
        val pending = pendingConversion ?: return false
        if (!active || outcome.request != pending) return false
        pendingConversion = null
        if (pending.revision != revision || pending.reading != buffer.reading) return false
        val converted = (outcome as? ConversionOutcome.Converted)?.result?.takeIf(ConversionResult::isConsistent)
        if (converted == null) {
            failedConversionRevision = revision
            return false
        }
        val result = keepChosenPrecedingSegments(converted, previous = currentConversion())
        val focus = pending.focusedSegmentIndex.coerceAtMost(result.segments.lastIndex)
        buffer.setDisplay(result.display)
        if (!synchronizeComposition(result, focus)) {
            failClosed()
            return false
        }
        appliedConversion = result
        appliedConversionCommitRequest = result.request
        focusedConversionSegmentIndex = focus
        return true
    }

    /**
     * 伸縮の応答で、伸縮した文節より前にあり読みが変わらない文節のうち、利用者が候補を選んだものを前の表示のまま残す。
     * Mozcは伸縮した文節より前の区切りを保つため、同じ位置の文節は同じ読みになる。選んでいない文節はエンジンの表記を使う。
     */
    private fun keepChosenPrecedingSegments(result: ConversionResult, previous: ConversionResult?): ConversionResult {
        if (previous == null || result.request.segmentLengths == null) return result
        val segments = result.segments.mapIndexed { index, segment ->
            val before = previous.segments.getOrNull(index)
            if (index < result.request.focusedSegmentIndex && before != null && before.chosen && before.reading == segment.reading) {
                before
            } else {
                segment
            }
        }
        return result.copy(segments = segments)
    }

    /** 時間超過した要求を取り下げ、遅れて届いた応答を適用しないようにする。 */
    fun abandonConversion(request: ConversionRequest): Boolean {
        if (pendingConversion != request) return false
        pendingConversion = null
        failedConversionRevision = revision
        return true
    }

    /**
     * 明示変換の候補バーの対象（注目文節）を前後へ一つ移し、Editor上の強調も付け直す。
     * 両端より外へは移らず、Editorの実カーソルも動かさない。応答待ちの伸縮は取り下げる。
     */
    fun moveConversionFocus(delta: Int): Boolean {
        if (!active || delta == 0) return false
        val conversion = currentConversion() ?: return false
        val target = focusedConversionSegmentIndex + if (delta < 0) -1 else 1
        if (target !in conversion.segments.indices) return false
        pendingConversion = null
        focusedConversionSegmentIndex = target
        if (synchronizeComposition(conversion, target)) return true
        failClosed()
        return false
    }

    /**
     * 候補を選んだ文節を変換結果へ反映し、表示を更新する。表示元の照合のためにUI用のrevisionを進めるが、
     * 確定時にエンジンへ渡す要求は、エンジンが変換状態を持つ元の要求のまま保つ。
     */
    private fun updateExplicitConversionSegment(
        conversion: ConversionResult,
        segmentIndex: Int,
        segment: ConversionSegment,
    ): Boolean {
        val commitRequest = appliedConversionCommitRequest ?: conversion.request
        val segments = conversion.segments.toMutableList().also { it[segmentIndex] = segment }
        val nextRevision = revision + 1
        val updated = conversion.copy(
            request = conversion.request.copy(revision = nextRevision),
            segments = segments,
        )
        buffer.setDisplay(updated.display)
        revision = nextRevision
        pendingConversion = null
        failedConversionRevision = null
        if (!synchronizeComposition(updated, segmentIndex)) {
            failClosed()
            return false
        }
        appliedConversion = updated
        appliedConversionCommitRequest = commitRequest
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
        abandonPendingInput()
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
        if (!finishPendingInput()) return false
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
        if (!finishPendingInput()) return false
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
        if (!finishPendingInput()) return false
        if (liveCore != null) return selectNextLiveCandidate()
        if (currentConversion() != null) return selectNextExplicitCandidate()
        if (active && !buffer.isEmpty && pendingConversion != null) return true
        val client = conversionClient
        if (active && !buffer.isEmpty && client != null && client.isAvailable &&
            !policy.suppressSuggestions && failedConversionRevision != revision
        ) {
            val request = ConversionRequest(
                sessionEpoch = sessionEpoch,
                revision = revision,
                reading = buffer.reading,
                incognito = policy.suppressLearning,
                kanaModifierInsensitive = twelveKeyOnlyComposition,
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
     * 未確定文字があればEnterは確定だけを行い、改行や送信は次のEnterで行う。
     * 未確定文字がなければ改行またはeditor actionへ振り分ける。
     */
    fun handleEnter(): Boolean {
        if (!finishPendingInput()) return false
        if (hasComposition) return commitComposition()
        if (EditorActionPolicy.shouldSendEnterKey(policy)) return active && connection.sendEnterKey()
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
            if (hasComposingSpan) {
                val staleIndex = staleCompositions.indexOfFirst {
                    it.matches(candidatesStart, candidatesEnd, newSelectionStart)
                }
                if (staleIndex >= 0) {
                    staleCompositions.removeAt(staleIndex)
                    return
                }
            }
            invalidateDeletionUndoUnlessSelectionMatches(newSelectionStart, newSelectionEnd, candidatesStart, candidatesEnd)
            resetInputSequences()
            compositionStart = null
            clearCompositionExpectations()
            pendingCommittedSelections.clear()
            updateKnownSelection(newSelectionStart, newSelectionEnd)
            return
        }

        if (!hasComposingSpan) {
            resetInputSequences()
            deletionUndo = null
            // hostがspanを終了済みなら、旧readingを次の入力で再挿入しない。
            discardCompositionWithoutEditorCall()
            updateKnownSelection(newSelectionStart, newSelectionEnd)
            return
        }

        if (newSelectionStart < 0 || newSelectionEnd < 0 || newSelectionStart != newSelectionEnd) {
            resetInputSequences()
            deletionUndo = null
            finishAndDiscardComposition(newSelectionStart, newSelectionEnd)
            return
        }

        val expected = expectedComposition
        if (expected?.matches(candidatesStart, candidatesEnd, newSelectionStart) == true) {
            invalidateDeletionUndoUnlessSelectionMatches(newSelectionStart, newSelectionEnd, candidatesStart, candidatesEnd)
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
        if (sameCurrentSpan) {
            invalidateDeletionUndoUnlessSelectionMatches(newSelectionStart, newSelectionEnd, candidatesStart, candidatesEnd)
        }
        if (sameCurrentSpan && acceptCompositionSelection(candidatesStart, newSelectionStart)) {
            resetInputSequences()
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
        resetInputSequences()
        deletionUndo = null
        finishAndDiscardComposition(newSelectionStart, newSelectionEnd)
    }

    /**
     * セッションを無効化し、内部compositionだけを捨てる。
     * 旧InputConnectionへfinishやcommitを送らないことが切替時の安全条件である。
     * 同じ欄の再開始で未確定を確定する場合は[closeForRestart]を使う。
     */
    fun close() {
        if (!active) return
        resetInputSequences()
        active = false
        clearPredictions()
        deletionUndo = null
        buffer.clear()
        liveCore?.finishField()
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        pendingCommittedSelections.clear()
        connection.invalidate()
    }

    /**
     * 同じ入力欄への再開始（画面回転など）で、Editorに残る未確定の表示をその表記のまま確定してから閉じる。
     * Editorのcomposing spanには表示中の変換がそのまま入っているため、finishComposingTextだけで表示文字列が確定する。
     * 送り先は再開始後に有効な接続[restartedConnection]で、無効化済みのこのセッションの接続には送らない。
     * commitTextは使わない。Editorが旧接続を閉じた時点でspanを確定済みの場合に、同じ本文を二重に入れるため。
     */
    fun closeForRestart(restartedConnection: EditorConnectionPort) {
        resetInputSequences()
        if (hasComposition) restartedConnection.finishComposingText()
        close()
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
            focusedReading = focused?.reading,
        )
    }

    /** ライブ変換の候補を選ぶ。表示時と状態が変わっていれば拒否する。対象segmentはchosenになる。 */
    fun selectLiveCandidate(choice: CandidateChoice): Boolean {
        abandonPendingInput()
        return performLive { it.selectCandidate(choice) } ?: false
    }

    /**
     * 候補バーの対象を前（direction<0）または次のsegmentへ移す。読点は飛ばし、最後のsegmentの次は
     * 末尾入力位置へ戻す。入力カーソルは動かさないため、訂正後に末尾から入力を続けられる。
     */
    fun moveLiveFocus(direction: Int): Boolean {
        val core = liveCore ?: return false
        if (!active || direction == 0) return false
        if (!finishPendingInput()) return false
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
        if (!update.handled) return false
        update.candidateRequest?.let(::dispatchCandidateRequest)
        return refreshLiveHighlight()
    }

    /**
     * 候補バーの対象segmentの候補を、明示変換と同じ方法で取り直すよう依頼する。候補一覧を開いたときに呼ぶ。
     * 取り直し済み、または依頼先が使えなければ何もしない。結果はapplyLiveCandidatesで後から反映する。
     */
    fun requestLiveCandidates(): Boolean {
        val core = liveCore ?: return false
        if (!active) return false
        val update = core.requestFocusedCandidates()
        update.candidateRequest?.let(::dispatchCandidateRequest)
        return update.handled
    }

    /** workerから届いた取り直しの候補を、コアが要求時と同じ状態だと照合できた場合だけ反映する。 */
    fun applyLiveCandidates(result: CandidateResult): Boolean {
        val core = liveCore ?: return false
        if (!active) return false
        candidatesOutstanding = false
        val update = core.onCandidateResult(result)
        return update.handled && update.rejection == null
    }

    /**
     * 文節の区切りを伸縮できる状態か。ライブ変換では入力中、明示変換では変換結果を表示している間。
     * キーボードは、この間だけ←→の長押しを伸縮に使う。
     */
    val canResizeSegment: Boolean
        get() = active && if (liveCore != null) hasComposition else currentConversion() != null

    /**
     * 文節の区切りを一書記素縮める（delta<0）か伸ばす（delta>0）。ライブ変換と明示変換の両方で、
     * 候補バーが注目している文節を対象にする。伸縮できなければfalse。
     */
    fun resizeSegment(delta: Int): Boolean {
        if (!active || delta == 0) return false
        if (!finishPendingInput()) return false
        if (liveCore != null) return performLive { it.resizeFocusedSegment(delta) } ?: false
        return resizeConversionSegment(delta)
    }

    /** 候補バーの対象を末尾入力位置のsegmentへ戻す。 */
    fun returnLiveFocusToInput(): Boolean {
        val core = liveCore ?: return false
        if (!active) return false
        if (!finishPendingInput()) return false
        return core.returnToInputPosition().handled && refreshLiveHighlight()
    }

    /** ライブ変換の直前の操作（候補の誤選択など）を取り消す。取り消せる操作が無ければfalse。 */
    fun undoLive(): Boolean {
        abandonPendingInput()
        return performLive { it.undo() } ?: false
    }

    /** workerから届いたライブ変換の結果を、コアが現在の状態と照合できた場合だけ表示へ反映する。 */
    fun applyLiveResult(result: LiveResult): Boolean {
        val core = liveCore ?: return false
        if (!active) return false
        liveResultRevision = result.identity.revision
        val before = core.segments
        val update = core.onConversionResult(result)
        val rejection = update.rejection
        if (rejection != null && LiveChangeClassifier.isStale(rejection)) {
            evaluation?.recordLive(LiveChangeClassifier.STALE_RESULT_DISCARDED)
        }
        if (rejection != null || update.commands.isEmpty()) return false
        // 正→誤の遷移の判定に使う、compositionより前の欄の文字列。評価モードで許容表記があるときだけ読む。
        val judge = correctness?.takeIf { it.isActive && evaluation?.isRecording == true }
        val prefix = if (judge != null) textBeforeLiveComposition() else null
        val applied = applyLiveUpdate(update, core.segments)
        if (applied && evaluation?.isRecording == true) {
            evaluation.recordLive(LiveChangeClassifier.resultApplied(before, core.segments))
            if (prefix != null && judge?.isCorrectToWrong(prefix, before, core.segments) == true) {
                evaluation.recordNeural(NeuralCounts(correctToWrong = 1))
            }
        }
        return applied
    }

    /**
     * ライブ変換のcompositionより前にある欄の文字列を返す。カーソルより前の文字列から、composition内のカーソルより
     * 前の部分を除いて求める。評価用の判定にだけ使い、読めなければ空文字列にする。
     */
    private fun textBeforeLiveComposition(): String {
        val before = connection.textBeforeCursor(EVALUATION_PREFIX_MAX_CHARS)?.toString() ?: return ""
        return before.dropLast(liveCursorUtf16.coerceAtMost(before.length))
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
        if (evaluation?.isRecording == true) {
            evaluation.recordLive(LiveChangeClassifier.userOperation(before, core.segments))
        }
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
        update.candidateRequest?.let(::dispatchCandidateRequest)
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
        liveRequestedRevision = request.identity.revision
        val client = liveClient
        if (client != null && client.isAvailable) {
            client.requestLiveConversion(sessionEpoch, request)
            return
        }
        kanaFallbackConverter.convert(request)?.let(::applyLiveResult)
    }

    /** 候補の取り直しを依頼する。エンジンが使えない間は、かな・カナ候補のほかに取り直す候補が無いため送らない。 */
    private fun dispatchCandidateRequest(request: CandidateRequest) {
        val client = liveClient ?: return
        if (!client.isAvailable) return
        candidatesOutstanding = true
        client.requestSegmentCandidates(sessionEpoch, request)
    }

    /**
     * 明示変換の表示中に、注目文節の区切りを一書記素伸縮した変換をエンジンへ依頼する。
     * 伸縮を続けて押した場合は、応答を待っている要求の区切りから数える。結果は通常の変換応答として届く。
     */
    private fun resizeConversionSegment(delta: Int): Boolean {
        val conversion = currentConversion() ?: return false
        val client = conversionClient ?: return false
        if (!client.isAvailable || policy.suppressSuggestions) return false
        val focus = focusedConversionSegmentIndex
        val segment = conversion.segments.getOrNull(focus) ?: return false
        // 注目文節より前の区切りは、表示中の変換のまま保つようエンジンへ渡す。
        val precedingLengths = conversion.segments.take(focus).map { GraphemeClusters.split(it.reading).size }
        val remainingLength = GraphemeClusters.split(buffer.reading).size - precedingLengths.sum()
        val current = pendingConversion
            ?.takeIf { it.segmentLengths != null && it.focusedSegmentIndex == focus }
            ?.segmentLengths?.last()
            ?: GraphemeClusters.split(segment.reading).size
        val length = current + if (delta < 0) -1 else 1
        if (length < 1 || length > remainingLength) return false
        val request = ConversionRequest(
            sessionEpoch = sessionEpoch,
            revision = revision,
            reading = buffer.reading,
            incognito = policy.suppressLearning,
            segmentLengths = precedingLengths + length,
            kanaModifierInsensitive = twelveKeyOnlyComposition,
        )
        pendingConversion = request
        client.requestConversion(request)
        return true
    }

    /** 確定したsegment列をエンジンへ学習させる。学習禁止欄とエンジンが使えない場合は送らない。 */
    private fun learnCommitted(segments: List<LiveSegment>) {
        if (policy.suppressLearning) return
        val client = liveClient ?: return
        if (!client.isAvailable) return
        val units = liveLearningUnits(segments)
        if (units.isNotEmpty()) client.learnCommitted(sessionEpoch, units, twelveKeyOnlyComposition)
    }

    /** 候補バーの対象にできるsegmentか。読点だけのsegmentは訂正の対象にしない。 */
    private fun isFocusTarget(segment: LiveSegment): Boolean = segment.reading != LiveConversionRules.SOFT_BOUNDARY

    /**
     * 明示変換の表示中に変換キーを押したとき、注目文節で表示中の次の候補を選ぶ（確定はしない）。最後の次は先頭へ戻る。
     */
    private fun selectNextExplicitCandidate(): Boolean {
        val conversion = currentConversion() ?: return false
        val index = focusedConversionSegmentIndex
        val segment = conversion.segments.getOrNull(index) ?: return false
        val candidates = segmentCandidates(conversion, index)
        if (candidates.isEmpty()) return false
        val current = candidates.indexOfFirst { it.value == segment.value }
        val next = candidates[(current + 1) % candidates.size]
        if (next.value == segment.value) return true
        return selectConversionCandidate(ConversionChoice(conversion.request, next.id, index))
    }

    /** 明示変換のindex番目の文節の候補。エンジンが文節ごとの候補を返さなかった先頭文節は、先頭文節の候補を使う。 */
    private fun segmentCandidates(conversion: ConversionResult, index: Int): List<ConversionCandidate> {
        val segment = conversion.segments.getOrNull(index) ?: return emptyList()
        return segment.candidates.ifEmpty { if (index == 0) conversion.headCandidates else emptyList() }
    }

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
        setKnownSelection(cursor, cursor)
        if (offset == liveCursorUtf16) return true
        val index = core.clusterIndexForDisplayOffset(offset)
        if (index != null) {
            liveCursorUtf16 = offset
            return performLive { it.moveCursorTo(index) } ?: false
        }
        val span = core.displaySpans().firstOrNull { offset > it.start && offset < it.end } ?: return false
        val update = core.focusSegment(span.segmentId)
        if (!update.handled) return false
        update.candidateRequest?.let(::dispatchCandidateRequest)
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
        appliedConversionCommitRequest = null
        focusedConversionSegmentIndex = 0
    }

    /** 表示中の変換結果が現在のrevisionと読みに一致する場合だけ返す。 */
    private fun currentConversion(): ConversionResult? {
        val conversion = appliedConversion ?: return null
        if (conversion.request.revision != revision || conversion.request.reading != buffer.reading) return null
        return conversion
    }

    /**
     * 明示変換のcompositionをEditorへ送る。未変換なら読みのカーソル、変換表示中なら末尾へカーソルを置く。
     * 複数文節の変換結果を渡すと、focusIndexの文節（候補バーの対象）を強調する。
     */
    private fun synchronizeComposition(conversion: ConversionResult? = null, focusIndex: Int = 0): Boolean {
        val cursorOffset = if (buffer.display == buffer.reading) {
            buffer.cursorUtf16Offset()
        } else {
            buffer.display.length
        }
        return writeComposition(styleExplicitComposition(conversion, focusIndex), cursorOffset)
    }

    /** 複数文節の明示変換を表示中なら、注目文節の表示範囲を強調した文字列を返す。ライブ変換と同じ強調を使う。 */
    private fun styleExplicitComposition(conversion: ConversionResult?, focusIndex: Int): CharSequence {
        val text = buffer.display
        if (conversion == null || conversion.segments.size < 2 || conversion.display != text) return text
        val segment = conversion.segments.getOrNull(focusIndex) ?: return text
        val start = conversion.segments.take(focusIndex).sumOf { it.value.length }
        val state = if (segment.chosen) SegmentState.CHOSEN else SegmentState.PROVISIONAL
        val span = DisplaySpan(focusIndex.toLong(), start, start + segment.value.length, state, converted = true, focused = true)
        return compositionStyler(text, span)
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
            val oldGeometry = expectedComposition
            clearCompositionExpectations()
            oldGeometry?.let(::rememberStaleComposition)
            setKnownSelection(start, start)
            refreshDeletionLocation()
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
            setKnownSelection(null, null)
            refreshDeletionLocation()
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
            setKnownSelection(target, target)
            if (target != end) {
                recordExpectedComposition(
                    CompositionGeometry(
                        start = start,
                        displayLength = text.length,
                        cursorOffset = cursorOffset,
                    ),
                )
            }
            refreshDeletionLocation()
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
            setKnownSelection(null, null)
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
        setKnownSelection(destination, destination)
    }

    /** 現在のselectionが既知なら、正規化済みの開始位置を返す。 */
    private fun knownSelectionStart(): Int? {
        val start = selectionStart ?: return null
        val end = selectionEnd ?: return null
        return minOf(start, end)
    }

    /** 不明値を0へ変換せず、既知のselectionだけを正規化して保存する。 */
    private fun updateKnownSelection(start: Int, end: Int) {
        setKnownSelection(start, end)
    }

    /** selectionの向きと正規化範囲を同時に保存し、不明値は不明のまま保つ。 */
    private fun setKnownSelection(anchor: Int?, focus: Int?) {
        if (anchor == null || focus == null || anchor < 0 || focus < 0) {
            selectionAnchor = null
            selectionFocus = null
            selectionStart = null
            selectionEnd = null
            return
        }
        selectionAnchor = anchor
        selectionFocus = focus
        selectionStart = minOf(anchor, focus)
        selectionEnd = maxOf(anchor, focus)
    }

    /** 現在送信したcompositionを保持し、一つ前までの通知を遅延判定へ残す。 */
    private fun recordExpectedComposition(geometry: CompositionGeometry) {
        expectedComposition?.takeIf { it != geometry }?.let(::rememberStaleComposition)
        expectedComposition = geometry
    }

    /** compositionを消した後も、先に送った選択通知を遅延判定できるよう残す。 */
    private fun rememberStaleComposition(geometry: CompositionGeometry) {
        staleCompositions += geometry
        while (staleCompositions.size > MAX_STALE_COMPOSITIONS) {
            staleCompositions.removeAt(0)
        }
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
        setKnownSelection(cursor, cursor)
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

    /** 遅延した確定通知なら、連続する自己遷移をまとめて消費する。 */
    private fun consumeCommittedSelection(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
    ): Boolean {
        for (endIndex in pendingCommittedSelections.indices) {
            val end = pendingCommittedSelections[endIndex]
            if (!end.endsAt(newStart, newEnd)) continue
            for (startIndex in 0..endIndex) {
                if (!pendingCommittedSelections[startIndex].startsAt(oldStart, oldEnd)) continue
                val isContinuous = (startIndex until endIndex).all { index ->
                    pendingCommittedSelections[index].endsAt(
                        pendingCommittedSelections[index + 1].oldStart,
                        pendingCommittedSelections[index + 1].oldEnd,
                    )
                }
                if (!isContinuous) continue
                repeat(endIndex + 1) { pendingCommittedSelections.removeAt(0) }
                return true
            }
        }
        return false
    }

    /** 接続結果が不明な場合に状態を破棄し、同じ本文を再送できなくする。 */
    private fun failClosed() {
        active = false
        deletionUndo = null
        buffer.clear()
        liveCore?.finishField()
        markCompositionChanged()
        compositionStart = null
        clearCompositionExpectations()
        pendingCommittedSelections.clear()
        connection.invalidate()
    }

    private companion object {
        /** 左ドラッグで行頭を探すときに読む、カーソル前の最大文字数。これより長い行はこの長さだけ消す。 */
        const val LINE_DELETE_MAX_CHARS = 1_000

        /** 次の語を予測するMozcへ渡す確定文脈の最大UTF-16長。本文全体を送らないために制限する。 */
        private const val PREDICTION_CONTEXT_MAX_CHARS = 64

        /** 正→誤の遷移の判定で読む、カーソル前の最大文字数。課題文（長文でも50文字未満）が収まる長さにする。 */
        const val EVALUATION_PREFIX_MAX_CHARS = 200

        /** 「元に戻す」の前に一致を確かめる、カーソルの前後の文字数。 */
        const val DELETION_CONTEXT_CHARS = 32

        /**
         * 遅延通知の照合に残す過去composition数の上限。ライブ変換では一打鍵で読みの表示と変換結果の表示の
         * 2回（カーソルが末尾以外なら位置の指定を加えて最大4回）書き込むため、明示変換で使っていた8では
         * 2〜4打鍵分の遅れしか照合できない。照合できない遅延通知は外部の変更とみなしてcompositionを
         * 確定・破棄するので、速い連続入力でも誤って確定しないよう32（8打鍵以上）にする。
         */
        const val MAX_STALE_COMPOSITIONS = 32

        /** ローマ字として組み立てる文字の範囲（ASCIIの印字可能文字）。これ以外を含む文字列はそのまま入れる。 */
        val ASCII_PRINTABLE = 0x21..0x7e

        /** ライブ変換で区切りや確定を意味する句読点。差し替えではなく通常の入力として入れる。 */
        val LIVE_PUNCTUATION = LiveConversionRules.TERMINAL_PUNCTUATION + LiveConversionRules.SOFT_BOUNDARY
    }
}

/** 確定操作の前後で期待するselection範囲を表す。 */
private data class SelectionTransition(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val newEnd: Int,
) {
    /** hostのselectionがこの遷移の開始範囲と一致するかを返す。 */
    fun startsAt(start: Int, end: Int): Boolean {
        if (oldStart < 0 || oldEnd < 0 || start < 0 || end < 0) return false
        return oldStart == minOf(start, end) && oldEnd == maxOf(start, end)
    }

    /** hostのselectionがこの遷移の終了範囲と一致するかを返す。 */
    fun endsAt(start: Int, end: Int): Boolean {
        if (newStart < 0 || newEnd < 0 || start < 0 || end < 0) return false
        return newStart == minOf(start, end) && newEnd == maxOf(start, end)
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

/** 一回分のBackspace連続削除、または行頭削除を戻す記録。本文は機密欄では保持しない。 */
private data class DeletionUndo(
    val kind: DeletionUndoKind,
    val removedExternal: String = "",
    val lineText: String = "",
    val explicitComposition: CompositionSnapshot? = null,
    val liveUndoSteps: Int = 0,
    val originalCompositionStart: Int? = null,
    val cursor: Int,
    val before: String,
    val after: String,
    val composition: CompositionGeometry?,
    // ローマ字の保留表示をUndoした後、同じ未確定入力として続けるための状態。
    val romajiPending: String? = null,
    // 行頭までの削除で未確定読みを消した場合は、戻すときに読みを再入力する。
    val fromComposition: Boolean = false,
)

private enum class DeletionUndoKind {
    BACKSPACE,
    LINE_START,
}

/** Undoできる削除直後の本文・selection位置を表す。 */
private data class DeletionLocation(
    val before: String,
    val after: String,
    val cursor: Int,
    val composition: CompositionGeometry?,
)

/**
 * カーソル前の文字列[before]から、左ドラッグで消す範囲を返す。同じ行の行頭までとし、行頭にいる場合は直前の改行一つとする。
 */
internal fun lineDeleteTarget(before: String): String {
    if (before.isEmpty()) return ""
    if (before.endsWith("\n")) return "\n"
    return before.substring(before.lastIndexOf('\n') + 1)
}
