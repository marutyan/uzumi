package dev.uzumi.ime.conversion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateList
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWord
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Result
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config

/** Mozc protocolの命令列と、辞書なし状態の検出を偽のnativeで検証する。 */
class MozcConversionEngineTest {
    /** 入力途中のsuggestion要求は混合候補と部分予測を有効にし、読みが続く候補を返す。 */
    @Test
    fun suggestRequestsFullReadingPredictions() {
        val native = FakeMozcNative().apply {
            respond = { input ->
                if (input.type == Input.CommandType.SEND_KEY && input.key.hasKeyString()) {
                    predictionOutput(
                        CandidateWord.newBuilder().setId(4).setKey("ありがとう").setValue("ありがとう"),
                        CandidateWord.newBuilder().setId(8).setKey("ありがとうございます").setValue("ありがとうございます"),
                        CandidateWord.newBuilder().setId(9).setKey("ありがとう").setValue("有難う"),
                        CandidateWord.newBuilder().setId(10).setKey("ありがと").setValue("有難う"),
                    )
                } else {
                    Output.newBuilder().setConsumed(true).build()
                }
            }
        }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })
        assertTrue(engine.setIncognito(true, kanaModifierInsensitive = true))
        native.inputs.clear()

        assertEquals(
            listOf(PredictionCandidate(4, "ありがとう", "ありがとう"),
                PredictionCandidate(8, "ありがとうございます", "ありがとうございます"),
                PredictionCandidate(9, "有難う", "ありがとう")),
            engine.suggest(sessionId = 5, reading = "ありがと"),
        )
        val inputs = native.inputs.filter { it.type == Input.CommandType.SEND_KEY && it.key.hasKeyString() }
        assertEquals(listOf("あ", "り", "が", "と"), inputs.map { it.key.keyString })
        // 途中のキーでは予測を計算させず、最後のキーでだけ既定どおり予測を求める。
        assertTrue(inputs.dropLast(1).all { it.hasRequestSuggestion() && !it.requestSuggestion })
        assertTrue(inputs.last().requestSuggestion)
        assertFalse(inputs.any { it.hasRequest() })
        val requests = native.inputs.filter { it.type == Input.CommandType.SET_REQUEST }.map { it.request }
        assertEquals(2, requests.size)
        assertTrue(requests.first().isIncognitoMode)
        assertTrue(requests.first().mixedConversion)
        assertFalse(requests.first().zeroQuerySuggestion)
        assertTrue(requests.first().autoPartialSuggestion)
        assertTrue(requests.last().isIncognitoMode)
        assertFalse(requests.last().mixedConversion)
        assertFalse(requests.last().zeroQuerySuggestion)
        assertFalse(requests.last().autoPartialSuggestion)
        // 予測の一時設定と、その後の通常設定への復元のどちらでも表記ゆれ変換を保つ。
        assertTrue(requests.all { it.kanaModifierInsensitiveConversion })
        assertFalse(native.inputs.any { it.key.specialKey == KeyEvent.SpecialKey.SPACE })
    }

    /** 表示候補を除外する前にMozcから50件を取得し、候補バー向けの早期切り捨てをしない。 */
    @Test
    fun suggestReadsUpToFiftyCandidates() {
        val native = FakeMozcNative().apply {
            respond = { input ->
                if (input.type == Input.CommandType.SEND_KEY && input.key.hasKeyString()) {
                    predictionOutput(*List(55) { index ->
                        CandidateWord.newBuilder()
                            .setId(index)
                            .setKey("ありがとう")
                            .setValue("候補$index")
                    }.toTypedArray())
                } else {
                    Output.newBuilder().setConsumed(true).build()
                }
            }
        }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val candidates = engine.suggest(sessionId = 5, reading = "ありがと")

        assertEquals(50, candidates.size)
        assertEquals("候補0", candidates.first().value)
        assertEquals("候補49", candidates.last().value)
    }

    /**
     * 表記ゆれ変換が有効なら、小書きを打ち分けなかった読み（「りようかいしました」）に対し、読み全体の変換結果と
     * 小書き・濁点の違いを除いて続く予測も残す。候補はsession_handler_mainで実際のMozcが返した形を写したもの。
     */
    @Test
    fun suggestKeepsWholeReadingAndKanaVariantCandidates() {
        val native = kanaVariantPredictionNative()
        val engine = MozcConversionEngine(native, { File("profile") }, { null })
        assertTrue(engine.setIncognito(false, kanaModifierInsensitive = true))

        assertEquals(
            listOf(
                // 12キーの表記ゆれを許可した場合も、入力側の読みから確定範囲を取る。
                PredictionCandidate(0, "了解しました", partialKey = "りょうかいし"),
                PredictionCandidate(1, "利用介しました", "りようかいしました"),
                PredictionCandidate(4, "利用開始ました", partialKey = "りようかいし"),
                PredictionCandidate(5, "リヨウカイシマシタ", "りようかいしました"),
                PredictionCandidate(6, "領解しました", "りょうかいしました"),
                PredictionCandidate(7, "りょうかいしました", "りょうかいしました"),
            ),
            engine.suggest(sessionId = 5, reading = "りようかいしました"),
        )
    }

    /** 12キー以外の入力や評価の記録中など表記ゆれ変換を使わない間は、Requestで無効にし、読みの違う候補を従来どおり除く。 */
    @Test
    fun suggestWithoutKanaModifierInsensitiveDropsVariantReadings() {
        val native = kanaVariantPredictionNative()
        val engine = MozcConversionEngine(native, { File("profile") }, { null })
        assertTrue(engine.setIncognito(false))

        assertEquals(
            listOf(
                PredictionCandidate(1, "利用介しました", "りようかいしました"),
                PredictionCandidate(4, "利用開始ました", partialKey = "りようかいし"),
                PredictionCandidate(5, "リヨウカイシマシタ", "りようかいしました"),
            ),
            engine.suggest(sessionId = 5, reading = "りようかいしました"),
        )
        val requests = native.inputs.filter { it.type == Input.CommandType.SET_REQUEST }.map { it.request }
        assertEquals(3, requests.size)
        assertTrue(requests.none { it.kanaModifierInsensitiveConversion })
    }

    /** 実際のMozcが「りようかいしました」の入力途中に返した候補窓（表記ゆれ変換あり）を写した偽native。 */
    private fun kanaVariantPredictionNative() = FakeMozcNative().apply {
        respond = { input ->
            if (input.type == Input.CommandType.SEND_KEY && input.key.hasKeyString()) {
                predictionOutput(
                    CandidateWord.newBuilder().setId(0).setKey("りょうかいし").setValue("了解しました"),
                    CandidateWord.newBuilder().setId(1).setValue("利用介しました").setNumSegmentsInCandidate(2),
                    CandidateWord.newBuilder().setId(4).setKey("りようかいし").setValue("利用開始ました"),
                    CandidateWord.newBuilder().setId(5).setValue("リヨウカイシマシタ"),
                    CandidateWord.newBuilder().setId(6).setKey("りょうかいしました").setValue("領解しました"),
                    CandidateWord.newBuilder().setId(7).setKey("りょうかいしました").setValue("りょうかいしました"),
                    // 打ったとおりのひらがなと、読みが入力と関係しない候補は出さない。
                    CandidateWord.newBuilder().setId(8).setValue("りようかいしました"),
                    CandidateWord.newBuilder().setId(9).setKey("りんご").setValue("林檎"),
                )
            } else {
                Output.newBuilder().setConsumed(true).build()
            }
        }
    }

    /** 表記ゆれ変換の候補（keyが小書きの違う読み）も、文節の読みだけを置き換える候補として文節の候補へ入れる。 */
    @Test
    fun convertSegmentsKeepsKanaVariantCandidatesOnlyWhenEnabled() {
        val respond: (Input) -> Output = { input ->
            if (input.key.specialKey == KeyEvent.SpecialKey.SPACE) {
                Output.newBuilder().setConsumed(true)
                    .setPreedit(
                        Preedit.newBuilder().setCursor(0).addSegment(
                            Preedit.Segment.newBuilder().setKey("りようかいしました").setValue("了解しました")
                                .setValueLength(6).setAnnotation(Preedit.Segment.Annotation.HIGHLIGHT),
                        ),
                    )
                    .setAllCandidateWords(
                        CandidateList.newBuilder()
                            .addCandidates(CandidateWord.newBuilder().setId(0).setKey("りょうかいし").setValue("了解しました"))
                            .addCandidates(CandidateWord.newBuilder().setId(1).setKey("りようかいし").setValue("利用開始ました")),
                    )
                    .build()
            } else {
                Output.newBuilder().setConsumed(true).build()
            }
        }
        val enabled = MozcConversionEngine(FakeMozcNative().apply { this.respond = respond }, { File("profile") }, { null })
        assertTrue(enabled.setIncognito(false, kanaModifierInsensitive = true))
        assertEquals(
            listOf("了解しました", "利用開始ました"),
            enabled.convertSegments(sessionId = 5, reading = "りようかいしました")!!.single().candidates,
        )

        val disabled = MozcConversionEngine(
            FakeMozcNative().apply { this.respond = respond }, { File("profile") }, { null },
        )
        assertTrue(disabled.setIncognito(false))
        assertEquals(
            listOf("利用開始ました"),
            disabled.convertSegments(sessionId = 5, reading = "りようかいしました")!!.single().candidates,
        )
    }

    /** 表記ゆれ変換はConfigとRequestの論理積で働くため、読み込み時にConfigを一度だけ有効にする。 */
    @Test
    fun loadEnablesKanaModifierInsensitiveConfigOnce() {
        val profile = createTempDir()
        val native = FakeMozcNative().apply { version = "2.31.0" }
        val engine = MozcConversionEngine(native, { profile }, { File(profile, "mozc.data") })

        assertEquals(EngineHealth.Ready("2.31.0"), engine.load())
        assertEquals(listOf(Input.CommandType.GET_CONFIG, Input.CommandType.SET_CONFIG), native.inputs.map { it.type })
        assertTrue(native.inputs.last().config.useKanaModifierInsensitiveConversion)

        // 保存済みの設定で既に有効なら、設定を書き直さない。
        native.inputs.clear()
        native.respond = { input ->
            if (input.type == Input.CommandType.GET_CONFIG) {
                Output.newBuilder().setConfig(Config.newBuilder().setUseKanaModifierInsensitiveConversion(true)).build()
            } else {
                Output.newBuilder().setConsumed(true).build()
            }
        }
        assertEquals(EngineHealth.Ready("2.31.0"), engine.load())
        assertEquals(listOf(Input.CommandType.GET_CONFIG), native.inputs.map { it.type })
        profile.deleteRecursively()
    }

    /** 次の語の要求は専用NWP命令へ確定文脈を載せ、Mozcが返した候補をそのまま識別する。 */
    @Test
    fun requestNextWordPredictionUsesSurroundingContext() {
        val native = FakeMozcNative().apply {
            respond = { input ->
                if (input.command.type == SessionCommand.CommandType.REQUEST_NWP) {
                    predictionOutput(
                        CandidateWord.newBuilder().setId(3).setKey("てんき").setValue("天気"),
                        CandidateWord.newBuilder().setId(5).setKey("よほう").setValue("予報"),
                    )
                } else {
                    Output.newBuilder().setConsumed(true).build()
                }
            }
        }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertEquals(
            listOf(PredictionCandidate(3, "天気", "てんき"), PredictionCandidate(5, "予報", "よほう")),
            engine.requestNextWordPrediction(sessionId = 7, precedingText = "明日の"),
        )
        val input = native.inputs.single { it.type == Input.CommandType.SEND_COMMAND }
        assertEquals(Input.CommandType.SEND_COMMAND, input.type)
        assertEquals(SessionCommand.CommandType.REQUEST_NWP, input.command.type)
        assertEquals("明日の", input.context.precedingText)
        assertTrue(input.requestSuggestion)
        val requests = native.inputs.filter { it.type == Input.CommandType.SET_REQUEST }.map { it.request }
        assertEquals(2, requests.size)
        assertTrue(requests.first().zeroQuerySuggestion)
        assertTrue(requests.first().mixedConversion)
        assertTrue(requests.first().autoPartialSuggestion)
        assertFalse(requests.last().zeroQuerySuggestion)
        assertFalse(requests.last().mixedConversion)
        assertFalse(requests.last().autoPartialSuggestion)
    }

    /** 予測の選択はSUBMIT_CANDIDATEに予測候補のIDを付けて送り、Mozcの履歴へ学習させる。 */
    @Test
    fun commitPredictionSendsCandidateId() {
        val native = FakeMozcNative().apply {
            respond = { input ->
                if (input.type == Input.CommandType.SEND_COMMAND &&
                    input.command.type == SessionCommand.CommandType.SUBMIT_CANDIDATE
                ) {
                    Output.newBuilder().setConsumed(true).setResult(Result.newBuilder()
                        .setType(Result.ResultType.STRING)
                        .setValue("候補")
                        .setKey("よみ")).build()
                } else {
                    Output.newBuilder().setConsumed(true).build()
                }
            }
        }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertTrue(engine.commitPrediction(sessionId = 5, candidateId = 3))

        val command = native.inputs.single().command
        assertEquals(SessionCommand.CommandType.SUBMIT_CANDIDATE, command.type)
        assertEquals(3, command.id)
    }

    /** SUBMIT_CANDIDATEが確定結果を返さない場合は、選択成功として扱わない。 */
    @Test
    fun submitPredictionWithoutResultFails() {
        val engine = MozcConversionEngine(FakeMozcNative(), { File("profile") }, { null })

        assertNull(engine.submitPrediction(sessionId = 5, candidateId = 3))
        assertFalse(engine.commitPrediction(sessionId = 5, candidateId = 3))
    }

    /** SUBMIT_CANDIDATEの出力からMozcが実際に残した読みと確定keyを返す。 */
    @Test
    fun submitPredictionReturnsActualRemainingReading() {
        val native = FakeMozcNative().apply {
            respond = { input ->
                if (input.type == Input.CommandType.SEND_COMMAND &&
                    input.command.type == SessionCommand.CommandType.SUBMIT_CANDIDATE
                ) {
                    Output.newBuilder()
                        .setConsumed(true)
                        .setResult(Result.newBuilder()
                            .setType(Result.ResultType.STRING)
                            .setValue("今日")
                            .setKey("きょう"))
                        .setPreedit(Preedit.newBuilder()
                            .setCursor(1)
                            .addSegment(Preedit.Segment.newBuilder()
                                .setAnnotation(Preedit.Segment.Annotation.UNDERLINE)
                                .setKey("は")
                                .setValue("は")
                                .setValueLength(1)))
                        .build()
                } else {
                    Output.newBuilder().setConsumed(true).build()
                }
            }
        }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertEquals(
            EnginePredictionCommit(remainingReading = "は", resultReading = "きょう"),
            engine.submitPrediction(sessionId = 5, candidateId = 21),
        )
    }

    /** libmozc.soが無いビルドでは、辞書を探さずにUnavailableを返す。 */
    @Test
    fun missingNativeLibraryIsUnavailable() {
        val native = FakeMozcNative().apply { libraryLoaded = false }
        val engine = MozcConversionEngine(native, { File("profile") }, { error("辞書を探してはいけない") })

        assertEquals(EngineHealth.Unavailable(EngineUnavailableReason.NATIVE_LIBRARY_MISSING), engine.load())
        assertTrue(native.postLoadDataPaths.isEmpty())
    }

    /** 辞書が無い場合も存在しないpathでnativeを初期化し、minimal engineのversionを検出する。 */
    @Test
    fun missingDataIsDetectedAsMinimalEngine() {
        val profile = createTempDir()
        val native = FakeMozcNative().apply { version = "0.0.0" }
        val engine = MozcConversionEngine(native, { profile }, { null })

        assertEquals(EngineHealth.Unavailable(EngineUnavailableReason.MINIMAL_ENGINE), engine.load())
        assertFalse(File(native.postLoadDataPaths.single()).exists())
        profile.deleteRecursively()
    }

    /** 辞書のversionが得られれば、Readyとしてversionを返す。 */
    @Test
    fun dataVersionIsReported() {
        val profile = createTempDir()
        val native = FakeMozcNative().apply { version = "2.31.0" }
        val engine = MozcConversionEngine(native, { profile }, { File(profile, "mozc.data") })

        assertEquals(EngineHealth.Ready("2.31.0"), engine.load())
        profile.deleteRecursively()
    }

    /**
     * 変換は読みの各文字をAS_ISで送りSPACEで変換する。focusを右へ動かして各文節の候補を集め、候補は選ばない。
     * 新しいsessionでは学習を消さないようREVERTを送らない。
     */
    @Test
    fun convertRebuildsCompositionFromReading() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val conversion = engine.convert(sessionId = 5, reading = "きょうはいい")!!

        val inputs = native.inputs
        assertFalse(inputs.any { it.command.type == SessionCommand.CommandType.REVERT })
        val characters = inputs.filter { it.type == Input.CommandType.SEND_KEY && it.key.hasKeyString() }
        assertEquals(listOf("き", "ょ", "う", "は", "い", "い"), characters.map { it.key.keyString })
        assertTrue(characters.all { it.key.inputStyle == KeyEvent.InputStyle.AS_IS })
        assertTrue(inputs.any { it.key.specialKey == KeyEvent.SpecialKey.SPACE })
        assertEquals(1, inputs.count { it.key.specialKey == KeyEvent.SpecialKey.RIGHT && it.key.modifierKeysCount == 0 })
        assertFalse(inputs.any { it.command.type == SessionCommand.CommandType.SELECT_CANDIDATE })
        assertTrue(inputs.all { it.id == 5L })
        assertEquals(listOf("きょうは" to "今日は", "いい" to "いい"), conversion.segments.map { it.reading to it.value })
        assertEquals(
            listOf(
                ConversionCandidate(0, "今日は", "きょうは"),
                ConversionCandidate(1, "京は", "きょうは"),
            ),
            conversion.headCandidates,
        )
        assertEquals(conversion.headCandidates, conversion.segments[0].candidates)
        assertEquals(
            listOf(ConversionCandidate(0, "いい", "いい"), ConversionCandidate(1, "良い", "いい")),
            conversion.segments[1].candidates,
        )
        assertTrue(mozc.submitted.isEmpty())
    }

    /** REVERTはpreeditが残るときだけ送り、確定で待機状態へ戻った後の次の変換では送らない。 */
    @Test
    fun revertIsSentOnlyWhilePreeditRemains() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })
        assertTrue(engine.convert(sessionId = 5, reading = "きょうはいい") != null)

        native.inputs.clear()
        assertTrue(engine.convert(sessionId = 5, reading = "きょうはいい") != null)
        assertEquals(SessionCommand.CommandType.REVERT, native.inputs.first().command.type)

        assertTrue(engine.commitAll(sessionId = 5))
        native.inputs.clear()
        assertTrue(engine.convert(sessionId = 5, reading = "きょうはいい") != null)
        assertFalse(native.inputs.any { it.command.type == SessionCommand.CommandType.REVERT })
    }

    /** 候補ID・読み・表記を含むMozc応答を作り、JNIなしで候補抽出を確かめる。 */
    private fun predictionOutput(vararg words: CandidateWord.Builder): Output = Output.newBuilder()
        .setConsumed(true)
        .setAllCandidateWords(CandidateList.newBuilder().addAllCandidates(words.map(CandidateWord.Builder::build)))
        .build()

    /** Mozcが失敗を返した場合は変換結果を作らない。 */
    @Test
    fun sessionFailureReturnsNull() {
        val native = FakeMozcNative()
        native.respond = { Output.newBuilder().setErrorCode(Output.ErrorCode.SESSION_FAILURE).build() }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertNull(engine.convert(sessionId = 5, reading = "かんじ"))
        assertNull(engine.createSession())
    }

    /** incognitoと表記ゆれ変換の有無は、SET_REQUESTのRequestで一緒に指定する。 */
    @Test
    fun incognitoUsesSetRequest() {
        val native = FakeMozcNative()
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertTrue(engine.setIncognito(true, kanaModifierInsensitive = true))

        val input = native.inputs.single()
        assertEquals(Input.CommandType.SET_REQUEST, input.type)
        assertTrue(input.request.isIncognitoMode)
        assertTrue(input.request.kanaModifierInsensitiveConversion)
        assertFalse(input.request.mixedConversion)
    }

    /** 学習の消去は、文節履歴と予測の履歴を消す二つの命令をsessionなしで送る。 */
    @Test
    fun clearLearningSendsHistoryAndPredictionClears() {
        val native = FakeMozcNative()
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertTrue(engine.clearLearning())

        assertEquals(
            listOf(Input.CommandType.CLEAR_USER_HISTORY, Input.CommandType.CLEAR_USER_PREDICTION),
            native.inputs.map { it.type },
        )
    }

    /**
     * ライブ変換の変換は予測を求めずに読みを積んでSPACEで変換し、focusを右へ動かして各文節の候補を読む。
     * 読みの異なる予測候補と複数文節をまとめる候補は除き、確定しない。
     */
    @Test
    fun convertSegmentsReadsCandidatesOfEverySegmentWithoutCommitting() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val segments = engine.convertSegments(sessionId = 5, reading = "きょうはいい")!!

        assertEquals(
            listOf(
                EngineSegment("きょうは", "今日は", listOf("今日は", "京は")),
                EngineSegment("いい", "いい", listOf("いい", "良い")),
            ),
            segments,
        )
        val characterKeys = native.inputs.filter { it.type == Input.CommandType.SEND_KEY && it.key.hasKeyString() }
        assertTrue(characterKeys.all { it.hasRequestSuggestion() && !it.requestSuggestion })
        assertEquals(1, native.inputs.count { it.key.specialKey == KeyEvent.SpecialKey.RIGHT })
        assertFalse(native.inputs.any { it.command.type == SessionCommand.CommandType.SUBMIT })
        assertTrue(mozc.submitted.isEmpty())
    }

    /**
     * Mozcは自立語部分の読み（content_key）が文節の読みと違う候補にkeyを付ける。「講演に」のkey「こうえん」は
     * 文節の読みの接頭辞なので、予測として除かずライブ変換の候補に残す（Phase 2cで「講演に」が出なかった原因）。
     */
    @Test
    fun convertSegmentsKeepsCandidatesWhoseKeyIsTheirContentReading() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val segments = engine.convertSegments(sessionId = 5, reading = "こうえんに")!!

        assertEquals(listOf(EngineSegment("こうえんに", "公園に", listOf("公園に", "項園に", "講演に"))), segments)
    }

    /**
     * 明示変換の各文節の候補は、ライブ変換で全文節を変換したときの同じ文節の候補と同じ規則（読みを変えない候補だけ）で集める。
     * 注目した文節の取り直しは各候補を一時的に選んで読みを確かめ、複数の文節をまとめる候補だけを除く。確定はしない。
     */
    @Test
    fun convertSegmentCollectsSameCandidatesAsExplicitConversion() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        for (reading in listOf("こうえんに", "きょうはいい")) {
            val explicit = engine.convert(sessionId = 5, reading = reading)!!.segments
                .map { segment -> segment.candidates.map { it.value } }
            val live = engine.convertSegments(sessionId = 5, reading = reading)!!.map { it.candidates }

            assertEquals(live, explicit)
        }
        val lecture = engine.convertSegment(sessionId = 5, preceding = "", reading = "こうえんに", following = "")!!
        assertEquals(listOf("公園に", "項園に", "講演に", "予測"), lecture.candidates)
        assertFalse(native.inputs.any { it.command.type == SessionCommand.CommandType.SUBMIT })
        assertTrue(mozc.submitted.isEmpty())
    }

    /** 前の読みを文脈にするときは、前を一文節に合わせてからfocusを移し、対象の文節の幅を合わせて候補を集める。 */
    @Test
    fun convertSegmentAlignsContextAndTargetSegments() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val after = engine.convertSegment(sessionId = 5, preceding = "きょうは", reading = "いい", following = "")!!
        assertEquals(EngineSegment("いい", "いい", listOf("いい", "良い", "予測")), after)
        assertEquals(1, native.inputs.count { it.key.specialKey == KeyEvent.SpecialKey.RIGHT && it.key.modifierKeysCount == 0 })

        native.inputs.clear()
        val shrunk = engine.convertSegment(sessionId = 5, preceding = "", reading = "きょう", following = "はいい")!!
        assertEquals(EngineSegment("きょう", "今日", listOf("今日", "京", "予測")), shrunk)
        assertEquals(
            1,
            native.inputs.count {
                it.key.specialKey == KeyEvent.SpecialKey.LEFT && KeyEvent.ModifierKey.SHIFT in it.key.modifierKeysList
            },
        )
    }

    /**
     * 明示変換で先頭文節の書記素数を指定すると、Shift+左・右で先頭文節の幅を合わせてから候補を集める。
     */
    @Test
    fun convertWithSegmentLengthsResizesHeadSegment() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val shrunk = engine.convert(sessionId = 5, reading = "きょうはいい", segmentLengths = listOf(3))!!
        assertEquals(listOf("きょう" to "今日", "はいい" to "はいい"), shrunk.segments.map { it.reading to it.value })
        assertEquals(listOf("今日", "京"), shrunk.headCandidates.map { it.value })

        native.inputs.clear()
        val expanded = engine.convert(sessionId = 5, reading = "きょうはいい", segmentLengths = listOf(5))!!
        assertEquals(listOf("きょうはい", "い"), expanded.segments.map { it.reading })
        assertEquals(
            1,
            native.inputs.count {
                it.key.specialKey == KeyEvent.SpecialKey.RIGHT && KeyEvent.ModifierKey.SHIFT in it.key.modifierKeysList
            },
        )
    }

    /**
     * 先頭以外の文節を伸縮するときは、前の文節から順に幅を合わせて利用者が決めた区切りを保ち、
     * focusを先頭へ戻してから全文節の候補を集め直す。
     */
    @Test
    fun convertWithSegmentLengthsKeepsPrecedingBoundaries() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val resized = engine.convert(sessionId = 5, reading = "きょうはいい", segmentLengths = listOf(3, 2))!!

        assertEquals(listOf("きょう", "はい", "い"), resized.segments.map(ConversionSegment::reading))
        assertEquals(
            2,
            native.inputs.count {
                it.key.specialKey == KeyEvent.SpecialKey.LEFT && KeyEvent.ModifierKey.SHIFT in it.key.modifierKeysList
            },
        )
        assertEquals(1, native.inputs.count { it.key.specialKey == KeyEvent.SpecialKey.LEFT && it.key.modifierKeysCount == 0 })
        assertEquals(listOf("今日", "京"), resized.segments[0].candidates.map(ConversionCandidate::value))
        assertTrue(resized.segments.all { segment -> segment.candidates.all { it.reading == segment.reading } })
        assertTrue(mozc.submitted.isEmpty())
    }

    /** 学習は区切りを文節の幅の伸縮で合わせ、表記を候補から選んでから全体を確定する。 */
    @Test
    fun learnSegmentsAlignsBoundariesAndSurfacesBeforeSubmitting() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val learned = engine.learnSegments(
            sessionId = 5,
            segments = listOf(LearnedSegment("きょう", "京"), LearnedSegment("はいい", "はいい")),
        )

        assertTrue(learned)
        assertEquals(listOf("京はいい"), mozc.submitted)
        val shrink = native.inputs.filter {
            it.key.specialKey == KeyEvent.SpecialKey.LEFT && KeyEvent.ModifierKey.SHIFT in it.key.modifierKeysList
        }
        assertEquals(1, shrink.size)
        assertTrue(native.inputs.any { it.command.type == SessionCommand.CommandType.SELECT_CANDIDATE })
        assertFalse(native.inputs.any { it.command.type == SessionCommand.CommandType.REVERT })
    }

    /** 表記を候補から選べない場合は確定せずに取り消し、学習させない。 */
    @Test
    fun learnSegmentsRevertsWhenSurfaceIsNotACandidate() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val learned = engine.learnSegments(sessionId = 5, segments = listOf(LearnedSegment("きょうは", "登録語")))

        assertFalse(learned)
        assertTrue(mozc.submitted.isEmpty())
        assertEquals(SessionCommand.CommandType.REVERT, native.inputs.last().command.type)
    }

    /**
     * 学習の削除は、読みを一文節に変換して表記が第一候補なら、その候補IDでMozcの履歴から消し、確定せずに取り消す。
     * 実際のMozcでは「かき」の柿を学習して第一候補にした後、この命令で柿が4番目へ戻ることをsession_handler_mainで確かめた。
     */
    @Test
    fun deleteFromHistorySendsTopCandidateIdAndReverts() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertTrue(engine.deleteFromHistory(sessionId = 5, reading = "かき", surface = "柿"))

        assertEquals(listOf("柿"), mozc.deletedFromHistory)
        assertTrue(mozc.submitted.isEmpty())
        assertEquals(SessionCommand.CommandType.REVERT, native.inputs.last().command.type)
    }

    /**
     * 第一候補でない表記には削除を送らない。Mozcの文節履歴の削除は文節の第一候補の表記を消すため、
     * 送ると別の語（ここでは「下記」の前にある「柿」）の学習を消してしまう。
     */
    @Test
    fun deleteFromHistorySkipsCandidateThatIsNotFirst() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertFalse(engine.deleteFromHistory(sessionId = 5, reading = "かき", surface = "下記"))

        assertTrue(mozc.deletedFromHistory.isEmpty())
        assertTrue(mozc.submitted.isEmpty())
        val requests = native.inputs.filter { it.type == Input.CommandType.SET_REQUEST }.map { it.request }
        // 予測でも確かめた後、予測用のrequestを通常の設定へ戻す。
        assertFalse(requests.last().mixedConversion)
        assertEquals(SessionCommand.CommandType.REVERT, native.inputs.last().command.type)
    }

    private fun createTempDir(): File = Files.createTempDirectory("mozc-engine-test").toFile()
}

