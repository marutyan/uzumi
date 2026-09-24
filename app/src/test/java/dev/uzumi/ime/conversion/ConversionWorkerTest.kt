package dev.uzumi.ime.conversion

import dev.uzumi.ime.evaluation.LiveRequestReport
import dev.uzumi.ime.learning.LearningStore
import dev.uzumi.ime.live.CandidateRequest
import dev.uzumi.ime.live.CandidateResult
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import dev.uzumi.ime.live.ConversionResult as LiveResult
import dev.uzumi.ime.live.FixedRange
import dev.uzumi.ime.live.ProtectedRange
import dev.uzumi.ime.live.RequestIdentity
import dev.uzumi.ime.live.ResultSegment
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * 候補の取り直しは最新の要求だけを処理し、incognitoを先に指定してから、前後の読みを文脈に一文節として変換する。
     * 登録語は自動変換と同じく先頭に加え、ASCIIだけの文脈はエンジンへ送らない。
     */
    @Test
    fun segmentCandidatesUseLatestRequestWithContextAndUserDictionary() {
        val fixture = Fixture(dictionary = FakeLookup("てんき" to "テンキ"))
        fixture.startReady()
        fixture.engine.calls.clear()
        fixture.engine.segmentFor = { _, reading, _ -> EngineSegment(reading, "天気", listOf("天気", "転機", "天機")) }

        fixture.worker.requestSegmentCandidates(7, candidateRequest(reading = "てんい", preceding = "きょうは"))
        fixture.worker.requestSegmentCandidates(
            7,
            candidateRequest(reading = "てんき", preceding = "abc", following = "が", learningAllowed = false),
        )
        fixture.executor.runAll()

        assertEquals(listOf("createSession", "setIncognito(true)", "convertSegment(1,|てんき|が)"), fixture.engine.calls)
        val (epoch, result) = fixture.candidateResults.single()
        assertEquals(7L, epoch)
        assertEquals("てんき", result.request.reading)
        assertEquals(listOf("テンキ", "天気", "転機", "天機", "てんき"), result.candidates)
    }

    /** 区切りを決めた範囲は、エンジンの一文節の変換（convertSegment）で変換し、候補は取り直し済みとして返す。 */
    @Test
    fun liveConversionConvertsFixedRangeAsOneSegment() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()
        fixture.engine.segmentFor = { _, reading, _ -> EngineSegment(reading, "三時に", listOf("三時に", "3時に")) }
        val identity = RequestIdentity(1, 2, "さんじにあう", 0, 6, emptyList(), 6, 0, fixedRanges = listOf(FixedRange(0, 4)))

        fixture.worker.requestLiveConversion(7, LiveRequest(identity, "さんじにあう", learningAllowed = true))
        fixture.executor.runAll()

        assertEquals(
            listOf("convertSegment(1,|さんじに|)", "convertSegments(1,あう)"),
            fixture.engine.calls.filter { it.startsWith("convert") },
        )
        val fixed = fixture.liveResults.single().second.segments.first()
        assertEquals(ResultSegment("さんじに", "三時に", listOf("三時に", "3時に"), candidatesComplete = true), fixed)
    }

    /** 明示変換の文節の伸縮は、先頭文節の書記素数をエンジンへ渡して変換し直す。 */
    @Test
    fun explicitConversionPassesHeadLength() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()

        fixture.worker.requestConversion(request(reading = "かんじ").copy(headLength = 2))
        fixture.executor.runAll()

        assertEquals(listOf("convert(1,かんじ,2)"), fixture.engine.calls.filter { it.startsWith("convert") })
    }

    /** ASCIIだけの文節と、終了した編集セッションの要求はエンジンへ送らない。 */
    @Test
    fun segmentCandidatesSkipAsciiAndEndedSessions() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.engine.calls.clear()

        fixture.worker.requestSegmentCandidates(7, candidateRequest(reading = "abc"))
        fixture.executor.runAll()
        fixture.worker.endSession(8)
        fixture.worker.requestSegmentCandidates(8, candidateRequest(reading = "てんき"))
        fixture.executor.runAll()

        assertTrue(fixture.engine.calls.none { it.startsWith("convertSegment") })
        assertTrue(fixture.candidateResults.isEmpty())
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

    /**
     * ライブ変換では、完全一致した学習語のうちscoreが最も高いものを表示し、候補は「登録語 → 学習語 → エンジン」の順に並ぶ。
     * 登録語がある読みでは登録語を表示する。
     */
    @Test
    fun liveConversionShowsLatestLearnedWordAfterUserDictionary() {
        val learning = learningStore()
        learning.record("こうえんに", "公演に")
        now = 1
        learning.record("こうえんに", "校園に")
        learning.record("かん", "寒")
        val fixture = Fixture(dictionary = FakeLookup("かん" to "缶"), learning = learning)
        fixture.startReady()
        fixture.engine.segmentsFor = { reading ->
            listOf(EngineSegment("こうえんに", "公園に", listOf("公園に", "公演に")), EngineSegment("いく", "行く", listOf("行く")))
                .takeIf { reading == "こうえんにいく" } ?: listOf(EngineSegment(reading, reading, listOf(reading)))
        }

        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "こうえんにいく"))
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 2, reading = "かん"))
        fixture.executor.runAll()
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 3, reading = "こうえんにいく"))
        fixture.executor.runAll()

        val kan = fixture.liveResults[0].second.segments.single()
        assertEquals("缶", kan.surface)
        assertEquals(listOf("缶", "寒", "かん"), kan.candidates)
        val head = fixture.liveResults[1].second.segments.first()
        assertEquals("校園に", head.surface)
        assertEquals(listOf("校園に", "公演に", "公園に"), head.candidates)
    }

    /** 学習禁止欄のライブ変換では学習語を参照しない。 */
    @Test
    fun liveConversionInNoLearningFieldIgnoresLearnedWords() {
        val learning = learningStore().apply { record("かん", "寒") }
        val fixture = Fixture(learning = learning)
        fixture.startReady()

        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "かん", learningAllowed = false))
        fixture.executor.runAll()

        assertEquals(listOf("かん"), fixture.liveResults.single().second.segments.single().candidates)
    }

    /** ライブ変換で確定したsegmentは学習へ記録し、ひらがなのままの表記は記録しない。保存は入力欄の終了時に行う。 */
    @Test
    fun liveCommitRecordsLearnedWordsAndSavesOnSessionEnd() {
        val learning = learningStore()
        val fixture = Fixture(learning = learning)
        fixture.startReady()
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "こうえんにいく"))
        fixture.executor.runAll()

        fixture.worker.learnCommitted(7, listOf(listOf(LearnedSegment("こうえんに", "校園に"), LearnedSegment("いく", "いく"))))
        fixture.executor.runAll()
        assertEquals(listOf("校園に"), learning.exactMatches("こうえんに").map { it.surface })
        assertTrue(learning.exactMatches("いく").isEmpty())
        assertFalse(learningFile.exists())

        fixture.worker.endSession(7)
        fixture.executor.runAll()
        assertTrue(learningFile.exists())
    }

    /**
     * 明示変換の候補は「登録語 → 学習語 → エンジン → 前方一致の予測」の順に並び、表示はエンジンの第一候補のまま。
     * 学習語だけの候補を確定すると学習へ記録し、エンジンへは送らない。予測は学習語の本来の読みで記録する。
     */
    @Test
    fun explicitConversionOrdersLearnedWordsAndRecordsCommits() {
        val learning = learningStore()
        learning.record("かんじ", "幹事")
        now = 1
        learning.record("かんじ", "監事")
        learning.record("かんじる", "感じる")
        val fixture = Fixture(dictionary = FakeLookup("かんじ" to "莞爾"), learning = learning)
        fixture.startReady()
        val conversion = request(reading = "かんじ")

        fixture.worker.requestConversion(conversion)
        fixture.executor.runAll()

        val result = (fixture.outcomes.single() as ConversionOutcome.Converted).result
        assertEquals(listOf("莞爾", "監事", "幹事", "漢字", "感じ", "感じる"), result.headCandidates.map { it.value })
        assertEquals("莞爾", result.display)
        assertTrue(result.isConsistent)
        val prediction = result.headCandidates.last()
        assertEquals("かんじ", prediction.reading)
        assertEquals("かんじる", prediction.learnedReading)

        fixture.engine.calls.clear()
        fixture.worker.commitCandidate(conversion, prediction.id)
        fixture.executor.runAll()
        assertFalse(fixture.engine.calls.any { it.startsWith("commitCandidate") })
        assertEquals(2, learning.exactMatches("かんじる").single().useCount)
    }

    /** エンジンの候補を確定したら、エンジンへ通知し、学習にも記録する。 */
    @Test
    fun explicitEngineCandidateCommitIsSentAndRecorded() {
        val learning = learningStore()
        val fixture = Fixture(learning = learning)
        fixture.startReady()
        val conversion = request(reading = "かんじ")
        fixture.worker.requestConversion(conversion)
        fixture.executor.runAll()
        fixture.engine.calls.clear()

        fixture.worker.commitCandidate(conversion, 1)
        fixture.executor.runAll()

        assertEquals(listOf("commitCandidate(1,1)"), fixture.engine.calls)
        assertEquals(listOf("感じ"), learning.exactMatches("かんじ").map { it.surface })
    }

    /** 学習禁止欄の明示変換では、学習語を候補へ加えず、確定も記録しない。 */
    @Test
    fun explicitConversionInNoLearningFieldNeitherUsesNorRecordsLearning() {
        val learning = learningStore().apply { record("かんじ", "幹事") }
        val fixture = Fixture(learning = learning)
        fixture.startReady()
        val conversion = request(reading = "かんじ", incognito = true)

        fixture.worker.requestConversion(conversion)
        fixture.executor.runAll()
        fixture.worker.commitCandidate(conversion, 1)
        fixture.executor.runAll()

        val result = (fixture.outcomes.single() as ConversionOutcome.Converted).result
        assertEquals(listOf("漢字", "感じ"), result.headCandidates.map { it.value })
        assertNull(learning.exactMatches("かんじ").firstOrNull { it.surface == "感じ" })
    }

    /** ライブ変換で登録語を表示したまま確定しても、登録語は学習へ記録しない。 */
    @Test
    fun liveCommitDoesNotRecordUserDictionaryWords() {
        val learning = learningStore()
        val fixture = Fixture(dictionary = FakeLookup("うずみ" to "渦見", "かん" to "缶"), learning = learning)
        fixture.startReady()
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "うずみかん"))
        fixture.executor.runAll()

        fixture.worker.learnCommitted(7, listOf(listOf(LearnedSegment("うずみ", "渦見"), LearnedSegment("かん", "缶"))))
        fixture.executor.runAll()

        assertTrue(learning.allWords().isEmpty())
    }

    /**
     * 2segment以上の単位では、先頭からの累積句を記録する。新しい句は候補を選んだsegmentを含む句だけで、
     * 一segmentの単位と選ばずに確定した単位からは作らない。既にある句は選ばずに確定しても回数を更新する。
     */
    @Test
    fun liveCommitRecordsCumulativePhrasesContainingChosenSegment() {
        val learning = learningStore()
        val fixture = Fixture(learning = learning)
        fixture.startReady()
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "きょうはこうえんにいく"))
        fixture.executor.runAll()

        fixture.worker.learnCommitted(
            7,
            listOf(
                listOf(
                    LearnedSegment("きょうは", "今日は"),
                    LearnedSegment("こうえんに", "校園に", chosen = true),
                    LearnedSegment("いく", "行く"),
                ),
                listOf(LearnedSegment("てんき", "天気"), LearnedSegment("が", "が")),
                listOf(LearnedSegment("はし", "橋", chosen = true)),
            ),
        )
        fixture.executor.runAll()

        assertEquals(
            listOf("きょうはこうえんに" to "今日は校園に", "きょうはこうえんにいく" to "今日は校園に行く"),
            learning.allWords().filter { it.isPhrase }.map { it.reading to it.surface }.sortedBy { it.first.length },
        )
        assertEquals(setOf("今日は", "校園に", "行く", "天気", "橋"), learning.allWords().filterNot { it.isPhrase }.map { it.surface }.toSet())

        // 表示された句をそのまま（選ばずに）確定しても、覚えている句は更新する。
        fixture.worker.learnCommitted(
            7,
            listOf(listOf(LearnedSegment("きょうは", "今日は"), LearnedSegment("こうえんに", "校園に"))),
        )
        fixture.executor.runAll()
        assertEquals(2, learning.exactPhraseMatches("きょうはこうえんに").single().useCount)
    }

    /**
     * 句は登録語のsegmentの手前で打ち切る。ひらがなだけの句と50文字を超える句は記録しない。
     * ひらがなだけのsegmentは、漢字を含む句の一部としてなら記録する。
     */
    @Test
    fun phrasesStopBeforeUserDictionaryWordsAndFollowLearningRules() {
        val learning = learningStore()
        val fixture = Fixture(dictionary = FakeLookup("うずみ" to "渦見"), learning = learning)
        fixture.startReady()
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "きょうは"))
        fixture.executor.runAll()
        val long = "長".repeat(26)

        fixture.worker.learnCommitted(
            7,
            listOf(
                listOf(
                    LearnedSegment("きょう", "今日", chosen = true),
                    LearnedSegment("は", "は"),
                    LearnedSegment("うずみ", "渦見"),
                    LearnedSegment("です", "です"),
                ),
                listOf(LearnedSegment("いい", "いい", chosen = true), LearnedSegment("よ", "よ")),
                listOf(LearnedSegment("ながい", long, chosen = true), LearnedSegment("ながい", long)),
            ),
        )
        fixture.executor.runAll()

        assertEquals(listOf("きょうは" to "今日は"), learning.allWords().filter { it.isPhrase }.map { it.reading to it.surface })
        assertTrue(learning.exactMatches("ながい").single().surface == long)
    }

    /**
     * ライブ変換では、部分範囲の先頭からの読みに一致する句の表記を使い、句の終わりで区切って残りを別に変換する。
     * 学習禁止欄では句を参照しない。
     */
    @Test
    fun liveConversionAlignsSegmentsToLearnedPhrase() {
        val learning = learningStore().apply { recordPhrase("こうえんにいく", "校園に行く", createIfMissing = true) }
        val fixture = Fixture(learning = learning)
        fixture.startReady()
        fixture.engine.segmentsFor = { reading ->
            when (reading) {
                "こうえんにいくよ" -> listOf(EngineSegment("こうえんに", "公園に", listOf("公園に")), EngineSegment("いくよ", "行くよ", listOf("行くよ")))
                "こうえんにいく" -> listOf(EngineSegment("こうえんに", "公園に", listOf("公園に")), EngineSegment("いく", "行く", listOf("行く")))
                else -> listOf(EngineSegment(reading, reading, listOf(reading)))
            }
        }

        fixture.worker.requestLiveConversion(7, liveRequest(revision = 1, reading = "こうえんにいくよ"))
        fixture.executor.runAll()
        fixture.worker.requestLiveConversion(7, liveRequest(revision = 2, reading = "こうえんにいくよ", learningAllowed = false))
        fixture.executor.runAll()

        assertEquals(
            listOf(
                ResultSegment("こうえんにいく", "校園に行く", listOf("校園に行く", "公園に行く", "こうえんにいく")),
                ResultSegment("よ", "よ", listOf("よ")),
            ),
            fixture.liveResults[0].second.segments,
        )
        assertEquals(listOf("公園に", "行くよ"), fixture.liveResults[1].second.segments.map { it.surface })
    }

    /** 明示変換では、読み全体に一致する句をエンジンの第一候補の前に置き、表示はエンジンの第一候補のままにする。 */
    @Test
    fun explicitConversionPlacesWholeReadingPhraseFirst() {
        val learning = learningStore().apply { recordPhrase("こうえんにいく", "校園に行く", createIfMissing = true) }
        val fixture = Fixture(learning = learning)
        fixture.startReady()

        fixture.worker.requestConversion(request(reading = "こうえんにいく"))
        fixture.executor.runAll()

        val result = (fixture.outcomes.single() as ConversionOutcome.Converted).result
        assertEquals(listOf("校園に行く", "こうえんにいく"), result.headCandidates.map { it.value })
        assertEquals("こうえんにいく", result.headCandidates.first().learnedReading)
        assertEquals("こうえんにいく", result.display)
        assertTrue(result.isConsistent)
    }

    /** エンジンがReadyでなければ、全消去はworkerへ依頼せず、エンジンの学習ファイルを直接消す経路へ回す。 */
    @Test
    fun clearAllFallsBackToFilesWhenEngineIsNotReady() {
        var fileClears = 0
        val learning = LearningStore(learningFile, clock = { now }, clearEngineFiles = { fileClears += 1 })
        val fixture = Fixture(learning = learning)
        fixture.engine.loadResult = { EngineHealth.Unavailable(EngineUnavailableReason.MINIMAL_ENGINE) }
        fixture.worker.start()
        fixture.executor.runAll()
        fixture.engine.calls.clear()

        learning.clearAll()
        fixture.executor.runAll()

        assertEquals(1, fileClears)
        assertFalse(fixture.engine.calls.contains("clearLearning"))
    }

    /** 学習の全消去は、動いているworkerを通じてエンジンの学習も消す。 */
    @Test
    fun clearAllClearsEngineLearningThroughWorker() {
        val learning = learningStore()
        val fixture = Fixture(learning = learning)
        fixture.startReady()
        fixture.engine.calls.clear()

        learning.clearAll()
        fixture.executor.runAll()

        assertEquals(listOf("clearLearning"), fixture.engine.calls)
    }

    /** ニューラル変換を選ぶと、ライブ変換のかなの連なりをモデルで変換し、辞書（Mozc）の文節へ切り分ける。 */
    @Test
    fun liveConversionUsesNeuralBackendWhenSelected() {
        val fixture = Fixture()
        fixture.startReady()
        val backend = FakeNeuralBackend { reading, _ -> NeuralModelOutput.Completed(if (reading == "かんじ") "漢字" else reading) }
        fixture.worker.setNeuralBackend(backend)

        fixture.worker.requestLiveConversion(1, liveRequest(revision = 1, reading = "かんじ"))
        fixture.executor.runAll()

        assertEquals(listOf("漢字"), fixture.liveResults.single().second.segments.map { it.surface })
        assertEquals(listOf("かんじ" to ""), backend.calls)
        // 評価モードでなければ報告を作らない
        assertTrue(fixture.reports.isEmpty())
    }

    /** 学習禁止欄では、保護範囲の表記を含む左文脈をモデルへ渡さない。通常の欄では渡す。 */
    @Test
    fun learningForbiddenFieldWithholdsLeftContext() {
        for (learningAllowed in listOf(true, false)) {
            val fixture = Fixture()
            fixture.startReady()
            val backend = FakeNeuralBackend { reading, _ -> NeuralModelOutput.Completed(reading) }
            fixture.worker.setNeuralBackend(backend)
            val identity = RequestIdentity(
                1, 1, "かんじです", 0, 5, listOf(ProtectedRange(0, 3, "漢字")), 5, 0,
            )

            fixture.worker.requestLiveConversion(1, LiveRequest(identity, "かんじです", learningAllowed))
            fixture.executor.runAll()

            val expected = if (learningAllowed) "漢字" else ""
            assertEquals("learningAllowed=$learningAllowed", listOf("です" to expected), backend.calls)
        }
    }

    /** 新しいライブ変換の要求は、前の要求の推論を止める（通し番号が増える）。 */
    @Test
    fun newLiveRequestCancelsPreviousInference() {
        val fixture = Fixture()
        fixture.startReady()
        val backend = FakeNeuralBackend { reading, _ -> NeuralModelOutput.Completed(reading) }
        fixture.worker.setNeuralBackend(backend)

        fixture.worker.requestLiveConversion(1, liveRequest(revision = 1, reading = "か"))
        fixture.worker.requestLiveConversion(1, liveRequest(revision = 2, reading = "かん"))

        assertEquals(listOf(1L, 2L), backend.cancelledBefore)
        fixture.executor.runAll()
        assertEquals(listOf(2L), fixture.liveResults.map { it.second.identity.revision })
    }

    /** 推論が中断されたら、その要求の結果は返さない。評価モードでは中断を数えた報告だけを返す。 */
    @Test
    fun cancelledInferenceReturnsNoResult() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.evaluating = true
        fixture.worker.setNeuralBackend(FakeNeuralBackend(cancelled = true) { _, _ -> NeuralModelOutput.Cancelled })

        fixture.worker.requestLiveConversion(1, liveRequest(revision = 1, reading = "かんじ"))
        fixture.executor.runAll()

        assertTrue(fixture.liveResults.isEmpty())
        val report = fixture.reports.single().second
        assertEquals(null, report.identity)
        assertEquals(1, report.counts.cancelled)
    }

    /**
     * 別プロセスの推論serviceが期限（300 ms）までに答えなければ、Mozcの結果を返す。評価モードでは時間超過を数え、
     * 推論時間を時間超過の値で打ち切って残す。
     */
    @Test
    fun timeoutFallsBackToMozcAndIsCounted() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.evaluating = true
        fixture.engine.segmentsFor = { reading -> listOf(EngineSegment(reading, "Mozc:$reading", listOf("Mozc:$reading"))) }
        val silent = object : dev.uzumi.ime.neural.NeuralRuntimePort {
            val cancels = mutableListOf<Long>()
            override fun convert(requestId: Long, prompt: ByteArray, parseSpecial: Boolean, maxTokens: Int) = Unit
            override fun cancel(requestId: Long) {
                cancels += requestId
            }
        }
        val client = dev.uzumi.ime.neural.NeuralRuntimeClient(NeuralModelSpec.ZENZ_XSMALL).apply {
            attach(silent)
            onLoaded(true)
        }
        fixture.worker.setNeuralBackend(client)

        val started = System.nanoTime()
        fixture.worker.requestLiveConversion(1, liveRequest(revision = 1, reading = "かんじ"))
        fixture.executor.runAll()
        val waitedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("Mozc:かんじ"), fixture.liveResults.single().second.segments.map { it.surface })
        assertTrue("waited $waitedMillis ms", waitedMillis in 250..1_000)
        assertEquals(listOf(1L), silent.cancels)
        val report = fixture.reports.single().second
        assertEquals(1, report.counts.timeouts)
        assertEquals(1, report.counts.modelRequests)
        assertEquals(1, report.counts.mozcFallbacks)
        assertEquals(listOf(300.0), report.samples.map { it.millis })
    }

    /** Mozcだけの条件（M）でも評価用の報告を作り、ニューラルの項目は0になる。 */
    @Test
    fun mozcOnlyConditionReportsZeroNeuralCounts() {
        val fixture = Fixture()
        fixture.startReady()
        fixture.evaluating = true

        fixture.worker.requestLiveConversion(1, liveRequest(revision = 1, reading = "かんじ"))
        fixture.executor.runAll()

        val report = fixture.reports.single().second
        assertEquals(fixture.liveResults.single().second.identity, report.identity)
        assertEquals(dev.uzumi.ime.evaluation.NeuralCounts(), report.counts)
        assertTrue(report.samples.isEmpty())
    }

    // 学習キャッシュのテスト用の保存先と時計。
    private val learningDirectory: File = Files.createTempDirectory("worker-learning").toFile().apply { deleteOnExit() }
    private val learningFile = File(learningDirectory, "learning.tsv")
    private var now = 0L

    private fun learningStore() = LearningStore(learningFile, clock = { now })

    private fun candidateRequest(
        reading: String,
        preceding: String = "",
        following: String = "",
        learningAllowed: Boolean = true,
    ) = CandidateRequest(
        sessionEpoch = 1,
        revision = 1,
        converterGeneration = 0,
        segmentId = 3,
        readingStart = 0,
        readingEnd = reading.length,
        reading = reading,
        preceding = preceding,
        following = following,
        learningAllowed = learningAllowed,
    )

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
    private class Fixture(dictionary: FakeLookup? = null, learning: LearningStore? = null) {
        val engine = FakeConversionEngine()
        val executor = ManualExecutor()
        val outcomes = mutableListOf<ConversionOutcome>()
        val healthChanges = mutableListOf<EngineHealth>()
        val liveResults = mutableListOf<Pair<Long, LiveResult>>()
        val candidateResults = mutableListOf<Pair<Long, CandidateResult>>()
        val reports = mutableListOf<Pair<Long, LiveRequestReport>>()
        var evaluating = false
        val worker = ConversionWorker(
            engine = engine,
            executor = executor,
            onHealthChanged = { healthChanges += it },
            onOutcome = { outcomes += it },
            onLiveResult = { epoch, result -> liveResults += epoch to result },
            onCandidateResult = { epoch, result -> candidateResults += epoch to result },
            userDictionary = { dictionary },
            learningStore = { learning },
            onLiveReport = { epoch, report -> reports += epoch to report },
            evaluationActive = { evaluating },
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

/** 呼ばれた読みと左文脈、中断の通し番号を記録し、決めた結果を返すfakeのニューラル変換の窓口。 */
private class FakeNeuralBackend(
    private val cancelled: Boolean = false,
    private val output: (String, String) -> NeuralModelOutput,
) : NeuralBackend {
    override val spec = NeuralModelSpec.JINEN_XSMALL
    val calls = mutableListOf<Pair<String, String>>()
    val cancelledBefore = mutableListOf<Long>()

    override fun modelFor(
        deadlineNanos: Long,
        owner: Long,
        isSuperseded: () -> Boolean,
        record: (NeuralCallRecord) -> Unit,
    ) = NeuralKanaKanjiModel { reading, context ->
        calls += reading to context
        val outcome = if (cancelled) NeuralCallOutcome.CANCELLED else NeuralCallOutcome.COMPLETED
        record(NeuralCallRecord(sent = true, cacheHit = false, millis = 5.0, outcome = outcome))
        output(reading, context)
    }

    override fun cancelInFlight(newOwner: Long) {
        cancelledBefore += newOwner
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

    override fun convert(sessionId: Long, reading: String, headLength: Int?): EngineConversion {
        calls += if (headLength == null) "convert($sessionId,$reading)" else "convert($sessionId,$reading,$headLength)"
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

    // 一文節として変換したときに返す文節（前の読み、読み、後の読み）。既定では読みをそのまま返す。
    var segmentFor: (String, String, String) -> EngineSegment? = { _, reading, _ -> EngineSegment(reading, reading, listOf(reading)) }

    override fun convertSegment(sessionId: Long, preceding: String, reading: String, following: String): EngineSegment? {
        calls += "convertSegment($sessionId,$preceding|$reading|$following)"
        return segmentFor(preceding, reading, following)
    }

    override fun clearLearning(): Boolean {
        calls += "clearLearning"
        return true
    }

    override fun learnSegments(sessionId: Long, segments: List<LearnedSegment>): Boolean {
        calls += "learnSegments($sessionId," + segments.joinToString("|") { "${it.reading}=${it.surface}" } + ")"
        return learnAccepted(segments)
    }
}
