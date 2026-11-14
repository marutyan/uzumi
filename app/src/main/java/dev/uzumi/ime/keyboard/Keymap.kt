package dev.uzumi.ime.keyboard

/**
 * 利用者が設定で選び、キーボード左下の切り替えキーで選んだ順に移るキーの配列。
 * 保存値には列挙の名前を使うため、名前を変えると保存済みの選択が失われる。
 *
 * @property displayName 設定画面に出す名前
 * @property shortLabel 左下の切り替えキーに出す短い名前。そのキーを押すとこの配列へ移ることを示す
 * @property category 種類（かな・英字・数字）。切り替えキーの読み上げ名と、パスワード欄・数字の欄で出す配列の判定に使う
 */
enum class Keymap(val displayName: String, val shortLabel: String, val category: KeyboardMode) {
    /** かなの12キー（フリックとトグル入力）。 */
    JA_TWELVE("日本語 12キー", "あ", KeyboardMode.KANA),

    /** QWERTYのローマ字かな入力。 */
    JA_QWERTY("日本語 QWERTY", "ローマ字", KeyboardMode.KANA),

    /** QWERTYの英字入力。 */
    EN_QWERTY("英字 QWERTY", "ABC", KeyboardMode.QWERTY),

    /** 英字の12キー（フリックとトグル入力）。 */
    EN_TWELVE("英字 12キー", "abc", KeyboardMode.QWERTY),

    /** Simejiと同じ並びの数字キー（左右に記号と機能、下の段だけフリック）。 */
    NUMBER_SIMEJI("数字 Simeji型", "123", KeyboardMode.NUMERIC),

    /** 電卓と同じ並びの数字キー（キーマップを導入する前の数字面）。 */
    NUMBER_CALCULATOR("数字 電卓型", "123", KeyboardMode.NUMERIC),
}

/**
 * キーマップの選択（使う配列とその順番）の保存形式と、通常時の配列の切り替えの規則。
 * Android UIに依存しない純粋な関数にまとめ、JVMテストで守る。研究の評価の記録中の規則は[RecordingKeymapRules]に分ける。
 */
object KeymapRules {
    /** 既定の選択。かなの12キー、英字QWERTY、Simeji型の数字の順に切り替える。 */
    val DEFAULT_ORDER: List<Keymap> = listOf(Keymap.JA_TWELVE, Keymap.EN_QWERTY, Keymap.NUMBER_SIMEJI)

    /** 選択を保存用の文字列（配列名のカンマ区切り）にする。 */
    fun encode(order: List<Keymap>): String = normalize(order).joinToString(",") { it.name }

    /** 保存した文字列を選択へ戻す。未保存（null）ならnullを返し、旧設定からの移行を呼び出し側に任せる。 */
    fun decode(stored: String?): List<Keymap>? {
        stored ?: return null
        return normalize(stored.split(',').mapNotNull { name -> Keymap.entries.firstOrNull { it.name == name.trim() } })
    }

    /** 重複を除き、空なら既定の選択にする。少なくとも1つの配列を必ず残すための正規化。 */
    fun normalize(order: List<Keymap>): List<Keymap> = order.distinct().ifEmpty { DEFAULT_ORDER }

    /**
     * 旧設定「英字配列でローマ字かな入力」（[romajiInput]）と「配列の切替に英字入力を含める」（[includesEnglish]）から
     * 選択を作る。かなの12キーを先頭に、ローマ字かな入力がオンなら日本語QWERTY、オフか英字を含めるなら英字QWERTY、最後にSimeji型の数字。
     */
    fun migrate(romajiInput: Boolean, includesEnglish: Boolean): List<Keymap> = buildList {
        add(Keymap.JA_TWELVE)
        if (romajiInput) add(Keymap.JA_QWERTY)
        if (!romajiInput || includesEnglish) add(Keymap.EN_QWERTY)
        add(Keymap.NUMBER_SIMEJI)
    }

    /**
     * 欄で使える配列へ置き換える。文字をその場で確定する欄（[directCommit]。パスワード・数字・互換入力の欄）では、
     * 押すたびに直前の文字を差し替える英字12キーを使えないため、英字QWERTYにする。
     */
    fun usable(keymap: Keymap, directCommit: Boolean): Keymap =
        if (directCommit && keymap == Keymap.EN_TWELVE) Keymap.EN_QWERTY else keymap