/** 受け取ったCommandを記録し、テストが決めたOutputを返す偽のMozc native。 */
private class FakeMozcNative : MozcNativeBridge {
    var libraryLoaded = true
    var version = "test"
    val postLoadDataPaths = mutableListOf<String>()
    val inputs = mutableListOf<Input>()
    var respond: (Input) -> Output = { Output.newBuilder().setConsumed(true).setId(9).build() }

    override fun loadLibrary(): Boolean = libraryLoaded

    override fun onPostLoad(profileDirectory: String, dataFile: String): Boolean {
        postLoadDataPaths += dataFile
        return true
    }

    override fun evalCommand(command: ByteArray): ByteArray {
        val input = Command.parseFrom(command).input
        inputs += input
        return Command.newBuilder().setInput(input).setOutput(respond(input)).build().toByteArray()
    }

    override fun dataVersion(): String = version
}

/**
 * 変換状態を持つ最小の偽Mozc。読みを積み、SPACEで決まった規則の文節に分け、focusの移動、
 * 文節の幅の伸縮、候補の選択、確定（SUBMIT）、取り消し（REVERT）を再現する。
 */
private class StatefulMozc {
    /** SUBMITで確定した表記。学習が行われたかの確認に使う。 */
    val submitted = mutableListOf<String>()

