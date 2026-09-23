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
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand

/** Mozc protocolの命令列と、辞書なし状態の検出を偽のnativeで検証する。 */
class MozcConversionEngineTest {
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
     * 変換は読みの各文字をAS_ISで送りSPACEで変換する。各候補を一時的に選んで確定する読みを求め、
     * 最後に元の候補へ選択を戻す。新しいsessionでは学習を消さないようREVERTを送らない。
     */
    @Test
    fun convertRebuildsCompositionFromReading() {
        val native = FakeMozcNative().apply { respond = ::scriptedMozc }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val conversion = engine.convert(sessionId = 5, reading = "きょうはいい")!!

        val inputs = native.inputs
        assertFalse(inputs.any { it.command.type == SessionCommand.CommandType.REVERT })
        val characters = inputs.filter { it.type == Input.CommandType.SEND_KEY && it.key.hasKeyString() }
        assertEquals(listOf("き", "ょ", "う", "は", "い", "い"), characters.map { it.key.keyString })
        assertTrue(characters.all { it.key.inputStyle == KeyEvent.InputStyle.AS_IS })
        assertTrue(inputs.any { it.key.specialKey == KeyEvent.SpecialKey.SPACE })
        val last = inputs.last().command
        assertEquals(SessionCommand.CommandType.SELECT_CANDIDATE, last.type)
        assertEquals(0, last.id)
        assertTrue(inputs.all { it.id == 5L })
        assertEquals(
            listOf(ConversionSegment("きょうは", "今日は"), ConversionSegment("いい", "いい")),
            conversion.segments,
        )
        assertEquals(
            listOf(
                ConversionCandidate(0, "今日は", "きょうは"),
                ConversionCandidate(3, "京は", "きょうは"),
                ConversionCandidate(8, "今日はいい", "きょうはいい"),
            ),
            conversion.headCandidates,
        )
    }

