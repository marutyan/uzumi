package dev.uzumi.ime.live

/**
 * segmentが自動変換で書き換えられてよいかを表す。
 * provisionalだけを自動更新し、stableとchosenを後続入力から守るための区分である。
 */
enum class SegmentState {
    /** 入力中で、現在の読みに整合する変換結果なら自動更新してよい。 */
    PROVISIONAL,

    /** 同じ表記が続いたため、自動更新を止めた。ユーザーの候補選択は受け付ける。 */
    STABLE,

    /** ユーザーが候補を選んだ。自動更新も再分割も行わない。 */
    CHOSEN,
}

/**
 * composition内の一つのsegmentを表す。読みの範囲は書記素の個数で持ち、
 * UTF-16との変換は表示時だけ行うことで、結合文字や絵文字の途中で区切らない。
 */
data class LiveSegment(
    /** segmentの識別子。候補のタップが表示時と同じsegmentを指すかの照合に使う。 */
    val id: Long,
    /** composition全体の読みにおける開始書記素位置（含む）。 */
    val readingStart: Int,
    /** composition全体の読みにおける終了書記素位置（含まない）。 */
    val readingEnd: Int,
    /** このsegmentの読み。 */
    val reading: String,
    /** Editorへ表示する変換文字列。未変換なら読みと同じ。 */
    val surface: String,
    val state: SegmentState,
    /** 候補バーに出す表記。先頭が変換器の第一候補で、surfaceを必ず含む。 */
    val candidates: List<String>,
    /** 変換器の結果を反映済みか。falseの間は読みをそのまま表示している。 */
    val converted: Boolean,
    /** 同じ読み範囲と表記を、異なる読みrevisionで観測した回数。stableへの昇格判定に使う。 */
    val observations: Int,
    /** 最後に観測を数えた読みrevision。同じ入力への複数の結果を二重に数えないために持つ。 */
    val lastObservedReadingVersion: Long,
    /** 候補を、明示変換と同じ方法で変換器から取り直したか。trueなら注目しても候補を求め直さない。 */
    val candidatesComplete: Boolean = false,
) {
    /** 未変換の入力中segmentか。新しい読みを隣へ入力したときに結合してよい対象を表す。 */
    val isRaw: Boolean
        get() = !converted && state == SegmentState.PROVISIONAL
}

/**
 * UI（下線や候補バーの対象表示）へ渡す、Editor表示上のsegment範囲。
 * 範囲はEditorとの受け渡しに合わせてUTF-16 offsetで表す。
 */
data class DisplaySpan(
    val segmentId: Long,
    val start: Int,
    val end: Int,
    val state: SegmentState,
    val converted: Boolean,
    val focused: Boolean,
)

/**
 * 変換器へ再分割・再変換を許さない読み範囲を表す。
 * stable、chosen、Undoで復元した範囲、カーソルを含む範囲がここへ入る。
 */
data class ProtectedRange(
    val readingStart: Int,
    val readingEnd: Int,
    /** 保護範囲の現在の表示。変換器が文脈として使える。 */
    val surface: String,
)

/**
 * 変換要求が作られた時点の状態を識別する。
 * 結果を適用する前にすべての項目を現在値と照合し、一つでも違えば捨てる。
 */
data class RequestIdentity(
    val sessionEpoch: Long,
    val revision: Long,
    /** composition全体の読み。 */
    val reading: String,
    /** 変換してよい範囲の開始書記素位置（含む）。 */
    val targetStart: Int,
    /** 変換してよい範囲の終了書記素位置（含まない）。 */
    val targetEnd: Int,
    val protectedRanges: List<ProtectedRange>,
    /**
     * 入力カーソルの書記素位置。対象範囲の内部にある場合、変換器はこの位置で必ずsegmentを区切る。
     * 区切らないと、Editorに見えるカーソル（変換済み表記の後ろ）と読みの位置がずれるためである。
     */
    val inputCursor: Int,
    /** 辞書とモデルの世代。辞書更新前の要求を区別する。 */
    val converterGeneration: Long,
)

/**
 * コアが発行する変換要求。統合担当は変換器を別スレッドで呼び、結果をコアへ返す。
 * learningAllowedは送信前に決まり、機密欄や学習禁止欄ではfalseになる。
 */
data class ConversionRequest(
    val identity: RequestIdentity,
    /** 対象範囲の読み。identity.readingの[targetStart, targetEnd)に等しい。 */
    val targetReading: String,
    /** falseなら変換器は学習・履歴・文脈保存を行わない（Mozcではincognito相当）。 */
    val learningAllowed: Boolean,
)

/**
 * 変換器が返す一つのsegment。MozcのPreedit.Segmentのkeyを読み、valueを表記、
 * そのsegmentに焦点を当てたときのCandidateWindowの各valueを候補として写す想定である。
 */
data class ResultSegment(
    val reading: String,
    val surface: String,
    val candidates: List<String> = listOf(surface),
    /** 候補を明示変換と同じ方法で取ったか。trueならコアは注目時に候補を求め直さない。 */
    val candidatesComplete: Boolean = false,
)

/**
 * 変換器の返り値。segmentの読みを連結すると要求の対象範囲の読みと一致しなければならない。
 */
data class ConversionResult(
    val identity: RequestIdentity,
    val segments: List<ResultSegment>,
)

/**
 * 入力欄ごとの変換と学習の方針。Android側の方針をこの二値へ写してコアへ渡す。
 */
