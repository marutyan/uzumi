package dev.uzumi.ime.keyboard

import dev.uzumi.ime.editor.KanaKeyToggle

/** ライト・ダークの選び方。端末に合わせるか、どちらかに固定する。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** キーの形。角丸の半径（dp）を持ち、枠なしは押していないキーの地を塗らない。 */
enum class KeyShape(val cornerDp: Float) {
    ROUNDED(KeyboardDimens.KEY_CORNER_DP),
    SQUARE(2f),
    PILL(18f),
    FLAT(KeyboardDimens.KEY_CORNER_DP),
}

/** キーと候補バーの文字の書体。 */
enum class KeyFont {
    /** 端末の既定のゴシック。 */
    GOTHIC,

    /** 端末のserif（日本語では明朝体）。 */
    MINCHO,

    /** 同梱したZen Maru Gothic。 */
    ZEN_MARU_GOTHIC,

    /** 同梱したYusei Magic。 */
    YUSEI_MAGIC,
}

/** かなキーを押している間のフリックの表示のしかた。 */
enum class FlickDisplayMode {
    /** 押している方向の吹き出しを1枚だけ出す（Simejiと同じ）。 */
    BUBBLE,

    /** 押している間、四方向の吹き出しを十字に出す。 */
    CROSS,

    /** キーの中の文字を押している方向の文字へ替え、四方向の案内を小さく出す（これまでのUzumi）。 */
    IN_KEY,

    /** 方向の表示を出さない。 */
    NONE,
}

/** フリックの吹き出しの形。形はPathで描く。 */
enum class FlickBubbleShape {
    PENTAGON,
    ROUNDED,
    CIRCLE,
    DROP,
    HEART,
    STAR,
    CLOUD,
}

/**
 * きせかえの設定。配色、背景画像の明るさとぼかし、キーの形と文字、フリックの表示を持つ。
 * 見た目だけを変え、入力される文字と評価の計数には影響しない。保存は[toStorage]・[fromStorage]の文字列で行う。
 */
