package dev.uzumi.ime.conversion

import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import dev.uzumi.ime.live.ConversionResult as LiveResult
import dev.uzumi.ime.live.ProtectedRange
import dev.uzumi.ime.live.RequestIdentity
import dev.uzumi.ime.live.ResultSegment
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 直列worker、最新要求だけの処理、incognitoの順序、session破棄、fallback検出を検証する。 */
class ConversionWorkerTest {
    /** 要求は呼び出し元でエンジンを呼ばず、workerのexecutor上で一件ずつ処理される。 */
    @Test
    fun requestReturnsWithoutCallingEngineUntilWorkerRuns() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()

        fixture.worker.requestConversion(request(reading = "かんじ"))
        assertTrue(fixture.engine.calls.isEmpty())

        fixture.executor.runAll()
        assertEquals(
            listOf("createSession", "setIncognito(false)", "convert(1,かんじ)"),
            fixture.engine.calls,
        )
        assertEquals(1, fixture.outcomes.size)
        assertTrue(fixture.outcomes.single() is ConversionOutcome.Converted)
    }

    /** 未処理のまま次の要求が来たら、古い読みはエンジンへ送らない。 */
    @Test
    fun onlyLatestPendingRequestIsConverted() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()

        fixture.worker.requestConversion(request(revision = 1, reading = "か"))
        fixture.worker.requestConversion(request(revision = 2, reading = "かん"))
        fixture.worker.requestConversion(request(revision = 3, reading = "かんじ"))
        fixture.executor.runAll()

        assertEquals(listOf("convert(1,かんじ)"), fixture.engine.calls.filter { it.startsWith("convert") })
        assertEquals(listOf(3L), fixture.outcomes.map { it.request.revision })
    }

    /** 学習禁止欄では、読みを送る前にincognitoを指定する。 */
    @Test
    fun incognitoIsSetBeforeReadingIsSent() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()

        fixture.worker.requestConversion(request(reading = "かんじ", incognito = true))
        fixture.executor.runAll()

        val incognitoIndex = fixture.engine.calls.indexOf("setIncognito(true)")
        val convertIndex = fixture.engine.calls.indexOf("convert(1,かんじ)")
        assertTrue(incognitoIndex >= 0)
        assertTrue(incognitoIndex < convertIndex)
    }

    /** incognitoを指定できなければ、読みをエンジンへ送らず失敗として返す。 */
    @Test
    fun incognitoFailurePreventsConversion() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.incognitoAccepted = false
        fixture.engine.calls.clear()

        fixture.worker.requestConversion(request(reading = "かんじ", incognito = true))
        fixture.executor.runAll()

        assertFalse(fixture.engine.calls.any { it.startsWith("convert") })
        assertTrue(fixture.outcomes.single() is ConversionOutcome.Failed)
    }

    /** 終了したフィールドの要求は、キューに残っていてもエンジンへ送らず、sessionを破棄する。 */
    @Test
    fun endedSessionDropsQueuedRequestAndDeletesEngineSession() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.worker.requestConversion(request(epoch = 1, reading = "かんじ"))
        fixture.executor.runAll()
        fixture.engine.calls.clear()
        fixture.outcomes.clear()

        fixture.worker.requestConversion(request(epoch = 1, revision = 2, reading = "かんじを"))
        fixture.worker.endSession(1)
        fixture.worker.requestConversion(request(epoch = 2, reading = "てんき"))
        fixture.executor.runAll()

        // 旧フィールドの「かんじを」は送られず、新フィールドには別のエンジンsessionが作られる。
        assertEquals(
            listOf("createSession", "setIncognito(false)", "convert(2,てんき)", "deleteSession(1)"),
            fixture.engine.calls,
        )
        assertEquals(listOf(2L), fixture.outcomes.map { it.request.sessionEpoch })
    }

    /** 学習通知は、エンジン側の現在の変換と同じ要求で、学習禁止でない場合だけ送る。 */
    @Test
    fun commitIsSentOnlyForCurrentNonIncognitoConversion() {
        val fixture = Fixture()
        fixture.startReady()
        val first = request(revision = 1, reading = "かんじ")
        fixture.worker.requestConversion(first)
        fixture.executor.runAll()
        val second = request(revision = 2, reading = "かんじ")
        fixture.worker.requestConversion(second)
        fixture.executor.runAll()
        fixture.engine.calls.clear()

        fixture.worker.commitCandidate(first, 7)
        fixture.worker.commitCandidate(second.copy(incognito = true), 7)
        fixture.worker.commitCandidate(second, 7)
        fixture.worker.commitAll(second)
        fixture.executor.runAll()

        assertEquals(listOf("commitCandidate(1,7)"), fixture.engine.calls)
    }

    /** data versionが正常でも既知の変換例に漢字が無ければ、辞書なしとして扱い変換を送らない。 */
    @Test
    fun minimalEngineIsDetectedByKnownConversion() {
        val fixture = Fixture()
        fixture.engine.knownConversionValues = listOf("かんじ", "カンジ")
        fixture.worker.start()
        fixture.executor.runAll()

        assertEquals(EngineHealth.Unavailable(EngineUnavailableReason.MINIMAL_ENGINE), fixture.worker.health)
        assertFalse(fixture.worker.isAvailable)
        assertTrue(fixture.engine.calls.contains("setIncognito(true)"))
        assertTrue(fixture.engine.calls.contains("deleteSession(100)"))

        fixture.engine.calls.clear()
        fixture.worker.requestConversion(request(reading = "かんじ"))
        fixture.executor.runAll()
        assertTrue(fixture.engine.calls.isEmpty())
        assertTrue(fixture.outcomes.single() is ConversionOutcome.Failed)
    }

    /** 読み込み時の例外や辞書なしの状態は、Unavailableとして通知する。 */
    @Test
    fun loadFailureIsReportedAsUnavailable() {
        val fixture = Fixture()
        fixture.engine.loadResult = { error("broken data") }
        fixture.worker.start()
        fixture.executor.runAll()

        assertEquals(
            listOf(EngineHealth.Unavailable(EngineUnavailableReason.INITIALIZATION_FAILED)),
            fixture.healthChanges,
        )
    }

    /** 文節の読みが要求した読みと一致しない結果は、適用させずに失敗として返す。 */
    @Test
    fun inconsistentSegmentsAreReportedAsFailure() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.segmentReadingOverride = "かん"

        fixture.worker.requestConversion(request(reading = "かんじ"))
        fixture.executor.runAll()

        assertTrue(fixture.outcomes.single() is ConversionOutcome.Failed)
    }

    /** ライブ変換も最新の要求だけを変換し、学習禁止なら読みを送る前にincognitoを指定する。 */
    @Test
    fun liveConversionUsesOnlyLatestRequestAndSetsIncognitoFirst() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()

        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "か"))
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 2, reading = "かん", learningAllowed = false))
        fixture.executor.runAll()

        assertEquals(listOf("createSession", "setIncognito(true)", "convertSegments(1,かん)"), fixture.engine.calls)
        val (epoch, result) = fixture.liveResults.single()
        assertEquals(7L, epoch)
        assertEquals(2L, result.identity.revision)
        assertEquals(listOf(ResultSegment("かん", "かん", listOf("かん"))), result.segments)
    }

    /** 保護範囲を除いた部分範囲だけをエンジンへ送り、ASCIIだけの範囲は送らない。 */
    @Test
    fun liveConversionSendsOnlyFreeNonAsciiRanges() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()
        val identity = RequestIdentity(
            sessionEpoch = 1,
            revision = 3,
            reading = "abcをかう",
            targetStart = 0,
            targetEnd = 6,
            protectedRanges = listOf(ProtectedRange(3, 4, "を")),
            inputCursor = 6,
            converterGeneration = 0,
        )

        fixture.worker.requestLiveConversion(7, LiveRequest(identity, "abcをかう", learningAllowed = true))
        fixture.executor.runAll()

        assertEquals(listOf("convertSegments(1,かう)"), fixture.engine.calls.filter { it.startsWith("convert") })
        assertEquals(listOf("abc", "を", "かう"), fixture.liveResults.single().second.segments.map { it.reading })
    }

    /** ライブ変換の結果には、読みが一致するユーザー辞書の登録語を候補の先頭に加える。 */
    @Test
    fun liveConversionAddsUserDictionaryWords() {
        val fixture = Fixture(dictionary = FakeLookup("かん" to "缶"))
        fixture.startReady()

        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "かん"))
        fixture.executor.runAll()

        val segment = fixture.liveResults.single().second.segments.single()
        assertEquals("缶", segment.surface)
        assertEquals(listOf("缶", "かん"), segment.candidates)
    }

    /** 明示変換の先頭文節の候補にも、登録語を先頭に加える。 */
    @Test
    fun explicitConversionAddsUserDictionaryWords() {
        val fixture = Fixture(dictionary = FakeLookup("かんじ" to "幹事"))
        fixture.startReady()

        fixture.worker.requestConversion(request(reading = "かんじ"))
        fixture.executor.runAll()

        val result = (fixture.outcomes.single() as ConversionOutcome.Converted).result
        assertEquals(listOf("幹事", "漢字", "感じ"), result.headCandidates.map { it.value })
        assertTrue(result.headCandidates.first().fromUserDictionary)
        assertEquals("幹事", result.display)
        assertTrue(result.isConsistent)
    }

    /** 確定の学習はsession破棄より先にキューへ積まれ、合わせられない単位はsegmentごとに学習し直す。 */
    @Test
    fun learningRunsBeforeSessionDeletionAndFallsBackPerSegment() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "きょうは"))
        fixture.executor.runAll()
        fixture.engine.calls.clear()
        fixture.engine.learnAccepted = { it.size == 1 }

        fixture.worker.learnCommitted(
            7,
            listOf(listOf(LearnedSegment("きょう", "今日"), LearnedSegment("は", "は"))),
        )
        fixture.worker.endSession(7)
        fixture.worker.learnCommitted(7, listOf(listOf(LearnedSegment("あ", "亜"))))
        fixture.executor.runAll()

        assertEquals(
            listOf(
                "setIncognito(false)",
                "learnSegments(1,きょう=今日|は=は)",
                "learnSegments(1,きょう=今日)",
                "learnSegments(1,は=は)",
                "deleteSession(1)",
            ),
            fixture.engine.calls,
        )
    }

    private fun liveRequest(revision: Long, reading: String, learningAllowed: Boolean = true): LiveRequest {
        val length = reading.length
        val identity = RequestIdentity(1, revision, reading, 0, length, emptyList(), length, 0)
        return LiveRequest(identity, reading, learningAllowed)
    }

    private fun request(
        epoch: Long = 1,
        revision: Long = 1,
        reading: String,
        incognito: Boolean = false,
    ) = ConversionRequest(epoch, revision, reading, incognito)

    /** テストごとのworker、偽エンジン、手動executorの組。 */
    private class Fixture(dictionary: FakeLookup? = null) {
        val engine = FakeConversionEngine()
        val executor = ManualExecutor()
        val outcomes = mutableListOf<ConversionOutcome>()
        val healthChanges = mutableListOf<EngineHealth>()
        val liveResults = mutableListOf<Pair<Long, LiveResult>>()
        val worker = ConversionWorker(
            engine = engine,
            executor = executor,
            onHealthChanged = { healthChanges += it },
            onOutcome = { outcomes += it },
            onLiveResult = { epoch, result -> liveResults += epoch to result },
            userDictionary = { dictionary },
        )

        /** 初期化と既知変換例の確認を済ませ、以後のエンジンsession番号を1から始める。 */
        fun startReady() {
            worker.start()
            executor.runAll()
            check(worker.isAvailable)
            engine.nextSessionId = 1
        }
    }
}