    /** DELETE_CANDIDATE_FROM_HISTORYで履歴から消すよう指定された表記。 */
    val deletedFromHistory = mutableListOf<String>()
    private val composition = StringBuilder()
    private val keys = mutableListOf<String>()
    // 文節ごとに選んでいる候補のID。
    private val selected = mutableListOf<Int>()
    private var focus = 0
    private var converting = false

    /**
     * 読みごとの候補（表記と自立語部分の読み）。自立語部分の読みは、実際のMozcと同じく文節の読みと違う場合だけ
     * CandidateWord.keyへ入る。載っていない読みは読みそのものだけを候補にする。
     */
    private val dictionary = mapOf(
        "きょうは" to listOf("今日は" to "きょう", "京は" to "きょう"),
        "いい" to listOf("いい" to null, "良い" to null),
        "きょう" to listOf("今日" to null, "京" to null),
        "かき" to listOf("柿" to null, "下記" to null),
        "こうえんに" to listOf("公園に" to "こうえん", "項園に" to null, "講演に" to "こうえん"),
    )

    /** Inputを一つ処理し、その時点のOutputを返す。 */
    fun respond(input: Input): Output {
        when {
            input.type == Input.CommandType.SEND_KEY && input.key.hasKeyString() -> {
                composition.append(input.key.keyString)
                converting = false
            }

            input.type == Input.CommandType.SEND_KEY -> handleSpecialKey(input.key)
            input.command.type == SessionCommand.CommandType.SELECT_CANDIDATE -> selected[focus] = input.command.id
            input.command.type == SessionCommand.CommandType.SUBMIT -> {
                submitted += preeditSegments().joinToString("") { it.second }
                clear()
            }

            input.command.type == SessionCommand.CommandType.REVERT -> clear()
            input.command.type == SessionCommand.CommandType.DELETE_CANDIDATE_FROM_HISTORY -> {
                deletedFromHistory += candidatesOf(keys[focus])[input.command.id].first
                // 実際のMozcと同じく、削除の後は変換を取り消してcompositionへ戻る。
                converting = false
            }
        }
        return output()
    }

