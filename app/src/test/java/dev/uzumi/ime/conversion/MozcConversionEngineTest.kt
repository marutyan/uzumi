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
     * 注目した文節の候補は、明示変換で同じ読みを先頭文節にしたときの候補と同じになる。
     * どちらも各候補を一時的に選んで読みを確かめ、複数の文節をまとめる候補だけを除く。確定はしない。
     */
    @Test
    fun convertSegmentCollectsSameCandidatesAsExplicitConversion() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        for ((reading, following) in listOf("こうえんに" to "", "きょうは" to "いい")) {
            val explicit = engine.convert(sessionId = 5, reading = reading + following)!!
                .headCandidates.filter { it.reading == reading }.map { it.value }
            val live = engine.convertSegment(sessionId = 5, preceding = "", reading = reading, following = following)!!

            assertEquals(explicit, live.candidates)
            assertEquals(explicit.first(), live.value)
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
    fun convertWithHeadLengthResizesHeadSegment() {
        val mozc = StatefulMozc()
        val native = FakeMozcNative().apply { respond = mozc::respond }
        val engine = MozcConversionEngine(native, { File("profile") }, { null })

        val shrunk = engine.convert(sessionId = 5, reading = "きょうはいい", headLength = 3)!!
        assertEquals(listOf(ConversionSegment("きょう", "今日"), ConversionSegment("はいい", "はいい")), shrunk.segments)
        assertEquals(listOf("今日", "京", "予測"), shrunk.headCandidates.filter { it.reading == "きょう" }.map { it.value })

        native.inputs.clear()
        val expanded = engine.convert(sessionId = 5, reading = "きょうはいい", headLength = 5)!!
        assertEquals(listOf("きょうはい", "い"), expanded.segments.map { it.reading })
        assertEquals(
            1,
            native.inputs.count {
                it.key.specialKey == KeyEvent.SpecialKey.RIGHT && KeyEvent.ModifierKey.SHIFT in it.key.modifierKeysList
            },
        )
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
