package dev.uzumi.ime.conversion

import java.util.concurrent.Executor
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
) : ConversionClient {
    @Volatile
    private var currentHealth: EngineHealth = EngineHealth.Loading

    // 未処理の変換要求のうち最新の一件。古い要求は処理前に上書きされて捨てられる。
    private val latestRequest = AtomicReference<ConversionRequest?>(null)

    // この値以下のsessionEpochは終了済み。キューに残った要求もエンジンへ送らない。
    private val endedThroughEpoch = AtomicLong(Long.MIN_VALUE)

    // 以下はworker threadだけが読み書きする。
    private val engineSessions = mutableMapOf<Long, Long>()
    // sessionEpochごとに、エンジン側sessionの状態を作った直前の要求。学習通知の照合に使う。
    private val lastConverted = mutableMapOf<Long, ConversionRequest>()

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
        }
    }

    override fun requestConversion(request: ConversionRequest) {
        latestRequest.set(request)
        executor.execute(::drainLatestRequest)
    }

    override fun commitCandidate(request: ConversionRequest, candidateId: Int) {
        if (request.incognito) return
        executor.execute {
            val sessionId = sessionForCommit(request) ?: return@execute
            runCatching { engine.commitCandidate(sessionId, candidateId) }
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
        val result = ConversionResult(request, conversion.segments, conversion.headCandidates)
        onOutcome(
            if (result.isConsistent) ConversionOutcome.Converted(result) else ConversionOutcome.Failed(request),
        )
    }

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