data class LiveFieldPolicy(
    /** falseなら変換要求を出さない（password等）。読みは表示する。 */
    val conversionAllowed: Boolean,
    /** falseなら要求へ学習禁止を付ける（password、sensitive、no-personalized-learning）。 */
    val learningAllowed: Boolean,
) {
    companion object {
        /** 通常の入力欄の方針。 */
        val NORMAL = LiveFieldPolicy(conversionAllowed = true, learningAllowed = true)
    }
}

/**
 * Enterキーの意味。改行として入れるか、Editor actionを送るかは統合担当がEditorInfoから決める。
 */
sealed interface EnterKind {
    /** 確定後に改行文字を一度だけ入れる。 */
    data object Newline : EnterKind

    /** 確定後にEditor action（Send、Search、Done、Next等）を一度だけ送る。 */
    data class EditorAction(val actionCode: Int) : EnterKind
}

/**
 * コアがEditorへ求める書込み。コアは副作用を持たず、統合担当がこの順に接続へ送る。
 */
sealed interface EditorCommand {
    /** composition全体をこの表示へ置き換え、カーソルをcomposition先頭からのUTF-16 offsetへ置く。 */
    data class SetComposition(val text: String, val cursorUtf16: Int) : EditorCommand

    /** 現在のcompositionをこの文字列で確定する。compositionがなければ文字列を確定入力する。 */
    data class Commit(val text: String) : EditorCommand

    /** Editor actionを送る。 */
    data class PerformEditorAction(val actionCode: Int) : EditorCommand
}

/**
 * 変換結果や候補のタップを捨てた理由。試験と記録で古い結果の拒否を区別するために使う。
 */
enum class RejectReason {
    NOT_ACTIVE,
    LIVE_DISABLED,
    CONVERSION_NOT_ALLOWED,
    EPOCH_MISMATCH,
    GENERATION_MISMATCH,
    REVISION_MISMATCH,
    READING_MISMATCH,
    TARGET_MISMATCH,
    PROTECTED_MISMATCH,
    /** 読みの欠落・重複、書記素の途中での分割、空の表記。 */
    MALFORMED_SEGMENTS,
    /** 保護範囲の境界をまたいで再分割している。 */
    CROSSES_PROTECTED,

    /** 対象範囲内の入力カーソル位置をまたいで一つのsegmentにしている。 */
    CROSSES_CURSOR,
    /** 候補を表示したときとepoch、revision、segment、候補が一致しない。 */
    STALE_CANDIDATE,
}

/**
 * コアの各操作の返り値。handledがfalseなら、統合担当は従来の編集経路（EditorSession）へ回す。
 */
data class LiveUpdate(
    val handled: Boolean,
    /** Editorへ順に送る書込み。 */
    val commands: List<EditorCommand> = emptyList(),
    /** 新しく発行した変換要求。前の要求は結果が届いても捨てられる。 */
    val request: ConversionRequest? = null,
    /** 注目したsegmentの候補を変換器へ取り直す要求。統合担当は変換要求と同じく別スレッドで処理する。 */
    val candidateRequest: CandidateRequest? = null,
    /** 結果や候補のタップを捨てた場合の理由。 */
    val rejection: RejectReason? = null,
) {
    companion object {
        /** コアが扱わない操作。 */
        val NOT_HANDLED = LiveUpdate(handled = false)

        /** 受け付けたが、Editorへの書込みも要求もない操作。 */
        val NO_CHANGE = LiveUpdate(handled = true)
    }
}

/**
 * 候補バーの一つの候補。表示時のepoch、revision、segmentを持ち、タップ時に照合する。
 */
data class CandidateChoice(
    val sessionEpoch: Long,
    val revision: Long,
    val segmentId: Long,
    val value: String,
)

/**
 * 候補バーに出す内容。対象segmentと候補の並びを表す。
 */
data class CandidateBar(
    val segmentId: Long,
    val choices: List<CandidateChoice>,
)

/**
 * 注目した一つのsegmentの候補を、明示変換と同じ方法で変換器へ取り直す要求。
 * 自動変換は速さのため候補を簡単に集めるだけなので、ユーザーが注目したsegmentだけを遅れて取り直す。
 * 結果は表示時と同じepoch・revision・segmentに一致する場合だけ適用する。
 */
data class CandidateRequest(
    val sessionEpoch: Long,
    val revision: Long,
    val converterGeneration: Long,
    val segmentId: Long,
    val readingStart: Int,
    val readingEnd: Int,
    /** 対象segmentの読み。 */
    val reading: String,
    /** 直前のsegmentの読み。変換器が文脈として一緒に変換する。句読点や先頭では空。 */
    val preceding: String,
    /** 直後のsegmentの読み。変換器が文脈として一緒に変換する。句読点や末尾では空。 */
    val following: String,
    /** falseなら変換器は学習・履歴を使わない。 */
    val learningAllowed: Boolean,
)

/** [CandidateRequest]への結果。candidatesは対象segmentの読みだけを置き換える候補で、先頭ほど優先する。 */
data class CandidateResult(
    val request: CandidateRequest,
    val candidates: List<String>,
)

/**
 * Undo/Redoの一操作の種類。自動変換はここへ入らず、起点の入力操作にまとめる。
 */
enum class OperationKind {
    INPUT,
    DELETE,
    KANA_TRANSFORM,
    BOUNDARY,
    CANDIDATE,
    READING_REVERT,
}
