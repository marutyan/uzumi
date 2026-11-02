package dev.uzumi.ime.editor

/**
 * QWERTYで打ったローマ字を、ひらがなと入力途中のローマ字へ分けて組み立てる。
 * 入力途中のローマ字（例「k」「ky」）はcompositionの末尾にそのまま表示し、次のキーで差し替える。
 * 変換規則はMozcのローマ字表（[RomajiKanaTable]）を最長一致で引く。Android APIを使わず、JVMテストで確かめられる。
 */
class RomajiKanaComposer {
    // compositionの末尾に表示している、まだかなにしていないローマ字。
    private var pending = ""

    /** 入力途中のローマ字があるか。 */
    val hasPending: Boolean
        get() = pending.isNotEmpty()

    /** 入力途中のローマ字。削除の「元に戻す」で保留状態を戻すために記録する。 */
    val pendingText: String
        get() = pending

    /**
     * 一文字を受け取り、compositionの末尾にある入力途中のローマ字を何へ差し替えるかを返す。
     * 表に続きがある間は保留し、続きが無くなった時点で保留分をかな（一致しなければ元の文字のまま）にして、
     * 新しい文字を最初から引き直す。Mozcの表の引き方と同じで、「nk」は「んk」、「kk」は「っk」になる。
     */
    fun input(char: Char): RomajiEdit {
        val old = pending
        val output = StringBuilder()
        var current = old
        while (true) {
            val sequence = current + char
            if (RomajiKanaTable.hasLongerKey(sequence)) {
                current = sequence
                break
            }
            val exact = RomajiKanaTable.lookup(sequence)
            if (exact != null) {
                output.append(exact.output)
                current = exact.next
                break
            }
            if (current.isEmpty()) {
                // 表に無い文字（大文字・数字・記号など）は英字のまま入れる。
                output.append(char)
                break
            }
            // 保留分では続きが無いため、保留分を確定してから新しい文字を引き直す。保留分は毎回短くなるので必ず終わる。
            val flushed = RomajiKanaTable.lookup(current)
            if (flushed != null) {
                output.append(flushed.output)
                current = flushed.next
            } else {
                output.append(current)
                current = ""
            }
        }
        pending = current
        return RomajiEdit(remove = old, insert = output.toString() + current)
    }

    /** 入力途中のローマ字を、表で一致すればかな（末尾の「n」は「ん」）、一致しなければそのままにして終える。 */
    fun finish(): RomajiEdit? {
        if (pending.isEmpty()) return null
        val old = pending
        pending = ""
        val exact = RomajiKanaTable.lookup(old)
        return RomajiEdit(remove = old, insert = if (exact != null) exact.output + exact.next else old)
    }

    /** Backspaceで、表示している入力途中のローマ字の末尾一文字を消す。 */
    fun deletePending() {
        pending = pending.dropLast(1)
    }

    /** 表示を変えずに保留だけを捨てる。表示中のローマ字はそのまま英字として残る。 */
    fun reset() {
        pending = ""
    }

    /** 削除の「元に戻す」でcompositionを戻したとき、保留していたローマ字も同じ状態へ戻す。 */
    fun restorePending(value: String) {
        pending = value
    }
}

/** compositionの末尾にある入力途中のローマ字[remove]を、[insert]へ差し替える編集。 */
data class RomajiEdit(val remove: String, val insert: String)

/**
 * Mozcのローマ字表（src/data/preedit/romanji-hiragana.tsv）。各行は「ローマ字、出力、次の保留」をタブで区切る。
 * 本文はlibmozc.soと同じcommit 13c98988247aa711d99db9e348ec2a597d14b5cdから変更せずに転記した（BSD 3-Clause、
 * 表示はthird_party/mozc/NOTICE.txt）。表に無い「?」「!」だけは、Gboardと同じく全角へ変える規則を後から足す。
 */
private object RomajiKanaTable {
    /** 表の一項目。[next]は出力後も保留に残すローマ字（「kk」→「っ」と保留「k」）。 */
    data class Entry(val output: String, val next: String)

