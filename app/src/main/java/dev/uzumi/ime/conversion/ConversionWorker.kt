package dev.uzumi.ime.conversion

import dev.uzumi.ime.dictionary.UserDictionaryLookup
import dev.uzumi.ime.learning.LearningStore
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

    /** requestの変換で表示した先頭文節の候補をユーザーが確定したことを伝える。 */
    fun commitCandidate(request: ConversionRequest, candidateId: Int)

    /** requestの変換結果を全文節そのまま確定したことを伝える。 */
    fun commitAll(request: ConversionRequest)
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

    /** ライブ変換で確定したsegment列を、単位ごとにエンジンへ学習させる。学習禁止欄では呼ばない。 */
    fun learnCommitted(sessionEpoch: Long, units: List<List<LearnedSegment>>)
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
    // ユーザー辞書を返す。初回はファイルを読むため、worker threadからだけ呼ぶ。
    private val userDictionary: () -> UserDictionaryLookup? = { null },
    // IME側の学習キャッシュを返す。初回はファイルを読むため、worker threadからだけ呼ぶ。nullなら学習を使わない。
    private val learningStore: () -> LearningStore? = { null },
) : ConversionClient, LiveConversionClient {
    @Volatile
    private var currentHealth: EngineHealth = EngineHealth.Loading

    // 未処理の変換要求のうち最新の一件。古い要求は処理前に上書きされて捨てられる。
    private val latestRequest = AtomicReference<ConversionRequest?>(null)

    // 未処理のライブ変換要求のうち最新の一件と、その編集セッションの世代。
    private val latestLiveRequest = AtomicReference<Pair<Long, LiveRequest>?>(null)

    // この値以下のsessionEpochは終了済み。キューに残った要求もエンジンへ送らない。
    private val endedThroughEpoch = AtomicLong(Long.MIN_VALUE)

    // 以下はworker threadだけが読み書きする。
    private val engineSessions = mutableMapOf<Long, Long>()
    // sessionEpochごとに、エンジン側sessionの状態を作った直前の要求。学習通知の照合に使う。
    private val lastConverted = mutableMapOf<Long, ConversionRequest>()
    // sessionEpochごとに、直前の明示変換で表示した先頭文節の候補。確定した候補の読みと表記を引くために使う。
    private val lastHeadCandidates = mutableMapOf<Long, List<ConversionCandidate>>()

    /** 最新の初期化状態。UI threadから読んでよい。 */
    val health: EngineHealth
        get() = currentHealth

    override val isAvailable: Boolean
        get() = currentHealth is EngineHealth.Ready

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
        latestLiveRequest.set(sessionEpoch to request)
        executor.execute(::drainLatestLiveRequest)
    }

    /**
     * 終了判定は呼び出し時点で行う。Send等で確定直後に入力欄が閉じても、同じ世代のsession破棄より先に
     * キューへ積むため、確定した内容を学習できる。
     */
    override fun learnCommitted(sessionEpoch: Long, units: List<List<LearnedSegment>>) {
        if (units.isEmpty() || isEnded(sessionEpoch)) return
        executor.execute {
            val sessionId = engineSessions[sessionEpoch] ?: return@execute
            // 表示のまま確定したsegmentと、同じ単位の先頭からの句をIME側の学習にも記録する。
            lookupLearningStore()?.let { store -> recordCommittedUnits(store, units) }
            if (!runCatching { engine.setIncognito(false) }.getOrDefault(false)) return@execute
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
     * 新しい句は、ユーザーが候補を選んだsegmentを含む場合だけ作る。選ばずに確定した句はエンジンの結果の再現なので、
     * 覚えても表示は変わらず上限を埋めるだけだからである。既に覚えている句は、選ばなくても時刻と回数を更新する。
     */
    private fun recordCommittedUnits(store: LearningStore, units: List<List<LearnedSegment>>) {
        val dictionary = lookupUserDictionary()
        // 読みと表記がユーザー辞書の登録語と一致するsegmentか。
        fun registered(segment: LearnedSegment): Boolean =
            dictionary?.exactMatches(segment.reading)?.any { it.surface == segment.surface } == true
        for (unit in units) {
            unit.filterNot(::registered).forEach { store.record(it.reading, it.surface) }
            // 登録語のsegmentより前だけを句にする。
            val phraseSource = unit.takeWhile { !registered(it) }
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
     * 明示的に選んだ候補の確定を、IME側の学習へ記録し、エンジンの候補ならエンジンへも通知する。
     * 学習語だけから作った候補はエンジンが知らないため、エンジンへは送らない。
     */
    override fun commitCandidate(request: ConversionRequest, candidateId: Int) {
        if (request.incognito) return
        executor.execute {
            val sessionId = sessionForCommit(request) ?: return@execute
            val candidate = lastHeadCandidates[request.sessionEpoch]?.firstOrNull { it.id == candidateId }
            candidate?.let { lookupLearningStore()?.record(it.learnedReading ?: it.reading, it.value) }
            if (candidate?.learnedReading == null) runCatching { engine.commitCandidate(sessionId, candidateId) }
            lastConverted.remove(request.sessionEpoch)
        }
    }

    override fun commitAll(request: ConversionRequest) {
        if (request.incognito) return
        executor.execute {
            val sessionId = sessionForCommit(request) ?: return@execute
            runCatching { engine.commitAll(sessionId) }
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
            lastHeadCandidates.remove(sessionEpoch)
            // 学習は打鍵ごとではなく、入力欄の終了時にまとめて保存する。
            lookupLearningStore()?.flush()
            val sessionId = engineSessions.remove(sessionEpoch) ?: return@execute
            runCatching { engine.deleteSession(sessionId) }
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
        if (sessionId == null || !runCatching { engine.setIncognito(request.incognito) }.getOrDefault(false)) {
            onOutcome(ConversionOutcome.Failed(request))
            return
        }
        lastConverted.remove(request.sessionEpoch)
        val conversion = runCatching { engine.convert(sessionId, request.reading) }.getOrNull()
        if (conversion == null) {
            onOutcome(ConversionOutcome.Failed(request))
            return
        }
        lastConverted[request.sessionEpoch] = request
        // 並びは「ユーザー辞書 → 学習 → エンジン」。学習禁止欄では学習語を参照しない。
        val learned = LearnedCandidates.mergeExplicit(
            request.reading,
            conversion,
            lookupLearningStore().takeUnless { request.incognito },
        )
        val merged = UserDictionaryCandidates.mergeExplicit(request.reading, learned, lookupUserDictionary())
        lastHeadCandidates[request.sessionEpoch] = merged.headCandidates
        val result = ConversionResult(request, merged.segments, merged.headCandidates)
        onOutcome(
            if (result.isConsistent) ConversionOutcome.Converted(result) else ConversionOutcome.Failed(request),
        )
    }

    /**
     * 最新のライブ変換要求だけを取り出し、保護範囲と入力カーソルで区切った部分範囲ごとに変換する。
     * 学習を止める欄では読みを送る前にincognitoを指定する。自動変換は確定しないため学習されない。
     */
    private fun drainLatestLiveRequest() {
        val (sessionEpoch, request) = latestLiveRequest.getAndSet(null) ?: return
        if (isEnded(sessionEpoch) || currentHealth !is EngineHealth.Ready) return
        val sessionId = sessionFor(sessionEpoch) ?: return
        if (!runCatching { engine.setIncognito(!request.learningAllowed) }.getOrDefault(false)) return
        lastConverted.remove(sessionEpoch)
        val converter = SegmentedLiveConverter(
            convertRange = { chunk ->
                if (isAsciiOnly(chunk)) {
                    listOf(ResultSegment(chunk, chunk))
                } else {
                    engine.convertSegments(sessionId, chunk)?.let { toLiveSegments(chunk, it) }
                }
            },
            userDictionary = lookupUserDictionary(),
            // 学習禁止欄では学習語を参照しない。完全一致した学習語のうちscoreが最も高いものを表示する。
            learnedSurfaces = lookupLearningStore()?.takeIf { request.learningAllowed }?.let { store ->
                { reading -> store.exactMatches(reading).map { it.surface } }
            },
            // 部分範囲の先頭からの読みに一致する句で区切りを合わせる。学習禁止欄では参照しない。
            learnedPhrases = lookupLearningStore()?.takeIf { request.learningAllowed }?.let { store ->
                { reading -> store.exactPhraseMatches(reading).map { it.surface } }
            },
        )
        val result = runCatching { converter.convert(request) }.getOrNull() ?: return
        onLiveResult(sessionEpoch, result)
    }

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

    private companion object {
        /** 辞書の有無を判定する既知の読み。 */
        const val KNOWN_READING = "かんじ"

        /** KNOWN_READINGの候補に辞書があれば必ず含まれる表記。 */
        const val KNOWN_CONVERSION = "漢字"
    }
}