data class KeyboardAppearance(
    val preset: ThemePreset = ThemePreset.STANDARD,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** カスタムのライトの色。カスタムを選ぶ前はnull。 */
    val customLight: RoleColors? = null,
    /** カスタムのダークの色。 */
    val customDark: RoleColors? = null,
    /** テーマとは別に選んだ文字の色。nullならテーマの色。 */
    val textColor: Int? = null,
    /** テーマとは別に選んだフリックの吹き出しの色。nullならテーマの色。 */
    val flickColor: Int? = null,
    /** 背景画像の明るさ（%）。 */
    val backgroundBrightness: Int = DEFAULT_BACKGROUND_BRIGHTNESS,
    /** 背景画像のぼかしの強さ（dp、0でぼかさない）。 */
    val backgroundBlur: Int = 0,
    val keyShape: KeyShape = KeyShape.ROUNDED,
    /** 背景画像があるときのキーの不透明度（%）。画像がなければ常に不透明に描く。 */
    val keyOpacity: Int = 100,
    /** キーの境界線を描くか。 */
    val keyBorder: Boolean = false,
    val keyFont: KeyFont = KeyFont.GOTHIC,
    /** キーの文字の大きさ（%）。 */
    val keyTextScale: Int = 100,
    val flickMode: FlickDisplayMode = FlickDisplayMode.BUBBLE,
    val flickShape: FlickBubbleShape = FlickBubbleShape.PENTAGON,
    /** 吹き出しの大きさ（%）。 */
    val flickSize: Int = 100,
    /** 吹き出しの不透明度（%）。 */
    val flickOpacity: Int = 100,
    /** かなキーに方向の文字を常に小さく表示するか。 */
    val flickGuide: Boolean = false,
    /** 背景画像を保存した時刻。0なら画像なし。画像の差し替えで入力Viewを作り直す判定に使い、保存とバックアップには含めない。 */
    val backgroundStamp: Long = 0L,
) {
    /** 背景画像を使うか。 */
    val hasBackgroundImage: Boolean
        get() = backgroundStamp != 0L

    /** 設定の保存とバックアップに使う文字列の組にする。画像の有無は含めない。 */
    fun toStorage(): Map<String, String> = buildMap {
        put(KEY_PRESET, preset.name)
        put(KEY_MODE, mode.name)
        customLight?.let { put(KEY_CUSTOM_LIGHT, it.encode()) }
        customDark?.let { put(KEY_CUSTOM_DARK, it.encode()) }
        textColor?.let { put(KEY_TEXT_COLOR, ThemeColorMath.toHex(it)) }
        flickColor?.let { put(KEY_FLICK_COLOR, ThemeColorMath.toHex(it)) }
        put(KEY_BACKGROUND_BRIGHTNESS, backgroundBrightness.toString())
        put(KEY_BACKGROUND_BLUR, backgroundBlur.toString())
        put(KEY_SHAPE, keyShape.name)
        put(KEY_OPACITY, keyOpacity.toString())
        put(KEY_BORDER, keyBorder.toString())
        put(KEY_FONT, keyFont.name)
        put(KEY_TEXT_SCALE, keyTextScale.toString())
        put(KEY_FLICK_MODE, flickMode.name)
        put(KEY_FLICK_SHAPE, flickShape.name)
        put(KEY_FLICK_SIZE, flickSize.toString())
        put(KEY_FLICK_OPACITY, flickOpacity.toString())
        put(KEY_FLICK_GUIDE, flickGuide.toString())
    }

    /**
     * 選んでいるテーマの色を、カスタムの色として写したうえでカスタムへ切り替える（試作と同じ）。すでにカスタムなら何もしない。
     */
    fun toCustom(): KeyboardAppearance {
        if (preset == ThemePreset.CUSTOM && customLight != null && customDark != null) return this
        val source = if (preset == ThemePreset.CUSTOM) ThemePreset.STANDARD else preset
        return copy(
            preset = ThemePreset.CUSTOM,
            customLight = customLight.takeIf { preset == ThemePreset.CUSTOM } ?: requireNotNull(source.light).roles(),
            customDark = customDark.takeIf { preset == ThemePreset.CUSTOM } ?: requireNotNull(source.dark).roles(),
        )
    }

    companion object {
        /** 背景画像の明るさの既定値（%）。試作と同じく少し暗くして文字を読みやすくする。 */
        const val DEFAULT_BACKGROUND_BRIGHTNESS = 85
        val BACKGROUND_BRIGHTNESS_RANGE = 30..130
        val BACKGROUND_BLUR_RANGE = 0..12
        val KEY_OPACITY_RANGE = 0..100
        val KEY_TEXT_SCALE_RANGE = 80..130
        val FLICK_SIZE_RANGE = 70..140
        val FLICK_OPACITY_RANGE = 30..100

        // 保存に使う名前。設定の保存先とバックアップの両方がこの名前を使う。
        const val KEY_PRESET = "theme"
        const val KEY_MODE = "themeMode"
        const val KEY_CUSTOM_LIGHT = "customLight"
        const val KEY_CUSTOM_DARK = "customDark"
        const val KEY_TEXT_COLOR = "textColor"
        const val KEY_FLICK_COLOR = "flickColor"
        const val KEY_BACKGROUND_BRIGHTNESS = "backgroundBrightness"
        const val KEY_BACKGROUND_BLUR = "backgroundBlur"
        const val KEY_SHAPE = "keyShape"
        const val KEY_OPACITY = "keyOpacity"
        const val KEY_BORDER = "keyBorder"
        const val KEY_FONT = "keyFont"
        const val KEY_TEXT_SCALE = "keyTextScale"
        const val KEY_FLICK_MODE = "flickMode"
        const val KEY_FLICK_SHAPE = "flickShape"
        const val KEY_FLICK_SIZE = "flickSize"
        const val KEY_FLICK_OPACITY = "flickOpacity"
        const val KEY_FLICK_GUIDE = "flickGuide"

        /** すべての保存名。保存し直すときに、無くなった項目（文字の色を戻したなど）を消すのに使う。 */
        val STORAGE_KEYS = listOf(
            KEY_PRESET, KEY_MODE, KEY_CUSTOM_LIGHT, KEY_CUSTOM_DARK, KEY_TEXT_COLOR, KEY_FLICK_COLOR,
            KEY_BACKGROUND_BRIGHTNESS, KEY_BACKGROUND_BLUR, KEY_SHAPE, KEY_OPACITY, KEY_BORDER, KEY_FONT,
            KEY_TEXT_SCALE, KEY_FLICK_MODE, KEY_FLICK_SHAPE, KEY_FLICK_SIZE, KEY_FLICK_OPACITY, KEY_FLICK_GUIDE,
        )

        /**
         * 保存した文字列から設定を読む。無い・読めない項目は既定値にし、範囲外の数値は範囲へ収める。
         * 古い版や新しい版のバックアップでも、分かる項目だけを使えるようにする。
         */
        fun fromStorage(values: Map<String, String>, backgroundStamp: Long = 0L): KeyboardAppearance {
            val defaults = KeyboardAppearance()
            return KeyboardAppearance(
                preset = enumValue(values[KEY_PRESET], defaults.preset),
                mode = enumValue(values[KEY_MODE], defaults.mode),
                customLight = values[KEY_CUSTOM_LIGHT]?.let(RoleColors::decode),
                customDark = values[KEY_CUSTOM_DARK]?.let(RoleColors::decode),
                textColor = values[KEY_TEXT_COLOR]?.let(ThemeColorMath::parseHex),
                flickColor = values[KEY_FLICK_COLOR]?.let(ThemeColorMath::parseHex),
                backgroundBrightness = intValue(values[KEY_BACKGROUND_BRIGHTNESS], defaults.backgroundBrightness, BACKGROUND_BRIGHTNESS_RANGE),
                backgroundBlur = intValue(values[KEY_BACKGROUND_BLUR], defaults.backgroundBlur, BACKGROUND_BLUR_RANGE),
                keyShape = enumValue(values[KEY_SHAPE], defaults.keyShape),
                keyOpacity = intValue(values[KEY_OPACITY], defaults.keyOpacity, KEY_OPACITY_RANGE),
                keyBorder = values[KEY_BORDER]?.toBooleanStrictOrNull() ?: defaults.keyBorder,
                keyFont = enumValue(values[KEY_FONT], defaults.keyFont),
                keyTextScale = intValue(values[KEY_TEXT_SCALE], defaults.keyTextScale, KEY_TEXT_SCALE_RANGE),
                flickMode = enumValue(values[KEY_FLICK_MODE], defaults.flickMode),
                flickShape = enumValue(values[KEY_FLICK_SHAPE], defaults.flickShape),
                flickSize = intValue(values[KEY_FLICK_SIZE], defaults.flickSize, FLICK_SIZE_RANGE),
                flickOpacity = intValue(values[KEY_FLICK_OPACITY], defaults.flickOpacity, FLICK_OPACITY_RANGE),
                flickGuide = values[KEY_FLICK_GUIDE]?.toBooleanStrictOrNull() ?: defaults.flickGuide,
                backgroundStamp = backgroundStamp,
            )
        }
    }
}