    // Mozcの表の本文。行頭の空白は読み込み時に除く。
    private val MOZC_TSV = """
        -	ー
        ~	〜
        .	。
        ,	、
        z/	・
        z.	…
        z,	‥
        zh	←
        zj	↓
        zk	↑
        zl	→
        z-	〜
        z[	『
        z]	』
        [	「
        ]	」
        va	ゔぁ
        vi	ゔぃ
        vu	ゔ
        ve	ゔぇ
        vo	ゔぉ
        vya	ゔゃ
        vyi	ゔぃ
        vyu	ゔゅ
        vye	ゔぇ
        vyo	ゔょ
        qq	っ	q
        vv	っ	v
        ll	っ	l
        xx	っ	x
        kk	っ	k
        gg	っ	g
        ss	っ	s
        zz	っ	z
        jj	っ	j
        tt	っ	t
        tch	っ	ch
        dd	っ	d
        hh	っ	h
        ff	っ	f
        bb	っ	b
        pp	っ	p
        mm	っ	m
        yy	っ	y
        rr	っ	r
        ww	っ	w
        www	w	ww
        cc	っ	c
        kya	きゃ
        kyi	きぃ
        kyu	きゅ
        kye	きぇ
        kyo	きょ
        gya	ぎゃ
        gyi	ぎぃ
        gyu	ぎゅ
        gye	ぎぇ
        gyo	ぎょ
        sya	しゃ
        syi	しぃ
        syu	しゅ
        sye	しぇ
        syo	しょ
        sha	しゃ
        shi	し
        shu	しゅ
        she	しぇ
        sho	しょ
        zya	じゃ
        zyi	じぃ
        zyu	じゅ
        zye	じぇ
        zyo	じょ
        tya	ちゃ
        tyi	ちぃ
        tyu	ちゅ
        tye	ちぇ
        tyo	ちょ
        cha	ちゃ
        chi	ち
        chu	ちゅ
        che	ちぇ
        cho	ちょ
        cya	ちゃ
        cyi	ちぃ
        cyu	ちゅ
        cye	ちぇ
        cyo	ちょ
        dya	ぢゃ
        dyi	ぢぃ
        dyu	ぢゅ
        dye	ぢぇ
        dyo	ぢょ
        tsa	つぁ
        tsi	つぃ
        tse	つぇ
        tso	つぉ
        tha	てゃ
        thi	てぃ
        t'i	てぃ
        thu	てゅ
        the	てぇ
        tho	てょ
        t'yu	てゅ
        dha	でゃ
        dhi	でぃ
        d'i	でぃ
        dhu	でゅ
        dhe	でぇ
        dho	でょ
        d'yu	でゅ
        twa	とぁ
        twi	とぃ
        twu	とぅ
        twe	とぇ
        two	とぉ
        t'u	とぅ
        dwa	どぁ
        dwi	どぃ
        dwu	どぅ
        dwe	どぇ
        dwo	どぉ
        d'u	どぅ
        nya	にゃ
        nyi	にぃ
        nyu	にゅ
        nye	にぇ
        nyo	にょ
        hya	ひゃ
        hyi	ひぃ
        hyu	ひゅ
        hye	ひぇ
        hyo	ひょ
        bya	びゃ
        byi	びぃ
        byu	びゅ
        bye	びぇ
        byo	びょ
        pya	ぴゃ
        pyi	ぴぃ
        pyu	ぴゅ
        pye	ぴぇ
        pyo	ぴょ
        fa	ふぁ
        fi	ふぃ
        fu	ふ
        fe	ふぇ
        fo	ふぉ
        fya	ふゃ
        fyu	ふゅ
        fyo	ふょ
        hwa	ふぁ
        hwi	ふぃ
        hwe	ふぇ
        hwo	ふぉ
        hwyu	ふゅ
        mya	みゃ
        myi	みぃ
        myu	みゅ
        mye	みぇ
        myo	みょ
        rya	りゃ
        ryi	りぃ
        ryu	りゅ
        rye	りぇ
        ryo	りょ
        n'	ん
        nn	ん
        n	ん
        xn	ん
        a	あ
        i	い
        u	う
        wu	う
        e	え
        o	お
        xa	ぁ
        xi	ぃ
        xu	ぅ
        xe	ぇ
        xo	ぉ
        la	ぁ
        li	ぃ
        lu	ぅ
        le	ぇ
        lo	ぉ
        lyi	ぃ
        xyi	ぃ
        lye	ぇ
        xye	ぇ
        ye	いぇ
        ka	か
        ki	き
        ku	く
        ke	け
        ko	こ
        xka	ヵ
        xke	ヶ
        lka	ヵ
        lke	ヶ
        ga	が
        gi	ぎ
        gu	ぐ
        ge	げ
        go	ご
        sa	さ
        si	し
        su	す
        se	せ
        so	そ
        ca	か
        ci	し
        cu	く
        ce	せ
        co	こ
        qa	くぁ
        qi	くぃ
        qu	く
        qe	くぇ
        qo	くぉ
        kwa	くぁ
        kwi	くぃ
        kwu	くぅ
        kwe	くぇ
        kwo	くぉ
        gwa	ぐぁ
        gwi	ぐぃ
        gwu	ぐぅ
        gwe	ぐぇ
        gwo	ぐぉ
        swa	すぁ
        swi	すぃ
        swu	すぅ
        swe	すぇ
        swo	すぉ
        zwa	ずぁ
        zwi	ずぃ
        zwu	ずぅ
        zwe	ずぇ
        zwo	ずぉ
        za	ざ
        zi	じ
        zu	ず
        ze	ぜ
        zo	ぞ
        ja	じゃ
        ji	じ
        ju	じゅ
        je	じぇ
        jo	じょ
        jya	じゃ
        jyi	じぃ
        jyu	じゅ
        jye	じぇ
        jyo	じょ
        ta	た
        ti	ち
        tu	つ
        tsu	つ
        te	て
        to	と
        da	だ
        di	ぢ
        du	づ
        de	で
        do	ど
        xtu	っ
        xtsu	っ
        ltu	っ
        ltsu	っ
        na	な
        ni	に
        nu	ぬ
        ne	ね
        no	の
        ha	は
        hi	ひ
        hu	ふ
        fu	ふ
        he	へ
        ho	ほ
        ba	ば
        bi	び
        bu	ぶ
        be	べ
        bo	ぼ
        pa	ぱ
        pi	ぴ
        pu	ぷ
        pe	ぺ
        po	ぽ
        ma	ま
        mi	み
        mu	む
        me	め
        mo	も
        xya	ゃ
        lya	ゃ
        ya	や
        wyi	ゐ
        xyu	ゅ
        lyu	ゅ
        yu	ゆ
        wye	ゑ
        xyo	ょ
        lyo	ょ
        yo	よ
        ra	ら
        ri	り
        ru	る
        re	れ
        ro	ろ
        xwa	ゎ
        lwa	ゎ
        wa	わ
        wi	うぃ
        we	うぇ
        wo	を
        wha	うぁ
        whi	うぃ
        whu	う
        whe	うぇ
        who	うぉ
    """.trimIndent()

    // Mozcの表に無いが、日本語入力では全角にする記号。ライブ変換で文の区切りとして扱われるよう全角へそろえる。
    private val EXTRA_ENTRIES = mapOf("?" to Entry("？", ""), "!" to Entry("！", ""))

    // ローマ字から項目への表。
    private val ENTRIES: Map<String, Entry> = buildMap {
        MOZC_TSV.lineSequence().filter(String::isNotBlank).forEach { line ->
            val columns = line.split('\t')
            put(columns[0], Entry(columns[1], columns.getOrElse(2) { "" }))
        }
        putAll(EXTRA_ENTRIES)
    }

    // 表のどれかのキーの、真の接頭辞すべて。続きを待つかどうかの判定に使う。
    private val PROPER_PREFIXES: Set<String> = ENTRIES.keys.flatMap { key -> (1 until key.length).map(key::take) }.toSet()

    /** ローマ字に完全一致する項目を返す。 */
    fun lookup(romaji: String): Entry? = ENTRIES[romaji]

    /** [romaji]で始まる、より長いキーがあるか。あれば次の文字を待つ。 */
    fun hasLongerKey(romaji: String): Boolean = romaji in PROPER_PREFIXES
}