    /** SPACE、focusの左右移動、Shift付きの幅の伸縮を処理する。 */
    private fun handleSpecialKey(key: KeyEvent) {
        val shift = KeyEvent.ModifierKey.SHIFT in key.modifierKeysList
        when {
            key.specialKey == KeyEvent.SpecialKey.SPACE -> {
                // 「は」の後ろで区切る単純な分割規則。
                val reading = composition.toString()
                val split = reading.indexOf("は") + 1
                keys.clear()
                keys += if (split in 1 until reading.length) {
                    listOf(reading.substring(0, split), reading.substring(split))
                } else {
                    listOf(reading)
                }
                selected.clear()
                selected += keys.map { 0 }
                focus = 0
                converting = true
            }

            key.specialKey == KeyEvent.SpecialKey.RIGHT && !shift -> focus = (focus + 1) % keys.size
            key.specialKey == KeyEvent.SpecialKey.LEFT && !shift -> focus = (focus - 1).coerceAtLeast(0)
            key.specialKey == KeyEvent.SpecialKey.RIGHT && shift && focus + 1 < keys.size -> {
                keys[focus] = keys[focus] + keys[focus + 1].first()
                keys[focus + 1] = keys[focus + 1].drop(1)
                if (keys[focus + 1].isEmpty()) {
                    keys.removeAt(focus + 1)
                    selected.removeAt(focus + 1)
                }
                for (index in focus until selected.size) selected[index] = 0
            }
            key.specialKey == KeyEvent.SpecialKey.LEFT && shift -> {
                val current = keys[focus]
                keys[focus] = current.dropLast(1)
                if (focus + 1 < keys.size) {
                    keys[focus + 1] = current.last() + keys[focus + 1]
                } else {
                    keys += current.last().toString()
                    selected += 0
                }
                selected[focus] = 0
            }
        }
    }