/** 登録されたタスクを、テストが指示した時点で登録順に一件ずつ実行する。 */
private class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    /** キューが空になるまで順に実行する。 */
    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

/** 呼ばれた操作を記録し、読みをそのまま一文節として返す偽の変換エンジン。 */
private class FakeConversionEngine : ConversionEngine {
    val calls = mutableListOf<String>()
    var loadResult: () -> EngineHealth = { EngineHealth.Ready("test-data") }
    var incognitoAccepted = true
    var nextSessionId = 100L
    var knownConversionValues = listOf("漢字", "感じ")
    var segmentReadingOverride: String? = null

    override fun load(): EngineHealth = loadResult()

    override fun createSession(): Long {
        calls += "createSession"
        return nextSessionId++
    }

    override fun deleteSession(sessionId: Long) {
        calls += "deleteSession($sessionId)"
    }

    override fun setIncognito(incognito: Boolean): Boolean {
        calls += "setIncognito($incognito)"
        return incognitoAccepted
    }

    override fun convert(sessionId: Long, reading: String): EngineConversion {
        calls += "convert($sessionId,$reading)"
        val values = if (reading == "かんじ") knownConversionValues else listOf(reading)
        val segmentReading = segmentReadingOverride ?: reading
        return EngineConversion(
            segments = listOf(ConversionSegment(segmentReading, values.first())),
            headCandidates = values.mapIndexed { index, value -> ConversionCandidate(index, value, segmentReading) },
        )
    }

    override fun commitCandidate(sessionId: Long, candidateId: Int): Boolean {
        calls += "commitCandidate($sessionId,$candidateId)"
        return true
    }

    override fun commitAll(sessionId: Long): Boolean {
        calls += "commitAll($sessionId)"
        return true
    }

    // ライブ変換で返す文節。既定では読み全体を一文節としてそのまま返す。
    var segmentsFor: (String) -> List<EngineSegment>? = { reading -> listOf(EngineSegment(reading, reading, listOf(reading))) }

    // 学習の成否。既定では常に成功する。
    var learnAccepted: (List<LearnedSegment>) -> Boolean = { true }

    override fun convertSegments(sessionId: Long, reading: String): List<EngineSegment>? {
        calls += "convertSegments($sessionId,$reading)"
        return segmentsFor(reading)
    }

    override fun learnSegments(sessionId: Long, segments: List<LearnedSegment>): Boolean {
        calls += "learnSegments($sessionId," + segments.joinToString("|") { "${it.reading}=${it.surface}" } + ")"
        return learnAccepted(segments)
    }
}