    /**
     * 左下の切り替えキーで巡る配列。文字をその場で確定する欄（[directCommit]）では英字12キーを英字QWERTYに置き換え、
     * パスワード欄ではかなの配列を飛ばし（かな入力を使わない）、英字の配列が一つも無ければ英字QWERTYを先頭に足して、
     * 文字を打てる配列を必ず含める。[directCommit]を省いた場合は、パスワード欄（その場で確定する欄の一つ）かどうかで決める。
     */
    fun cycleOrder(order: List<Keymap>, password: Boolean, directCommit: Boolean = password): List<Keymap> {
        val normalized = normalize(order).map { usable(it, directCommit) }.distinct()
        if (!password) return normalized
        val withoutKana = normalized.filter { it.category != KeyboardMode.KANA }
        return if (withoutKana.any { it.category == KeyboardMode.QWERTY }) withoutKana else listOf(Keymap.EN_QWERTY) + withoutKana
    }

    /** [current]の左下の切り替えキーで移る配列。選んだ順の次で、最後の次は最初。[current]が順番に無ければ最初へ移る。 */
    fun next(order: List<Keymap>, current: Keymap, password: Boolean, directCommit: Boolean = password): Keymap {
        val cycle = cycleOrder(order, password, directCommit)
        val index = cycle.indexOf(current)
        return if (index < 0) cycle.first() else cycle[(index + 1) % cycle.size]
    }

    /**
     * 種類を指定する切り替えキー（12キーとQWERTYの「123」、記号面の「あ」「ABC」「123」）で移る配列。
     * 選んだ順の中で最初のその種類の配列とし、無ければ英字QWERTY、Simeji型の数字、かなの12キーを使う。
     * パスワード欄でかなを求められた場合は英字にする。
     */
    fun forCategory(order: List<Keymap>, category: KeyboardMode, password: Boolean, directCommit: Boolean = password): Keymap {
        val cycle = cycleOrder(order, password, directCommit)
        return when (category) {
            KeyboardMode.KANA -> if (password) {
                forCategory(order, KeyboardMode.QWERTY, password = true, directCommit)
            } else {
                cycle.firstOrNull { it.category == KeyboardMode.KANA } ?: Keymap.JA_TWELVE
            }
            KeyboardMode.QWERTY -> cycle.firstOrNull { it.category == KeyboardMode.QWERTY } ?: Keymap.EN_QWERTY
            KeyboardMode.NUMERIC -> cycle.firstOrNull { it.category == KeyboardMode.NUMERIC } ?: Keymap.NUMBER_SIMEJI
            KeyboardMode.SYMBOL -> throw IllegalArgumentException("記号面はキーマップではない")
        }
    }

    /**
     * 入力欄を開いたときに出す配列。数字の欄では最初の数字の配列、パスワード欄では最初の英字の配列、
     * それ以外は前回の通常の欄で使っていた配列（[last]。今の選択に無ければ数字以外の最初の配列）にする。
     */
    fun initial(
        order: List<Keymap>,
        numeric: Boolean,
        password: Boolean,
        last: Keymap?,
        directCommit: Boolean = numeric || password,
    ): Keymap {
        val normalized = normalize(order)
        val keymap = when {
            numeric -> forCategory(normalized, KeyboardMode.NUMERIC, password, directCommit)
            password -> forCategory(normalized, KeyboardMode.QWERTY, password = true, directCommit)
            last != null && last in normalized -> last
            else -> normalized.firstOrNull { it.category != KeyboardMode.NUMERIC } ?: normalized.first()
        }
        return usable(keymap, directCommit)
    }

    /** 次に入力欄を開いたときのために覚える配列か。通常の欄で使った、数字以外の配列だけを覚える（数字の面は覚えない）。 */
    fun remembers(keymap: Keymap, numeric: Boolean, password: Boolean): Boolean =
        !numeric && !password && keymap.category != KeyboardMode.NUMERIC

    /** [current]の左下に置く切り替えキー。次の配列の短い名前を強調して出し、移る先の種類を読み上げる。 */
    fun switchKey(order: List<Keymap>, current: Keymap, password: Boolean, directCommit: Boolean = password): KeySpec.ModeSwitch {
        val target = next(order, current, password, directCommit)
        return KeySpec.ModeSwitch(label = target.shortLabel, targetMode = target.category, emphasized = true)
    }

    /** QWERTYの空白キーの表示。ローマ字かな入力中は「ローマ字」、英字入力中は「English」とし、どちらの配列かを見分けられるようにする。 */
    fun qwertySpaceLabel(romaji: Boolean): String = if (romaji) "ローマ字" else "English"
}