    /** 読みの候補を、表記とIDの組で返す。 */
    private fun candidatesOf(key: String): List<Pair<String, Int>> {
        return (dictionary[key]?.map { it.first } ?: listOf(key)).mapIndexed { index, value -> value to index }
    }

    /** 読みの候補の自立語部分の読み。文節の読みと同じならnull。 */
    private fun contentKeyOf(key: String, id: Int): String? = dictionary[key]?.getOrNull(id)?.second

    /** index番目の文節で選んでいる候補の表記。 */
    private fun selectedValue(index: Int): String = when (val id = selected[index]) {
        PREDICTION_ID -> "予測"
        MERGED_ID -> "連結"
        else -> candidatesOf(keys[index])[id].first
    }

    /** 現在のpreeditの文節（読み、表記）。二つの文節をまとめる候補を選んだ文節は、次の文節と一つにまとまる。 */
    private fun preeditSegments(): List<Pair<String, String>> {
        val segments = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < keys.size) {
            if (selected[index] == MERGED_ID && index + 1 < keys.size) {
                segments += keys[index] + keys[index + 1] to "連結"
                index += 2
            } else {
                segments += keys[index] to selectedValue(index)
                index += 1
            }
        }
        return segments
    }

    /** 変換状態をすべて捨てる。 */
    private fun clear() {
        composition.clear()
        keys.clear()
        selected.clear()
        converting = false
    }

    /** 現在の状態のOutput。変換中はfocus中の文節を強調し、その候補一覧を付ける。 */
    private fun output(): Output {
        val builder = Output.newBuilder().setConsumed(true)
        if (!converting) {
            if (composition.isNotEmpty()) {
                builder.setPreedit(
                    Preedit.newBuilder().setCursor(composition.length).addSegment(
                        Preedit.Segment.newBuilder()
                            .setKey(composition.toString())
                            .setValue(composition.toString())
                            .setValueLength(composition.length)
                            .setAnnotation(Preedit.Segment.Annotation.UNDERLINE),
                    ),
                )
            }
            return builder.build()
        }
        val preedit = Preedit.newBuilder().setCursor(0)
        preeditSegments().forEachIndexed { index, (key, value) ->
            preedit.addSegment(
                Preedit.Segment.newBuilder()
                    .setKey(key)
                    .setValue(value)
                    .setValueLength(value.length)
                    .setAnnotation(
                        if (index == focus) Preedit.Segment.Annotation.HIGHLIGHT else Preedit.Segment.Annotation.UNDERLINE,
                    ),
            )
        }
        val candidates = candidatesOf(keys[focus])
        val words = CandidateList.newBuilder().setFocusedIndex(candidates.indexOfFirst { it.second == selected[focus] }.coerceAtLeast(0))
        candidates.forEach { (value, id) ->
            val word = CandidateWord.newBuilder().setId(id).setValue(value).setNumSegmentsInCandidate(1)
            contentKeyOf(keys[focus], id)?.let(word::setKey)
            words.addCandidates(word)
        }
        // 読みが入力と異なるkeyを持つ候補（予測など）。候補の情報だけで安く集めるライブ変換の自動変換では除く。
        // 選んでも文節の読みは変わらないため、選んで確かめる明示変換と注目した文節の取り直しでは残る。
        words.addCandidates(CandidateWord.newBuilder().setId(PREDICTION_ID).setKey(keys[focus] + "ね").setValue("予測"))
        // 次の文節とまとめる候補。選ぶと文節の区切りが変わるため、ライブ変換の候補からは除かれる。
        if (focus + 1 < keys.size) {
            words.addCandidates(CandidateWord.newBuilder().setId(MERGED_ID).setValue("連結").setNumSegmentsInCandidate(2))
        }
        return builder.setPreedit(preedit).setAllCandidateWords(words).build()
    }

    private companion object {
        /** 読みが異なるkeyを持つ候補のID。 */
        const val PREDICTION_ID = 90

        /** 次の文節とまとめる候補のID。 */
        const val MERGED_ID = 91
    }
}
