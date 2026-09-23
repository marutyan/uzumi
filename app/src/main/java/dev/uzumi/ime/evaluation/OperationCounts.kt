package dev.uzumi.ime.evaluation

import dev.uzumi.ime.keyboard.KeyboardAction

/**
 * Phase 2cの評価で数える操作の種類。押下1回を一つの種類へ分け、入力した本文や候補の文字列は持たない。
 * 定義は`docs/phase2c-evaluation-protocol.md`の「指標」に従う。
 */
enum class OperationKind {
    /** 読み・英数字・読点・空白の入力と、小゛゜による文字の切り替え。 */
    INPUT,

    /** 誤りのない表示を確定するためだけの操作。明示変換の変換キーと、第一候補の選択。 */
    COMMIT,

    /**
     * 表示や入力を直す操作。削除、カーソル・文節の移動、文節の区切りの伸縮、第一候補以外の候補選択、元に戻す、
     * 末尾への復帰、カナへの切り替え、ライブ変換中の変換キー（次の候補への切り替え）。
     */
    CORRECTION,

    /** 本来の終端操作。句点の入力とEnter（改行・送信）。どの操作数にも含めない。 */
    TERMINATOR,

    /** 上のどれにも当たらない押下。面の切替、Shift、候補一覧の開閉など。キー操作数だけに数える。 */
    OTHER,
}

/**
 * 1課題の操作数。[keys]は終端操作を除くすべての押下の数で、[commits]と[corrections]はその内訳である。
 * [terminators]は終端操作の数で、[keys]には含めない。件数だけを持ち、本文は持たない。
 */
data class OperationCounts(
    val keys: Int = 0,
    val commits: Int = 0,
    val corrections: Int = 0,
    val terminators: Int = 0,
) {
    /** 押下1回を種類[kind]として足した操作数を返す。 */
    operator fun plus(kind: OperationKind): OperationCounts = when (kind) {
        OperationKind.TERMINATOR -> copy(terminators = terminators + 1)
        OperationKind.COMMIT -> copy(keys = keys + 1, commits = commits + 1)
        OperationKind.CORRECTION -> copy(keys = keys + 1, corrections = corrections + 1)
        OperationKind.INPUT, OperationKind.OTHER -> copy(keys = keys + 1)
    }
}

/**
 * 押下を[OperationKind]へ分ける規則。IMEの評価用計数とJVMテストの補助が同じ規則を使うよう一か所へ置く。
 */
object OperationClassifier {
    /** 課題の本来の終端操作として扱う句点。 */
    private const val FULL_STOP = "。"

    /** 文字キーで入れた文字列[value]の種類。句点だけを終端操作とし、それ以外は入力とする。 */
    fun text(value: String): OperationKind = if (value == FULL_STOP) OperationKind.TERMINATOR else OperationKind.INPUT

    /**
     * キーボードの操作[action]の種類。変換キーは、明示変換（[liveMode]がfalse）では変換を始める確定操作、
     * ライブ変換では次の候補へ切り替える訂正操作とする。Enterは入力中でも確定と改行・送信を一度に行うため終端操作とする。
     * キー操作数は画面に指が触れた回数なので、同じ長押しで繰り返した文節の伸縮（2回目以降）は数えずnullを返す。
     */
    fun keyboardAction(action: KeyboardAction, liveMode: Boolean): OperationKind? = when (action) {
        is KeyboardAction.Text -> text(action.value)
        KeyboardAction.Space, KeyboardAction.TransformKana -> OperationKind.INPUT
        KeyboardAction.Convert -> if (liveMode) OperationKind.CORRECTION else OperationKind.COMMIT
        KeyboardAction.Delete,
        KeyboardAction.DeleteToLineStart,
        is KeyboardAction.MoveCursor,
        KeyboardAction.ToKatakana,
        -> OperationKind.CORRECTION
        is KeyboardAction.ResizeSegment -> if (action.continued) null else OperationKind.CORRECTION
        KeyboardAction.Enter -> OperationKind.TERMINATOR
    }

    /** 候補バーまたは候補一覧で[index]番目（0始まり）の候補を選んだ操作の種類。第一候補だけを確定操作とする。 */
    fun candidatePick(index: Int): OperationKind = if (index == 0) OperationKind.COMMIT else OperationKind.CORRECTION
}
