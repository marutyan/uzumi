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

    /** 変換はREVERT、読みの各文字をAS_ISで送り、SPACEで変換し、文節と候補を取り出す。 */
    @Test
    fun convertRebuildsCompositionFromReading() {
        val native = FakeMozcNative()
        native.respond = { input ->
            if (input.type == Input.CommandType.SEND_KEY && input.key.hasSpecialKey()) {
                conversionOutput()
            } else {
                Output.newBuilder().setConsumed(true).build()
            }
        }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val conversion = engine.convert(sessionId = 5, reading = "きょうは")!!

        val inputs = native.inputs
        assertEquals(SessionCommand.CommandType.REVERT, inputs.first().command.type)
        val characters = inputs.filter { it.type == Input.CommandType.SEND_KEY && it.key.hasKeyString() }
        assertEquals(listOf("き", "ょ", "う", "は"), characters.map { it.key.keyString })
        assertTrue(characters.all { it.key.inputStyle == KeyEvent.InputStyle.AS_IS })
        assertEquals(KeyEvent.SpecialKey.SPACE, inputs.last().key.specialKey)
        assertTrue(inputs.all { it.id == 5L })
        assertEquals(listOf(ConversionSegment("きょうは", "今日は")), conversion.segments)
        assertEquals(listOf(ConversionCandidate(0, "今日は"), ConversionCandidate(3, "京は")), conversion.headCandidates)
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

    private fun conversionOutput(): Output {
        return Output.newBuilder()
            .setConsumed(true)
            .setPreedit(
                Preedit.newBuilder()
                    .setCursor(3)
                    .addSegment(
                        Preedit.Segment.newBuilder()
                            .setAnnotation(Preedit.Segment.Annotation.HIGHLIGHT)
                            .setKey("きょうは")
                            .setValue("今日は")
                            .setValueLength(3),
                    ),
            )
            .setAllCandidateWords(
                CandidateList.newBuilder()
                    .addCandidates(CandidateWord.newBuilder().setId(0).setValue("今日は"))
                    .addCandidates(CandidateWord.newBuilder().setId(3).setValue("京は"))
                    .addCandidates(CandidateWord.newBuilder().setId(4).setValue("今日は")),
            )
            .build()
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