/**
 * 入力の操作の調整。フリックの判定幅と判定のしかた、長押しの時間、12キーのトグルの区切りを持つ。
 * 既定値はこれまでのUzumiの挙動（円・半径20dp、端末の長押し時間、1秒）と同じにする。
 */
data class InputTuning(
    /** フリックとみなす最小の移動距離（dp）。 */
    val flickThresholdDp: Int = DEFAULT_FLICK_THRESHOLD_DP,
    val flickJudge: FlickJudge = FlickJudge.CIRCLE,
    /** 長押しの時間（ミリ秒）。0なら端末の設定に従う。 */
    val longPressMillis: Int = 0,
    /** 同じキーのタップを同じ文字の切り替えとして扱う猶予（ミリ秒）。 */
    val toggleWindowMillis: Int = KanaKeyToggle.WINDOW_MILLIS.toInt(),
) {
    /** 設定の保存とバックアップに使う文字列の組にする。 */
    fun toStorage(): Map<String, String> = mapOf(
        KEY_FLICK_THRESHOLD to flickThresholdDp.toString(),
        KEY_FLICK_JUDGE to flickJudge.name,
        KEY_LONG_PRESS to longPressMillis.toString(),
        KEY_TOGGLE_WINDOW to toggleWindowMillis.toString(),
    )

    /** フリックの判定だけをSimejiに近い値（縦と横それぞれ12dp）にした設定。長押しとトグルは変えない。 */
    fun simejiLike(): InputTuning = copy(flickThresholdDp = SIMEJI_FLICK_THRESHOLD_DP, flickJudge = FlickJudge.AXIS)

    companion object {
        /** これまでのフリックの判定幅（dp）。 */
        const val DEFAULT_FLICK_THRESHOLD_DP = 20
        val FLICK_THRESHOLD_RANGE = 6..30
        val LONG_PRESS_RANGE = 200..800
        val TOGGLE_WINDOW_RANGE = 300..2000

        /** Simejiに近い判定幅（dp）。Simejiの実測は縦と横それぞれ約11dp。 */
        const val SIMEJI_FLICK_THRESHOLD_DP = 12

        const val KEY_FLICK_THRESHOLD = "flickThresholdDp"
        const val KEY_FLICK_JUDGE = "flickJudge"
        const val KEY_LONG_PRESS = "longPressMillis"
        const val KEY_TOGGLE_WINDOW = "toggleWindowMillis"

        /** すべての保存名。 */
        val STORAGE_KEYS = listOf(KEY_FLICK_THRESHOLD, KEY_FLICK_JUDGE, KEY_LONG_PRESS, KEY_TOGGLE_WINDOW)

        /** 保存した文字列から読む。無い・読めない項目は既定値にし、範囲外の数値は範囲へ収める。長押しの0以下は端末の設定に従う。 */
        fun fromStorage(values: Map<String, String>): InputTuning {
            val defaults = InputTuning()
            val longPress = values[KEY_LONG_PRESS]?.toIntOrNull() ?: defaults.longPressMillis
            return InputTuning(
                flickThresholdDp = intValue(values[KEY_FLICK_THRESHOLD], defaults.flickThresholdDp, FLICK_THRESHOLD_RANGE),
                flickJudge = enumValue(values[KEY_FLICK_JUDGE], defaults.flickJudge),
                longPressMillis = if (longPress <= 0) 0 else longPress.coerceIn(LONG_PRESS_RANGE.first, LONG_PRESS_RANGE.last),
                toggleWindowMillis = intValue(values[KEY_TOGGLE_WINDOW], defaults.toggleWindowMillis, TOGGLE_WINDOW_RANGE),
            )
        }
    }
}

/** 保存した名前から列挙値を読む。知らない名前なら既定値。 */
private inline fun <reified E : Enum<E>> enumValue(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

/** 保存した整数を範囲へ収めて読む。読めなければ既定値。 */
private fun intValue(text: String?, default: Int, range: IntRange): Int =
    text?.toIntOrNull()?.coerceIn(range.first, range.last) ?: default