    /** REVERTはpreeditが残るときだけ送り、確定で待機状態へ戻った後の次の変換では送らない。 */
    @Test
    fun revertIsSentOnlyWhilePreeditRemains() {
        val native = FakeMozcNative().apply { respond = ::scriptedMozc }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })
        engine.convert(sessionId = 5, reading = "きょうはいい")

        native.inputs.clear()
        engine.convert(sessionId = 5, reading = "きょうはいい")
        assertEquals(SessionCommand.CommandType.REVERT, native.inputs.first().command.type)

        assertTrue(engine.commitAll(sessionId = 5))
        native.inputs.clear()
        engine.convert(sessionId = 5, reading = "きょうはいい")
        assertFalse(native.inputs.any { it.command.type == SessionCommand.CommandType.REVERT })

        assertTrue(engine.commitCandidate(sessionId = 5, candidateId = 3))
        native.inputs.clear()
        engine.convert(sessionId = 5, reading = "きょうはいい")
        assertEquals(SessionCommand.CommandType.REVERT, native.inputs.first().command.type)
    }

    /**
     * 「きょうは|いい」を変換する最小のMozcの振る舞い。候補8は二つの文節をまとめる。
     * SUBMITは全文節を確定してpreeditを消し、SUBMIT_CANDIDATEは先頭文節だけを確定して残りを残す。
     */
    private fun scriptedMozc(input: Input): Output {
        val consumed = Output.newBuilder().setConsumed(true)
        return when {
            input.type == Input.CommandType.SEND_KEY && input.key.hasSpecialKey() -> conversionOutput()
            input.type == Input.CommandType.SEND_KEY -> consumed.setPreedit(preedit("き" to "き")).build()
            input.command.type == SessionCommand.CommandType.SELECT_CANDIDATE && input.command.id == 8 ->
                consumed.setPreedit(preedit("きょうはいい" to "今日はいい")).build()
            input.command.type == SessionCommand.CommandType.SELECT_CANDIDATE ->
                consumed.setPreedit(preedit("きょうは" to "京は", "いい" to "いい")).build()
            input.command.type == SessionCommand.CommandType.SUBMIT_CANDIDATE ->
                consumed.setPreedit(preedit("いい" to "いい")).build()
            else -> consumed.build()
        }
    }

    /** 読みと表記の組からpreeditを作る。 */
    private fun preedit(vararg segments: Pair<String, String>): Preedit {
        val builder = Preedit.newBuilder().setCursor(0)
        segments.forEach { (key, value) ->
            builder.addSegment(
                Preedit.Segment.newBuilder()
                    .setAnnotation(Preedit.Segment.Annotation.UNDERLINE)
                    .setKey(key)
                    .setValue(value)
                    .setValueLength(value.length),
            )
        }
        return builder.build()
    }

    /** Mozcが失敗を返した場合は変換結果を作らない。 */
    @Test
    fun sessionFailureReturnsNull() {
        val native = FakeMozcNative()
        native.respond = { Output.newBuilder().setErrorCode(Output.ErrorCode.SESSION_FAILURE).build() }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertNull(engine.convert(sessionId = 5, reading = "かんじ"))
        assertNull(engine.createSession())
    }

    /** incognitoはSET_REQUESTのRequest.is_incognito_modeで指定する。 */
    @Test
    fun incognitoUsesSetRequest() {
        val native = FakeMozcNative()
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertTrue(engine.setIncognito(true))

        val input = native.inputs.single()
        assertEquals(Input.CommandType.SET_REQUEST, input.type)
        assertTrue(input.request.isIncognitoMode)
    }

    /** 先頭文節の確定はSUBMIT_CANDIDATEに候補IDを付けて送る。 */
    @Test
    fun commitCandidateSendsCandidateId() {
        val native = FakeMozcNative()
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        assertTrue(engine.commitCandidate(sessionId = 5, candidateId = 3))

        val command = native.inputs.single().command
        assertEquals(SessionCommand.CommandType.SUBMIT_CANDIDATE, command.type)
        assertEquals(3, command.id)
    }

    /** SPACEによる変換直後の応答。候補4は候補0と同じ表記・同じ読みで、重複として除かれる。 */
    private fun conversionOutput(): Output {
        return Output.newBuilder()
            .setConsumed(true)
            .setPreedit(preedit("きょうは" to "今日は", "いい" to "いい"))
            .setAllCandidateWords(
                CandidateList.newBuilder()
                    .setFocusedIndex(0)
                    .addCandidates(CandidateWord.newBuilder().setId(0).setValue("今日は"))
                    .addCandidates(CandidateWord.newBuilder().setId(3).setValue("京は"))
                    .addCandidates(CandidateWord.newBuilder().setId(4).setValue("今日は"))
                    .addCandidates(CandidateWord.newBuilder().setId(8).setValue("今日はいい")),
            )
            .build()
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
    private val composition = StringBuilder()
    private val keys = mutableListOf<String>()
    private val selected = mutableListOf<Int>()
    private var focus = 0
    private var converting = false

    /** 読みごとの候補。載っていない読みは読みそのものだけを候補にする。 */
    private val dictionary = mapOf(
        "きょうは" to listOf("今日は", "京は"),
        "いい" to listOf("いい", "良い"),
        "きょう" to listOf("今日", "京"),
    )

    /** Inputを一つ処理し、その時点のOutputを返す。 */
    fun respond(input: Input): Output {
        when {
            input.type == Input.CommandType.SEND_KEY && input.key.hasKeyString() -> {
                composition.append(input.key.keyString)
                converting = false
            }

            input.type == Input.CommandType.SEND_KEY -> handleSpecialKey(input.key)
            input.command.type == SessionCommand.CommandType.SELECT_CANDIDATE ->
                selected[focus] = candidatesOf(keys[focus]).indexOfFirst { it.second == input.command.id }
            input.command.type == SessionCommand.CommandType.SUBMIT -> {
                submitted += keys.indices.joinToString("") { candidatesOf(keys[it])[selected[it]].first }
                clear()
            }

            input.command.type == SessionCommand.CommandType.REVERT -> clear()
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
        return (dictionary[key] ?: listOf(key)).mapIndexed { index, value -> value to index }
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
        keys.forEachIndexed { index, key ->
            val value = candidatesOf(key)[selected[index]].first
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
        val words = CandidateList.newBuilder().setFocusedIndex(selected[focus])
        candidatesOf(keys[focus]).forEach { (value, id) ->
            words.addCandidates(CandidateWord.newBuilder().setId(id).setValue(value).setNumSegmentsInCandidate(1))
        }
        // 読みが異なる予測候補と、二つの文節をまとめる候補。どちらもライブ変換の候補から除かれるべきもの。
        words.addCandidates(CandidateWord.newBuilder().setId(90).setKey(keys[focus] + "ね").setValue("予測"))
        words.addCandidates(CandidateWord.newBuilder().setId(91).setValue("連結").setNumSegmentsInCandidate(2))
        return builder.setPreedit(preedit).setAllCandidateWords(words).build()
    }
}
