package dev.uzumi.ime.conversion

import dev.uzumi.ime.dictionary.UserDictionaryLookup
import dev.uzumi.ime.evaluation.LiveRequestReport
import dev.uzumi.ime.learning.LearningStore
import dev.uzumi.ime.live.CandidateRequest
import dev.uzumi.ime.live.CandidateResult
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import dev.uzumi.ime.live.ConversionResult as LiveResult
import dev.uzumi.ime.live.ResultSegment
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 編集セッションが変換エンジンへ出す非同期要求。呼び出し側は結果を待たずに戻る。
 */
interface ConversionClient {
    /** 辞書の読み込みと確認が済み、変換を依頼できるか。falseなら既存のかな・カナ候補だけを使う。 */
    val isAvailable: Boolean

    /** 変換を依頼する。結果はworkerの配送経路で後から届く。 */
    fun requestConversion(request: ConversionRequest)

    /**
     * requestの変換結果を、segmentsの区切りと表記で確定したことを伝える。
     * segmentsは候補選択を反映した表示中の文節で、ユーザーが選んだ文節にはchosenが付く。
     */
    fun commitAll(request: ConversionRequest, segments: List<ConversionSegment>)
}

/**
 * 編集セッションがライブ変換の要求と確定時の学習をエンジンへ出す非同期窓口。呼び出し側は結果を待たない。
 * sessionEpochは編集セッションの世代で、エンジン側sessionの対応付けと終了判定に使う。
 */
interface LiveConversionClient {
    /** 辞書の読み込みと確認が済み、変換を依頼できるか。 */
    val isAvailable: Boolean

    /** ライブ変換を依頼する。未処理の古い要求は捨てられ、最新の要求だけが変換される。 */
    fun requestLiveConversion(sessionEpoch: Long, request: LiveRequest)

    /**
     * ライブ変換で確定したsegment列を、単位ごとにエンジンへ学習させる。学習禁止欄では呼ばない。
     * kanaModifierInsensitiveは確定した読みが12キーだけで入力されたかで、変換時と同じ条件で表記を再現するために使う。
     */
    fun learnCommitted(sessionEpoch: Long, units: List<List<LearnedSegment>>, kanaModifierInsensitive: Boolean = false)

    /** 注目したsegmentの候補の取り直しを依頼する。未処理の古い要求は捨てられ、最新の要求だけを処理する。 */
    fun requestSegmentCandidates(sessionEpoch: Long, request: CandidateRequest)
}

/** 入力途中とゼロクエリの予測を非同期で取得し、選択候補の確定を通知する窓口。 */
interface PredictionClient {
    /** Mozcの辞書とnative初期化が完了しているか。 */
    val isPredictionAvailable: Boolean

    /** 最新の予測要求をキューへ積み、結果を後から返す。 */
    fun requestPrediction(request: PredictionRequest)

    /** 表示した予測候補を選んだことを、対応するMozc sessionへ伝える。 */
    fun commitPrediction(request: PredictionRequest, candidateId: Int)
}

/**
 * 変換エンジンを一つの直列executorだけから呼ぶ窓口。
 * globalなSessionHandlerへの同時呼び出しを防ぎ、UI threadをJNI呼び出しで止めない。
 * Editor sessionの世代（sessionEpoch）ごとにエンジン側のsessionを作成・破棄する。
 */