/**
 * 研究の評価の記録中と、評価の課題画面の入力欄で使う面の切り替えの規則。自動測定の道具
 * （tools/phase2c/run_automated.py のMODE_SWITCH）が前提にする、キーマップを導入する前の面と読み上げ名と電卓型の数字面を
 * そのまま保ち、キーマップの設定には従わない。英字の面はローマ字かな入力にしない英字QWERTYとする。
 */
object RecordingKeymapRules {
    /**
     * 評価の課題画面の入力欄に付ける目印（EditorInfo.privateImeOptions）。道具は記録していない課題の準備中にも
     * MODE_SWITCH の表で面を切り替えるため、目印のある欄では記録外でもこの規則を使う。目印は規則の選択にだけ使う。
     */
    const val EVALUATION_FIELD_OPTION = "dev.uzumi.ime.evaluationField"

    /** 入力欄の privateImeOptions（カンマ区切り）に評価の課題欄の目印があるか。 */
    fun isEvaluationField(privateImeOptions: String?): Boolean =
        privateImeOptions.orEmpty().split(',').any { it.trim() == EVALUATION_FIELD_OPTION }

    /** この規則を使うか。評価の記録中か、評価の課題欄なら使う。 */
    fun applies(recording: Boolean, evaluationField: Boolean): Boolean = recording || evaluationField

    /** この規則で出してよい配列。かなの12キー、英字QWERTY、電卓型の数字だけ。 */
    val KEYMAPS: Set<Keymap> = setOf(Keymap.JA_TWELVE, Keymap.EN_QWERTY, Keymap.NUMBER_CALCULATOR)

    /** QWERTYの空白キーの表示。導入前のローマ字かな入力を使わない英字配列と同じ「space」。 */
    const val QWERTY_SPACE_LABEL = "space"

    /** 種類を指定する切り替えキーで移る配列。パスワード欄ではかなの代わりに英字にする（導入前と同じ）。 */
    fun keymapFor(category: KeyboardMode, password: Boolean): Keymap = when (category) {
        KeyboardMode.KANA -> if (password) Keymap.EN_QWERTY else Keymap.JA_TWELVE
        KeyboardMode.QWERTY -> Keymap.EN_QWERTY
        KeyboardMode.NUMERIC -> Keymap.NUMBER_CALCULATOR
        KeyboardMode.SYMBOL -> throw IllegalArgumentException("記号面はキーマップではない")
    }

    /** この規則で出せない配列を表示中なら、同じ種類の導入前の配列へ置き換える。規則を使い始めた瞬間の面を合わせるために使う。 */
    fun coerce(keymap: Keymap, password: Boolean): Keymap =
        if (keymap in KEYMAPS) keymap else keymapFor(keymap.category, password)

    /** 入力欄を開いたときに出す配列。数字の欄は電卓型、パスワード欄は英字、それ以外は前回の通常の欄の面（かなか英字）。 */
    fun initial(numeric: Boolean, password: Boolean, last: Keymap?): Keymap = when {
        numeric -> Keymap.NUMBER_CALCULATOR
        password -> Keymap.EN_QWERTY
        last == Keymap.EN_QWERTY || last == Keymap.JA_QWERTY -> Keymap.EN_QWERTY
        else -> Keymap.JA_TWELVE
    }

    /**
     * 各配列の左下の切り替えキー。かなの面は「あA」で英字へ、英字の面は「あ」でかなへ、数字の面は「あ/A」でかな
     * （パスワード欄では「ABC」で英字）へ移る。かなへ移るキーはパスワード欄で隠す（[hidesInPassword]）。
     */
    fun switchKey(keymap: Keymap, password: Boolean): KeySpec.ModeSwitch = when (keymap) {
        Keymap.JA_TWELVE -> KeySpec.ModeSwitch("あA", KeyboardMode.QWERTY)
        Keymap.NUMBER_CALCULATOR -> if (password) {
            KeySpec.ModeSwitch("ABC", KeyboardMode.QWERTY)
        } else {
            KeySpec.ModeSwitch("あ/A", KeyboardMode.KANA)
        }
        else -> KeySpec.ModeSwitch("あ", KeyboardMode.KANA)
    }

    /** パスワード欄で隠す切り替えキーか。パスワード欄ではかな入力を使わないため、かなへ移るキーを出さない（導入前と同じ）。 */
    fun hidesInPassword(key: KeySpec.ModeSwitch): Boolean = key.targetMode == KeyboardMode.KANA
}