class ConversionWorker(
    private val engine: ConversionEngine,
    // 単一threadで順番に実行するexecutor。複数threadのexecutorを渡してはいけない。
    private val executor: Executor,
    // 初期化結果の通知。worker threadから呼ばれるため、UIへはpostして反映する。
    private val onHealthChanged: (EngineHealth) -> Unit,
    // 変換応答の通知。worker threadから呼ばれるため、UIへはpostして反映する。
    private val onOutcome: (ConversionOutcome) -> Unit,
    // ライブ変換の結果の通知（編集セッションの世代、結果）。worker threadから呼ばれる。失敗時は通知しない。
    private val onLiveResult: (Long, LiveResult) -> Unit = { _, _ -> },
    // 取り直した候補の通知（編集セッションの世代、結果）。worker threadから呼ばれる。失敗時は通知しない。
    private val onCandidateResult: (Long, CandidateResult) -> Unit = { _, _ -> },
    // ユーザー辞書を返す。初回はファイルを読むため、worker threadからだけ呼ぶ。
    private val userDictionary: () -> UserDictionaryLookup? = { null },
    // IME側の学習キャッシュを返す。初回はファイルを読むため、worker threadからだけ呼ぶ。nullなら学習を使わない。
    private val learningStore: () -> LearningStore? = { null },
    // 評価モードで、ライブ変換の要求ごとの評価用の報告を受け取る（編集セッションの世代、報告）。worker threadから呼ばれる。
    private val onLiveReport: (Long, LiveRequestReport) -> Unit = { _, _ -> },
    // 評価モードか。trueの間だけ、評価用の報告を作る（Mozcを直接呼ぶ照合が増えるため）。
    private val evaluationActive: () -> Boolean = { false },
    // 単調な時計（ナノ秒）。時間超過の期限と、適用までの時間の起点に使う。JVMテストで差し替える。
    private val nanoTime: () -> Long = System::nanoTime,
    // 予測応答はsessionEpochとrevisionを保ったままUIへ返す。
    private val onPredictionResult: (PredictionResult) -> Unit = {},
) : ConversionClient, LiveConversionClient, PredictionClient {
    @Volatile
    private var currentHealth: EngineHealth = EngineHealth.Loading

    // 未処理の変換要求のうち最新の一件。古い要求は処理前に上書きされて捨てられる。
    private val latestRequest = AtomicReference<ConversionRequest?>(null)

    // 未処理のライブ変換要求のうち最新の一件と、その編集セッションの世代・受理時刻・通し番号。
    private val latestLiveRequest = AtomicReference<PendingLive?>(null)

    // ライブ変換要求の通し番号。ニューラル変換の中断で、どの要求の推論を止めるかの照合に使う。
    private val liveSequence = AtomicLong(0)

    // ニューラル変換の窓口。nullならMozcだけでライブ変換する（製品の既定と評価条件M）。UIスレッドから切り替える。
    @Volatile
    private var neuralBackend: NeuralBackend? = null

    // 未処理の候補の取り直し要求のうち最新の一件と、その編集セッションの世代。
    private val latestCandidateRequest = AtomicReference<Pair<Long, CandidateRequest>?>(null)

    // 未処理の予測要求のうち最新の一件。古い読みや文脈の結果を抑える。
    private val latestPredictionRequest = AtomicReference<PredictionRequest?>(null)

    // この値以下のsessionEpochは終了済み。キューに残った要求もエンジンへ送らない。
    private val endedThroughEpoch = AtomicLong(Long.MIN_VALUE)

    // 以下はworker threadだけが読み書きする。
    private val engineSessions = mutableMapOf<Long, Long>()
    // 予測要求は通常変換・ライブ変換のpreeditや履歴を変えない専用sessionで処理する。
    private val predictionSessions = mutableMapOf<Long, Long>()
    // sessionEpochごとに、候補IDを確定できる最新の予測要求と候補一覧を保持する。
    private val lastPredictionRequests = mutableMapOf<Long, PredictionRequest>()
    private val lastPredictionCandidates = mutableMapOf<Long, List<PredictionCandidate>>()
    // sessionEpochごとに、エンジン側sessionの状態を作った直前の要求。学習通知の照合に使う。
    private val lastConverted = mutableMapOf<Long, ConversionRequest>()

    /** 最新の初期化状態。UI threadから読んでよい。 */
    val health: EngineHealth
        get() = currentHealth

    override val isAvailable: Boolean
        get() = currentHealth is EngineHealth.Ready

    override val isPredictionAvailable: Boolean
        get() = isAvailable

    /** エンジンの読み込みと既知の変換例による確認をworkerで始める。 */
    fun start() {
        executor.execute {
            val loaded = runCatching { engine.load() }
                .getOrElse { EngineHealth.Unavailable(EngineUnavailableReason.INITIALIZATION_FAILED) }
            val verified = if (loaded is EngineHealth.Ready && !convertsKnownExample()) {
                EngineHealth.Unavailable(EngineUnavailableReason.MINIMAL_ENGINE)
            } else {
                loaded
            }
            currentHealth = verified
            onHealthChanged(verified)
            // 初回の読込みはファイルを読むため、UI threadで初めて開かないようここで先に開く。
            lookupUserDictionary()
            // 設定画面の「学習履歴を消す」で、Mozcの学習もこのworker経由で消せるようにする。
            lookupLearningStore()?.setEngineClearer(::requestEngineLearningClear)
        }
    }

    /**
     * エンジンの学習の消去をworkerのキューへ積む。エンジンがReadyでない、またはworkerが止まっていて積めなければ
     * falseを返し、呼び出し側（学習キャッシュ）がエンジンの保存ファイルを直接消す。
     */
    private fun requestEngineLearningClear(): Boolean {
        if (currentHealth !is EngineHealth.Ready) return false
        return try {
            executor.execute { runCatching { engine.clearLearning() } }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun requestLiveConversion(sessionEpoch: Long, request: LiveRequest) {
        val sequence = liveSequence.incrementAndGet()
        latestLiveRequest.set(PendingLive(sessionEpoch, request, nanoTime(), sequence))
        // 前の要求の推論が続いていれば止める。その結果は古く、コアが捨てるためである。
        neuralBackend?.cancelInFlight(sequence)
        executor.execute(::drainLatestLiveRequest)
    }

    /**
     * ライブ変換に使うニューラル変換の窓口を切り替える。nullならMozcだけに戻す。UIスレッドから、入力欄の開始時に呼ぶ。
     * 切り替えの前後の結果は、コアの`converterGeneration`（モデルごとに異なる）で区別する。
     */
    fun setNeuralBackend(backend: NeuralBackend?) {
        neuralBackend = backend
    }

    override fun requestSegmentCandidates(sessionEpoch: Long, request: CandidateRequest) {
        latestCandidateRequest.set(sessionEpoch to request)
        executor.execute(::drainLatestCandidateRequest)
    }

    /** 入力途中またはゼロクエリの最新予測だけをworkerへ送る。 */
    override fun requestPrediction(request: PredictionRequest) {
        latestPredictionRequest.set(request)
        executor.execute(::drainLatestPredictionRequest)
    }

    /**
     * 対応する予測要求がまだ最新なら、選択した候補をIME側の学習へ記録し、
     * Mozcの予測sessionへも確定して履歴を学習させる。
     */
    override fun commitPrediction(request: PredictionRequest, candidateId: Int) {
        if (request.incognito || isEnded(request.sessionEpoch)) return
        executor.execute {
            if (lastPredictionRequests[request.sessionEpoch] != request) return@execute
            val candidate = lastPredictionCandidates[request.sessionEpoch].orEmpty()
                .firstOrNull { it.id == candidateId } ?: return@execute
            candidate.reading?.let { reading -> lookupLearningStore()?.record(reading, candidate.value) }
            val sessionId = predictionSessions[request.sessionEpoch] ?: return@execute
            val insensitive = kanaModifierInsensitive(request.kanaModifierInsensitive)
            if (!runCatching { engine.setIncognito(false, insensitive) }.getOrDefault(false)) return@execute
            runCatching { engine.commitPrediction(sessionId, candidateId) }
            lastPredictionRequests.remove(request.sessionEpoch)
            lastPredictionCandidates.remove(request.sessionEpoch)
        }
    }

    /**
     * 終了判定は呼び出し時点で行う。Send等で確定直後に入力欄が閉じても、同じ世代のsession破棄より先に
     * キューへ積むため、確定した内容を学習できる。
     */
    override fun learnCommitted(sessionEpoch: Long, units: List<List<LearnedSegment>>, kanaModifierInsensitive: Boolean) {
        if (units.isEmpty() || isEnded(sessionEpoch)) return
        executor.execute {
            val sessionId = engineSessions[sessionEpoch] ?: return@execute
            // 表示のまま確定したsegmentと、同じ単位の先頭からの句をIME側の学習にも記録する。
            lookupLearningStore()?.let { store -> recordCommittedUnits(store, units) }
            val insensitive = kanaModifierInsensitive(kanaModifierInsensitive)
            if (!runCatching { engine.setIncognito(false, insensitive) }.getOrDefault(false)) return@execute
            lastConverted.remove(sessionEpoch)
            for (unit in units) {
                val learned = runCatching { engine.learnSegments(sessionId, unit) }.getOrDefault(false)
                // まとめて合わせられない単位（登録語を含む等）は、segmentごとに学習し直す。
                if (!learned && unit.size > 1) {
                    unit.forEach { segment -> runCatching { engine.learnSegments(sessionId, listOf(segment)) } }
                }
            }
        }
    }

    /**
     * ライブ変換で確定した単位を学習キャッシュへ記録する。対象外の表記（ひらがなだけ、ASCIIだけ、50文字超）は記録の規則が除く。
     * ユーザー辞書の登録語を表示したsegmentは記録しない。辞書から消した後も学習から表示され続けるのを防ぐ。
     * 2segment以上の単位では、先頭からの累積句（1〜2番目、1〜3番目…、最後は単位の読み全体）も句として記録する。
     * 句は登録語のsegmentを含むところで打ち切る。登録語を含む句も、辞書から消した後に登録語を表示し続けるためである。
     * 顔文字・絵文字のsegmentでも句を打ち切る。句の表記は顔文字と判定されないことがあり、次回の表示値になるためである。
     * 顔文字・絵文字のsegment自体は、候補の順位を上げる回数のために一語として記録する。
     * 新しい句は、ユーザーが候補を選んだsegmentを含む場合だけ作る。選ばずに確定した句はエンジンの結果の再現なので、
     * 覚えても表示は変わらず上限を埋めるだけだからである。既に覚えている句は、選ばなくても時刻と回数を更新する。
     */
    private fun recordCommittedUnits(store: LearningStore, units: List<List<LearnedSegment>>) {
        val dictionary = lookupUserDictionary()
        // 読みと表記がユーザー辞書の登録語と一致するsegmentか。
        fun registered(segment: LearnedSegment): Boolean =
            dictionary?.exactMatches(segment.reading)?.any { it.surface == segment.surface } == true
        val emoticons = EmoticonJudge(dictionary, learningStore = null)
        for (unit in units) {
            unit.filterNot(::registered).forEach { store.record(it.reading, it.surface) }
            // 登録語と顔文字・絵文字のsegmentより前だけを句にする。
            val phraseSource = unit.takeWhile { !registered(it) && !emoticons.isEmoticon(it.reading, it.surface) }
            for (end in 2..phraseSource.size) {
                val phrase = phraseSource.subList(0, end)
                store.recordPhrase(
                    reading = phrase.joinToString(separator = "") { it.reading },
                    surface = phrase.joinToString(separator = "") { it.surface },
                    createIfMissing = phrase.any { it.chosen },
                )
            }
        }
    }

    override fun requestConversion(request: ConversionRequest) {
        latestRequest.set(request)
        executor.execute(::drainLatestRequest)
    }

    /**
     * 明示変換の確定を、ユーザーが候補を選んだ文節だけIME側の学習へ記録する。エンジンへは、選んだ文節が無ければ
     * 変換したまま確定させ、あれば同じ区切りと表記を再現して学習させる。登録語・学習語だけの表記を含む場合は
     * エンジンが表記を再現できないため送らない。学習禁止欄では何も記録しない。
     */
    override fun commitAll(request: ConversionRequest, segments: List<ConversionSegment>) {
        if (request.incognito) return
        executor.execute {
            val sessionId = sessionForCommit(request) ?: return@execute
            val chosen = segments.filter { it.chosen && !it.fromUserDictionary }
            lookupLearningStore()?.let { store ->
                chosen.forEach { store.record(it.learnedReading ?: it.reading, it.value) }
            }
            if (segments.all(ConversionSegment::engineCandidate)) {
                if (chosen.isEmpty()) {
                    runCatching { engine.commitAll(sessionId) }
                } else {
                    val learned = segments.map { LearnedSegment(it.reading, it.value, chosen = it.chosen) }
                    runCatching { engine.learnSegments(sessionId, learned) }
                }
            }
            lastConverted.remove(request.sessionEpoch)
        }
    }

    /**
     * Editor sessionの終了を記録し、対応するエンジン側sessionを破棄する。
     * 記録は呼び出し時点で行うため、既にキューにある同じ世代の要求はエンジンへ届かない。
     */
    fun endSession(sessionEpoch: Long) {
        endedThroughEpoch.accumulateAndGet(sessionEpoch, ::maxOf)
        executor.execute {
            lastConverted.remove(sessionEpoch)
            lastPredictionRequests.remove(sessionEpoch)
            lastPredictionCandidates.remove(sessionEpoch)
            // 学習は打鍵ごとではなく、入力欄の終了時にまとめて保存する。
            lookupLearningStore()?.flush()
            val sessionId = engineSessions.remove(sessionEpoch)
            if (sessionId != null) runCatching { engine.deleteSession(sessionId) }
            predictionSessions.remove(sessionEpoch)?.let { runCatching { engine.deleteSession(it) } }
        }
    }

    /** 最新の要求だけを取り出して変換する。先に処理された後続タスクは何もしない。 */
    private fun drainLatestRequest() {
        val request = latestRequest.getAndSet(null) ?: return
        if (isEnded(request.sessionEpoch)) return
        if (currentHealth !is EngineHealth.Ready) {
            onOutcome(ConversionOutcome.Failed(request))
            return
        }
        val sessionId = sessionFor(request.sessionEpoch)
        // 学習と履歴の方針は、読みをエンジンへ送る前に必ず指定する。
        val insensitive = kanaModifierInsensitive(request.kanaModifierInsensitive)
        if (sessionId == null || !runCatching { engine.setIncognito(request.incognito, insensitive) }.getOrDefault(false)) {
            onOutcome(ConversionOutcome.Failed(request))
            return
        }
        lastConverted.remove(request.sessionEpoch)
        val conversion = runCatching { engine.convert(sessionId, request.reading, request.segmentLengths) }.getOrNull()
        if (conversion == null || conversion.segments.isEmpty()) {
            onOutcome(ConversionOutcome.Failed(request))
            return
        }
        lastConverted[request.sessionEpoch] = request
        val segments = mergeExplicitSegments(request, conversion)
        val result = ConversionResult(request, segments, segments.first().candidates)
        onOutcome(
            if (result.isConsistent) ConversionOutcome.Converted(result) else ConversionOutcome.Failed(request),
        )
    }

    /**
     * 明示変換の各文節へ、候補の統合規則（並びは「ユーザー辞書 → 学習 → エンジン」、顔文字・絵文字は後ろ）を適用する。
     * 各文節の候補はその文節の読みだけを置き換えるため、候補を選んでも他の文節は変わらない。
     * 読み全体に一致する登録語があり、利用者が区切りを指定していなければ、読み全体を一文節にまとめて登録語を表示する。
     * 登録語が無く読み全体に一致する学習語があれば、同じく一文節にまとめる。読みが完全に一致する学習語は
     * ライブ変換と同じく最初の表示にする。評価の記録中は比較条件を変えないため、学習語を表示に使わない。
     */
    private fun mergeExplicitSegments(request: ConversionRequest, conversion: EngineConversion): List<ConversionSegment> {
        val store = lookupLearningStore().takeUnless { request.incognito }
        val dictionary = lookupUserDictionary()
        val emoticons = emoticonJudge(learningAllowed = !request.incognito)
        val displayStore = store.takeUnless { evaluationActive() }
        val source = wholeReadingSegment(request, conversion, dictionary)?.let(::listOf)
            ?: wholeLearnedSegment(request, conversion, displayStore, emoticons)?.let(::listOf)
            ?: conversion.segments.mapIndexed { index, segment ->
                // 先頭文節の候補はheadCandidatesでも受け取れる。読みが文節と同じものだけを使う。
                val engineCandidates = segment.candidates.ifEmpty {
                    if (index == 0) conversion.headCandidates.filter { it.reading == segment.reading } else emptyList()
                }
                segment.copy(candidates = engineCandidates)
            }
        return source.map { segment ->
            val engineCandidates = segment.candidates.ifEmpty {
                listOf(ConversionCandidate(ConversionCandidate.DISPLAYED_VALUE_ID, segment.value, segment.reading))
            }
            val single = EngineConversion(segments = listOf(segment), headCandidates = engineCandidates)
            // 表示に使う学習語。エンジンの表示と違う表記を確定するため、文節はエンジンの学習から外す（learnedReading）。
            val shown = displayStore?.let { learnedDisplay(segment.reading, it, emoticons) }
                ?.takeIf { it != segment.value }
                ?.let { segment.copy(value = it, learnedReading = segment.reading) }
                ?: segment
            val learned = LearnedCandidates.mergeExplicit(segment.reading, single, store).let { merged ->
                // 前方一致の予測は入力した読み全体を置き換える候補なので、複数文節の途中の文節には出さない。
                if (source.size == 1) {
                    merged
                } else {
                    merged.copy(headCandidates = merged.headCandidates.filter { it.learnedReading in setOf(null, segment.reading) })
                }
            }.copy(segments = listOf(shown))
            val withUser = UserDictionaryCandidates.mergeExplicit(segment.reading, learned, dictionary)
            val arranged = EmoticonCandidates.arrangeExplicit(withUser, emoticons)
            arranged.segments.single().copy(candidates = arranged.headCandidates)
        }
    }

    /**
     * 読み全体に一致する登録語を一文節として表示すべきなら、その文節を返す。候補はエンジンの文節の表記を連結したもので、
     * 登録語は後段の統合で先頭へ加わる。表示に使える登録語かは、登録語の統合規則の判定をそのまま使う。
     */
    private fun wholeReadingSegment(
        request: ConversionRequest,
        conversion: EngineConversion,
        dictionary: UserDictionaryLookup?,
    ): ConversionSegment? {
        if (conversion.segments.size < 2 || request.segmentLengths != null) return null
        val merged = UserDictionaryCandidates.mergeExplicit(request.reading, conversion, dictionary)
        if (merged.segments.size != 1) return null
        val joined = conversion.segments.joinToString(separator = "") { it.value }
        return ConversionSegment(
            reading = request.reading,
            value = joined,
            candidates = listOf(ConversionCandidate(ConversionCandidate.DISPLAYED_VALUE_ID, joined, request.reading)),
        )
    }

    /**
     * 読み全体に完全一致する学習語があり、エンジンが複数の文節に分け、利用者が区切りを指定していなければ、
     * 読み全体を一文節にした文節を返す。表示する学習語は後段で決め、候補はエンジンの文節の表記を連結したものにする。
     */
    private fun wholeLearnedSegment(
        request: ConversionRequest,
        conversion: EngineConversion,
        store: LearningStore?,
        emoticons: EmoticonJudge,
    ): ConversionSegment? {
        if (store == null || conversion.segments.size < 2 || request.segmentLengths != null) return null
        if (learnedDisplay(request.reading, store, emoticons) == null) return null
        val joined = conversion.segments.joinToString(separator = "") { it.value }
        return ConversionSegment(
            reading = request.reading,
            value = joined,
            candidates = listOf(ConversionCandidate(ConversionCandidate.DISPLAYED_VALUE_ID, joined, request.reading)),
        )
    }

    /** 明示変換の最初の表示に使う学習語。読みが完全に一致する語のうちscoreが最も高い、顔文字・絵文字でない語。 */
    private fun learnedDisplay(reading: String, store: LearningStore, emoticons: EmoticonJudge): String? =
        store.exactMatches(reading).map { it.surface }.firstOrNull { !emoticons.isEmoticon(reading, it) }

    /**
     * 最新のライブ変換要求だけを取り出し、保護範囲と入力カーソルで区切った部分範囲ごとに変換する。
     * 学習を止める欄では読みを送る前にincognitoを指定する。自動変換は確定しないため学習されない。
     */
    private fun drainLatestLiveRequest() {
        val pending = latestLiveRequest.getAndSet(null) ?: return
        val sessionEpoch = pending.sessionEpoch
        val request = pending.request
        if (isEnded(sessionEpoch) || currentHealth !is EngineHealth.Ready) return
        val sessionId = sessionFor(sessionEpoch) ?: return
        val insensitive = kanaModifierInsensitive(request.kanaModifierInsensitive)
        if (!runCatching { engine.setIncognito(!request.learningAllowed, insensitive) }.getOrDefault(false)) return
        lastConverted.remove(sessionEpoch)
        // 一つの部分範囲をMozcで変換する。ニューラル変換の辞書としても使う。
        val mozcRange: (String) -> List<ResultSegment>? = { chunk ->
            engine.convertSegments(sessionId, chunk)?.let { toLiveSegments(chunk, it) }
        }
        // 評価モードの間だけ件数を集める。H3の照合に使うMozcの文節は、変換器を通さずエンジンから直接取り出す。
        val collector = if (evaluationActive()) {
            LiveEvaluationCollector(mozcDirect = { reading ->
                runCatching { engine.convertSegments(sessionId, reading) }.getOrNull()?.let { toLiveSegments(reading, it) }
            })
        } else {
            null
        }
        val neural = neuralBackend?.let { backend ->
            val model = backend.modelFor(
                deadlineNanos = pending.requestedAtNanos + NeuralInferenceSettings.TIMEOUT_MILLIS * 1_000_000,
                owner = pending.sequence,
                isSuperseded = { latestLiveRequest.get() != null },
                record = { collector?.onCall(it) },
            )
            // 学習禁止欄と機密欄では、保護範囲や先に変換した表記を左文脈としてモデルへ渡さない。
            NeuralRangeConverter(
                model,
                mozcRange,
                collector ?: NeuralConversionObserver.NONE,
                useLeftContext = request.learningAllowed,
            )
        }
        val converter = SegmentedLiveConverter(
            convertRange = { chunk, leftContext ->
                when {
                    isAsciiOnly(chunk) -> listOf(ResultSegment(chunk, chunk))
                    neural != null -> {
                        collector?.onRange()
                        neural.convert(chunk, leftContext)
                    }
                    else -> mozcRange(chunk)
                }
            },
            userDictionary = lookupUserDictionary(),
            // 学習禁止欄では学習語を参照しない。完全一致した学習語のうちscoreが最も高いものを表示する。
            learnedSurfaces = learnedSurfaces(request.learningAllowed),
            // 部分範囲の先頭からの読みに一致する句で区切りを合わせる。学習禁止欄では参照しない。
            learnedPhrases = learnedPhrases(request.learningAllowed),
            // 顔文字・絵文字はニューラル変換やMozcの第一候補、学習語、登録語でも表示値にしない。
            emoticons = emoticonJudge(request.learningAllowed),
            // ユーザーが伸縮で区切りを決めた読みは、Mozcの文節の幅を合わせて一文節として変換する。
            convertFixed = { reading ->
                if (isAsciiOnly(reading)) {
                    ResultSegment(reading, reading)
                } else {
                    engine.convertSegment(sessionId, "", reading, "")?.let {
                        ResultSegment(reading, it.value, it.candidates, candidatesComplete = true)
                    }
                }
            },
        )
        // 新しい入力でニューラルの推論を中断した場合（NeuralRequestCancelled）も、結果を返さない。
        val result = runCatching { converter.convert(request) }.getOrNull()
        if (result != null) {
            val learnedStore = lookupLearningStore().takeIf { request.learningAllowed && !evaluationActive() }
            val withLearningSource = result.copy(segments = result.segments.map { segment ->
                val learned = learnedStore?.let { store ->
                    (store.exactMatches(segment.reading, Int.MAX_VALUE) + store.exactPhraseMatches(segment.reading, Int.MAX_VALUE))
                        .map { it.surface }
                        .toSet()
                }.orEmpty()
                segment.copy(learnedCandidates = learned)
            })
            onLiveResult(sessionEpoch, withLearningSource)
        }
        collector?.let { onLiveReport(sessionEpoch, it.report(result, pending.requestedAtNanos)) }
    }

    /**
     * 最新の候補の取り直し要求だけを取り出し、対象segmentを前後の読みを文脈に一文節として変換して候補を集める。
     * 自動変換と同じく、ユーザー辞書と学習語を合成する。ASCIIだけの読みはMozcへ送らない（入力のまま表示するため）。
     */
    private fun drainLatestCandidateRequest() {
        val (sessionEpoch, request) = latestCandidateRequest.getAndSet(null) ?: return
        if (isEnded(sessionEpoch) || currentHealth !is EngineHealth.Ready || isAsciiOnly(request.reading)) return
        val sessionId = sessionFor(sessionEpoch) ?: return
        val insensitive = kanaModifierInsensitive(request.kanaModifierInsensitive)
        if (!runCatching { engine.setIncognito(!request.learningAllowed, insensitive) }.getOrDefault(false)) return
        lastConverted.remove(sessionEpoch)
        // 文脈に使う前後の読み。ASCIIだけの読みはライブ変換でもMozcへ送らないため、文脈にもしない。
        val preceding = request.preceding.takeUnless(::isAsciiOnly).orEmpty()
        val following = request.following.takeUnless(::isAsciiOnly).orEmpty()
        val segment = runCatching { engine.convertSegment(sessionId, preceding, request.reading, following) }
            .getOrNull() ?: return
        val exactSurfaces = combineExactSurfaces(
            learnedSurfaces(request.learningAllowed),
            learnedPhrases(request.learningAllowed),
        )
        val judge = emoticonJudge(request.learningAllowed)
        val candidates = mergeSegmentCandidates(
            request.reading,
            segment.candidates,
            lookupUserDictionary(),
            exactSurfaces,
            judge,
        )
        val automaticCandidates = candidates.filterNot { judge.isEmoticon(request.reading, it) }
            .ifEmpty { listOf(request.reading) }
        val learnedCandidates = if (evaluationActive()) emptySet() else {
            (lookupLearningStore()?.exactMatches(request.reading, Int.MAX_VALUE).orEmpty() +
                lookupLearningStore()?.exactPhraseMatches(request.reading, Int.MAX_VALUE).orEmpty())
                .map { it.surface }
                .filter { it in candidates }
                .toSet()
        }
        onCandidateResult(sessionEpoch, CandidateResult(request, candidates, automaticCandidates, learnedCandidates))
    }

    /**
     * 専用sessionでMozc予測を取り、同じ要求の応答だけを候補選択へ結び付ける。
     * ライブ変換の要求が待っていれば予測を後回しにする。連続打鍵で予測が先に走ると、
     * ライブ変換の表示が遅れ、受理時刻から数えるニューラル変換の期限も削られるためである。
     */
    private fun drainLatestPredictionRequest() {
        if (latestPredictionRequest.get() == null) return
        if (latestLiveRequest.get() != null) {
            // 待っているライブ変換の処理はキューに積まれ済みのため、その後ろへ積み直す。要求はそのまま残し、最新だけを後で処理する。
            try {
                executor.execute(::drainLatestPredictionRequest)
            } catch (_: RejectedExecutionException) {
                // workerの終了中は予測を出さない。
            }
            return
        }
        val request = latestPredictionRequest.getAndSet(null) ?: return
        if (isEnded(request.sessionEpoch)) return
        if (currentHealth !is EngineHealth.Ready) {
            onPredictionResult(PredictionResult(request, emptyList()))
            return
        }
        val sessionId = predictionSessionFor(request.sessionEpoch)
        val insensitive = kanaModifierInsensitive(request.kanaModifierInsensitive)
        if (sessionId == null || !runCatching { engine.setIncognito(request.incognito, insensitive) }.getOrDefault(false)) {
            onPredictionResult(PredictionResult(request, emptyList()))
            return
        }
        val generated = runCatching {
            if (request.isZeroQuery) {
                engine.requestNextWordPrediction(sessionId, request.precedingText)
            } else {
                engine.suggest(sessionId, request.reading)
            }
        }.getOrDefault(emptyList())
        val judge = EmoticonJudge(lookupUserDictionary(), lookupLearningStore().takeUnless { request.incognito })
        val candidates = generated
            .filterNot { EmoticonText.isEmoticon(it.value) || judge.isEmoticon(it.reading ?: request.reading, it.value) }
            .take(MAX_VISIBLE_PREDICTIONS)
        lastPredictionRequests[request.sessionEpoch] = request
        lastPredictionCandidates[request.sessionEpoch] = candidates
        onPredictionResult(PredictionResult(request, candidates))
    }

    /**
     * 表記ゆれ変換を使うか。12キーだけで入力した読みの要求で使い、評価の記録中は変換結果を評価の比較条件から
     * 変えないため使わない。
     */
    private fun kanaModifierInsensitive(requested: Boolean): Boolean = requested && !evaluationActive()

    /** 読みに完全一致する学習語の表記を返す関数。学習禁止欄と学習キャッシュが無い場合はnull。 */
    private fun learnedSurfaces(learningAllowed: Boolean): ((String) -> List<String>)? =
        lookupLearningStore()?.takeIf { learningAllowed }?.let { store ->
            { reading -> store.exactMatches(reading).map { it.surface } }
        }

    /** 読みに完全一致する学習した句の表記を返す関数。学習禁止欄と学習キャッシュが無い場合はnull。 */
    private fun learnedPhrases(learningAllowed: Boolean): ((String) -> List<String>)? =
        lookupLearningStore()?.takeIf { learningAllowed }?.let { store ->
            { reading -> store.exactPhraseMatches(reading).map { it.surface } }
        }

    /** 顔文字・絵文字の判定を作る。学習禁止欄では学習の回数を参照せず、昇格させない。 */
    private fun emoticonJudge(learningAllowed: Boolean): EmoticonJudge =
        EmoticonJudge(lookupUserDictionary(), lookupLearningStore().takeIf { learningAllowed })

    /** ユーザー辞書を開いて返す。読めなければ登録語なしで変換を続ける。 */
    private fun lookupUserDictionary(): UserDictionaryLookup? = runCatching { userDictionary() }.getOrNull()

    /** 学習キャッシュを開いて返す。開けなければ学習なしで変換を続ける。 */
    private fun lookupLearningStore(): LearningStore? = runCatching { learningStore() }.getOrNull()

    /** 学習通知が、エンジン側sessionの現在の変換と同じ要求に対するものなら、そのsession IDを返す。 */
    private fun sessionForCommit(request: ConversionRequest): Long? {
        if (isEnded(request.sessionEpoch)) return null
        if (lastConverted[request.sessionEpoch] != request) return null
        return engineSessions[request.sessionEpoch]
    }

    /** sessionEpochに対応するエンジン側sessionを返し、無ければ作る。 */
    private fun sessionFor(sessionEpoch: Long): Long? {
        engineSessions[sessionEpoch]?.let { return it }
        val created = runCatching { engine.createSession() }.getOrNull() ?: return null
        engineSessions[sessionEpoch] = created
        return created
    }

    /** 予測専用のMozc sessionをsessionEpochごとに一つだけ作成する。 */
    private fun predictionSessionFor(sessionEpoch: Long): Long? {
        predictionSessions[sessionEpoch]?.let { return it }
        val created = runCatching { engine.createSession() }.getOrNull() ?: return null
        predictionSessions[sessionEpoch] = created
        return created
    }

    /** sessionEpochのEditor sessionが既に終了しているかを返す。 */
    private fun isEnded(sessionEpoch: Long): Boolean = sessionEpoch <= endedThroughEpoch.get()

    /**
     * 辞書が本当に使えるかを、学習を止めた一時sessionで既知の変換例により確かめる。
     * minimal engineは初期化に成功しても漢字候補を返さないため、ここで検出する。
     */
    private fun convertsKnownExample(): Boolean {
        return runCatching {
            if (!engine.setIncognito(true)) return@runCatching false
            val sessionId = engine.createSession() ?: return@runCatching false
            try {
                val conversion = engine.convert(sessionId, KNOWN_READING)
                conversion?.headCandidates?.any { it.value == KNOWN_CONVERSION } == true
            } finally {
                engine.deleteSession(sessionId)
            }
        }.getOrDefault(false)
    }

    /** 未処理のライブ変換要求。requestedAtNanosは受理した時刻で、時間超過の期限と適用までの時間の起点にする。 */
    private data class PendingLive(
        val sessionEpoch: Long,
        val request: LiveRequest,
        val requestedAtNanos: Long,
        val sequence: Long,
    )

    private companion object {
        /** 辞書の有無を判定する既知の読み。 */
        const val KNOWN_READING = "かんじ"

        /** KNOWN_READINGの候補に辞書があれば必ず含まれる表記。 */
        const val KNOWN_CONVERSION = "漢字"

        /** 候補バーの先頭に表示する予測候補の最大件数。 */
        private const val MAX_VISIBLE_PREDICTIONS = 3
    }
}
