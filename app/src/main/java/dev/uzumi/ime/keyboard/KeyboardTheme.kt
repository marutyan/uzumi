package dev.uzumi.ime.keyboard

import android.content.Context
import android.content.res.Configuration
import dev.uzumi.ime.R
import dev.uzumi.ime.UzumiSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * キーボードの配色。「標準」テーマの値は`res/values`と`res/values-night`の色resourceに置き、
 * ほかのテーマとカスタムは[ThemePalette]から[deriveKeyboardColors]で導く。きせかえの設定で選んだ明るさ（端末に合わせる／ライト／ダーク）に従う。
 */
data class KeyboardColors(
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyPressed: Int,
    val functionBackground: Int,
    val functionPressed: Int,
    val keyActive: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val text: Int,
    val textSecondary: Int,
    val popupBackground: Int,
    val onPopup: Int,
    val danger: Int,
    val onDanger: Int,
    val bar: Int,
    val divider: Int,
    /** フリックの吹き出しの地色。 */
    val flick: Int,
    /** フリックの吹き出しの上の文字の色。 */
    val onFlick: Int,
) {
    companion object {
        /** きせかえの設定（未指定なら保存済みの設定）と端末のライト／ダーク設定から配色を決める。 */
        fun from(
            context: Context,
            appearance: KeyboardAppearance = UzumiSettings.keyboardAppearance(context),
        ): KeyboardColors {
            val dark = when (appearance.mode) {
                ThemeMode.SYSTEM -> isNightMode(context.resources.configuration)
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val standard = resourceColors(context, dark)
            val palette = themePalette(appearance, dark)
            val base = palette?.let { deriveKeyboardColors(it, standard.danger, standard.onDanger) } ?: standard
            return base.withOverrides(appearance.textColor, appearance.flickColor)
        }

        /** 構成がダークモードか。 */
        private fun isNightMode(configuration: Configuration): Boolean =
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        /** 「標準」テーマの配色をresourceから読む。端末と違う明るさを選んだときは、その明るさの構成でresourceを読む。 */
        private fun resourceColors(context: Context, dark: Boolean): KeyboardColors {
            val source = if (isNightMode(context.resources.configuration) == dark) {
                context
            } else {
                val configuration = Configuration(context.resources.configuration)
                val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                context.createConfigurationContext(configuration)
            }
            val flick = source.getColor(R.color.kb_flick)
            return KeyboardColors(
                keyboardBackground = source.getColor(R.color.kb_background),
                keyBackground = source.getColor(R.color.kb_key),
                keyPressed = source.getColor(R.color.kb_key_pressed),
                functionBackground = source.getColor(R.color.kb_function),
                functionPressed = source.getColor(R.color.kb_function_pressed),
                keyActive = source.getColor(R.color.kb_key_active),
                accent = source.getColor(R.color.kb_accent),
                accentPressed = source.getColor(R.color.kb_accent_pressed),
                onAccent = source.getColor(R.color.kb_on_accent),
                text = source.getColor(R.color.kb_text),
                textSecondary = source.getColor(R.color.kb_text_secondary),
                popupBackground = source.getColor(R.color.kb_popup),
                onPopup = source.getColor(R.color.kb_on_popup),
                danger = source.getColor(R.color.kb_danger),
                onDanger = source.getColor(R.color.kb_on_danger),
                bar = source.getColor(R.color.kb_bar),
                divider = source.getColor(R.color.kb_divider),
                flick = flick,
                onFlick = ThemeColorMath.onColor(flick, LARGE_TEXT_CONTRAST),
            )
        }
    }
}

/** 地色やキーなど、カスタムで利用者が選べる色の役割。 */
enum class ColorRole {
    BACKGROUND,
    KEY,
    FUNCTION,
    ACCENT,
    TEXT,
    FLICK,
}

/** カスタムテーマの一つの明るさ（ライトまたはダーク）で利用者が選んだ6役割の色。 */
data class RoleColors(
    val background: Int,
    val key: Int,
    val function: Int,
    val accent: Int,
    val text: Int,
    val flick: Int,
) {
    /** 役割の色を返す。 */
    operator fun get(role: ColorRole): Int = when (role) {
        ColorRole.BACKGROUND -> background
        ColorRole.KEY -> key
        ColorRole.FUNCTION -> function
        ColorRole.ACCENT -> accent
        ColorRole.TEXT -> text
        ColorRole.FLICK -> flick
    }

    /** 一つの役割だけを差し替えた色の組を返す。 */
    fun with(role: ColorRole, color: Int): RoleColors = when (role) {
        ColorRole.BACKGROUND -> copy(background = color)
        ColorRole.KEY -> copy(key = color)
        ColorRole.FUNCTION -> copy(function = color)
        ColorRole.ACCENT -> copy(accent = color)
        ColorRole.TEXT -> copy(text = color)
        ColorRole.FLICK -> copy(flick = color)
    }

    /** 保存用の文字列（`#RRGGBB`をカンマで6つ並べる）にする。 */
    fun encode(): String = ColorRole.entries.joinToString(",") { ThemeColorMath.toHex(get(it)) }

    companion object {
        /** [encode]の文字列を読む。形式が違えばnull。 */
        fun decode(text: String): RoleColors? {
            val colors = text.split(',').map { ThemeColorMath.parseHex(it.trim()) ?: return null }
            if (colors.size != ColorRole.entries.size) return null
            return RoleColors(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5])
        }
    }
}

/**
 * テーマの一つの明るさの配色。試作で決めた9役割を持ち、押下中・強調色の上の文字などは[deriveKeyboardColors]で導く。
 */
data class ThemePalette(
    val background: Int,
    val key: Int,
    val function: Int,
    val active: Int,
    val accent: Int,
    val text: Int,
    val bar: Int,
    val divider: Int,
    val flick: Int,
) {
    /** カスタムへ写すときの6役割の色。 */
    fun roles(): RoleColors = RoleColors(background, key, function, accent, text, flick)
}

/**
 * プリセットのテーマ。値はきせかえの試作のライト・ダークの配色。標準は`res`の色と同じ値で、実際の描画ではresourceを使う。
 */
enum class ThemePreset(val light: ThemePalette?, val dark: ThemePalette?) {
    STANDARD(
        palette("#E4E7EB", "#FFFFFF", "#CDD3DA", "#D6E1F3", "#345995", "#1B1F24", "#F3F4F6", "#D0D5DB", "#8A8A8A"),
        palette("#17191D", "#34373D", "#262A30", "#2D3C57", "#9DB8EA", "#E8EAED", "#1E2126", "#3A3F47", "#6B7078"),
    ),
    WASHI(
        palette("#E9E3D6", "#FBF8F1", "#DDD5C4", "#E4D9C2", "#5B4A36", "#2A241C", "#F3EEE3", "#D3C9B5", "#8C7B64"),
        palette("#1C1A16", "#34302A", "#28251F", "#4A4033", "#D8C3A0", "#EDE6D8", "#23201B", "#463F35", "#7A6A55"),
    ),
    AI(
        palette("#D9E1EE", "#F6F8FC", "#C3CFE2", "#C9D7F0", "#1F3A68", "#152238", "#E8EDF6", "#B9C6DB", "#4A6290"),
        palette("#0E1626", "#22304A", "#18233A", "#2B4170", "#8FB0EC", "#E3E9F5", "#121C30", "#2C3A55", "#3E5683"),
    ),
    WAKATAKE(
        palette("#DCE7DD", "#F7FBF7", "#C5D6C8", "#CFE3D2", "#2F6B45", "#16261B", "#EAF2EB", "#BBCFBF", "#5D8A6B"),
        palette("#111A14", "#263429", "#1B261E", "#2E4A36", "#9ED2AE", "#E2EEE5", "#152019", "#2D3C31", "#466B52"),
    ),
    SAKURA(
        palette("#F1E1E4", "#FFF9FA", "#E5CBD1", "#F0D3DA", "#9C3D57", "#2E1A20", "#F8EDEF", "#DDC1C8", "#C07A8C"),
        palette("#1E1416", "#3A2A2E", "#2B1F22", "#55323C", "#F0A9BC", "#F2E4E7", "#24181B", "#47353A", "#8A5563"),
    ),
    SUMI(
        palette("#D8D8D8", "#F5F5F5", "#C4C4C4", "#DADADA", "#222222", "#111111", "#E9E9E9", "#BDBDBD", "#555555"),
        palette("#000000", "#1E1E1E", "#141414", "#333333", "#E6E6E6", "#F0F0F0", "#0A0A0A", "#2A2A2A", "#444444"),
    ),

    /** 利用者が選んだ色。値は[KeyboardAppearance.customLight]・[KeyboardAppearance.customDark]に持つ。 */
    CUSTOM(null, null),
    ;

    /** 明るさに合う配色。CUSTOMではnull。 */
    fun palette(dark: Boolean): ThemePalette? = if (dark) this.dark else light
}

/** `#RRGGBB`の9つの色からテーマの配色を作る。プリセットの定義だけに使う。 */
private fun palette(vararg hex: String): ThemePalette {
    val c = hex.map { requireNotNull(ThemeColorMath.parseHex(it)) }
    return ThemePalette(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8])
}

/**
 * 選んだテーマと明るさの配色を返す。標準ではnullを返し、呼び出し側がresourceの色を使う。
 * カスタムでは、候補バー・区切り線・有効なキーの色を試作と同じ規則で地色と強調色から導く。
 */
fun themePalette(appearance: KeyboardAppearance, dark: Boolean): ThemePalette? {
    if (appearance.preset == ThemePreset.STANDARD) return null
    if (appearance.preset != ThemePreset.CUSTOM) return appearance.preset.palette(dark)
    val roles = (if (dark) appearance.customDark else appearance.customLight)
        ?: requireNotNull(ThemePreset.STANDARD.palette(dark)).roles()
    return customPalette(roles)
}

/** カスタムの6役割から、地色が暗いかどうかで向きを変えて候補バー・区切り線・有効なキーの色を導く（試作と同じ規則）。 */
fun customPalette(roles: RoleColors): ThemePalette {
    val backgroundDark = ThemeColorMath.luminance(roles.background) < DARK_BACKGROUND_LUMINANCE
    return ThemePalette(
        background = roles.background,
        key = roles.key,
        function = roles.function,
        active = ThemeColorMath.shiftLightness(roles.accent, if (backgroundDark) -25f else 38f),
        accent = roles.accent,
        text = roles.text,
        bar = ThemeColorMath.shiftLightness(roles.background, if (backgroundDark) 3f else 5f),
        divider = ThemeColorMath.shiftLightness(roles.background, if (backgroundDark) 12f else -10f),
        flick = roles.flick,
    )
}

/**
 * テーマの配色からキーボード全体の配色を導く。押下中は明るい地色では黒を18%、暗い地色では白を12%混ぜ、
 * 強調色と吹き出しの上の文字は白か濃い灰色のうちコントラストが足りる方を選ぶ。警告色は標準の値を使う。
 */
fun deriveKeyboardColors(palette: ThemePalette, danger: Int, onDanger: Int): KeyboardColors {
    val backgroundDark = ThemeColorMath.luminance(palette.background) < DARK_BACKGROUND_LUMINANCE
    val onAccent = ThemeColorMath.onColor(palette.accent, NORMAL_TEXT_CONTRAST)
    return KeyboardColors(
        keyboardBackground = palette.background,
        keyBackground = palette.key,
        keyPressed = pressedColor(palette.key, backgroundDark),
        functionBackground = palette.function,
        functionPressed = pressedColor(palette.function, backgroundDark),
        keyActive = palette.active,
        accent = palette.accent,
        accentPressed = pressedColor(palette.accent, backgroundDark),
        onAccent = onAccent,
        text = palette.text,
        textSecondary = secondaryText(palette.text, palette.key),
        popupBackground = palette.accent,
        onPopup = onAccent,
        danger = danger,
        onDanger = onDanger,
        bar = palette.bar,
        divider = palette.divider,
        flick = palette.flick,
        onFlick = ThemeColorMath.onColor(palette.flick, LARGE_TEXT_CONTRAST),
    )
}

/** 利用者が別に選んだ文字の色と吹き出しの色を配色へ当てる。nullの項目はテーマの色のまま。 */
fun KeyboardColors.withOverrides(textColor: Int?, flickColor: Int?): KeyboardColors {
    var result = this
    if (textColor != null) result = result.copy(text = textColor, textSecondary = secondaryText(textColor, keyBackground))
    if (flickColor != null) result = result.copy(flick = flickColor, onFlick = ThemeColorMath.onColor(flickColor, LARGE_TEXT_CONTRAST))
    return result
}

/** 押下中の色。明るい地色のテーマでは暗く、暗い地色のテーマでは明るくする。 */
private fun pressedColor(color: Int, backgroundDark: Boolean): Int =
    if (backgroundDark) ThemeColorMath.mix(color, WHITE, 0.12f) else ThemeColorMath.mix(color, BLACK, 0.18f)

/** 補足の文字（長押し文字の案内など）の色。文字色をキーの色へ38%寄せて控えめにする。 */
private fun secondaryText(text: Int, key: Int): Int = ThemeColorMath.mix(text, key, 0.38f)

/** 文字とフリックの色の選択肢（試作と同じ15色。Simejiの横長のパレットに近い並び）。名前はresourceの同じ順の配列に置く。 */
val COLOR_CHIP_PALETTE: List<Int> = listOf(
    "#FFFFFF", "#9AA0A6", "#3C4043", "#000000", "#E53935", "#FB8C00", "#8D6E63", "#EC407A",
    "#FDD835", "#43A047", "#26A69A", "#29B6F6", "#1E88E5", "#283593", "#8E24AA",
).map { requireNotNull(ThemeColorMath.parseHex(it)) }

/** 地色が暗いとみなす相対輝度の上限。試作の規則と同じ値。 */
private const val DARK_BACKGROUND_LUMINANCE = 0.2

/** 通常の大きさの文字（Enterキーのラベルなど）に求めるコントラスト比。 */
private const val NORMAL_TEXT_CONTRAST = 4.5

/** 大きな文字（フリックの吹き出し）に求めるコントラスト比。 */
const val LARGE_TEXT_CONTRAST = 3.0

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

/**
 * きせかえで使う色の計算（`#RRGGBB`とHSLの行き来、相対輝度、コントラスト比、混色）。
 * android.graphics.Colorに頼らず、JVMテストで同じ計算を確かめられるようにする。
 */
object ThemeColorMath {
    /** 強調色などの上へ置く濃い文字色。 */
    const val DARK_ON_COLOR = 0xFF111111.toInt()

    /** 強調色などの上へ置く白い文字色。 */
    const val LIGHT_ON_COLOR = 0xFFFFFFFF.toInt()

    /** `#RRGGBB`を不透明な色の整数へ直す。形式が違えばnull。 */
    fun parseHex(text: String): Int? {
        if (text.length != 7 || text[0] != '#') return null
        val value = text.substring(1).toIntOrNull(16) ?: return null
        return value or 0xFF000000.toInt()
    }

    /** 色を`#RRGGBB`（大文字）にする。透明度は捨てる。 */
    fun toHex(color: Int): String = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    /** 赤・緑・青（0〜255）から不透明な色を作る。 */
    fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    /** 色の透明度を[alpha]（0〜1）にする。 */
    fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (color and 0xFFFFFF)

    /** [from]へ[to]を割合[amount]（0〜1）だけ混ぜる。透明度は[from]を保つ。 */
    fun mix(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).roundToInt()
        }
        return (from and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** 色をHSL（色相0〜360、彩度0〜100、明度0〜100）に直す。 */
    fun toHsl(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val lightness = (maximum + minimum) / 2f
        var hue = 0f
        var saturation = 0f
        if (maximum != minimum) {
            val d = maximum - minimum
            saturation = if (lightness > 0.5f) d / (2f - maximum - minimum) else d / (maximum + minimum)
            hue = when (maximum) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        }
        return floatArrayOf(hue, saturation * 100f, lightness * 100f)
    }

    /** HSL（色相0〜360、彩度0〜100、明度0〜100）から不透明な色を作る。 */
    fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
        val s = saturation.coerceIn(0f, 100f) / 100f
        val l = lightness.coerceIn(0f, 100f) / 100f
        val h = ((hue % 360f) + 360f) % 360f
        val a = s * min(l, 1f - l)
        fun f(n: Int): Int {
            val k = (n + h / 30f) % 12f
            val value = l - a * max(-1f, min(k - 3f, min(9f - k, 1f)))
            return (value * 255f).roundToInt()
        }
        return rgb(f(0), f(8), f(4))
    }

    /** HSLの明度だけを[delta]（百分率の点）ずらす。 */
    fun shiftLightness(color: Int, delta: Float): Int {
        val (h, s, l) = toHsl(color)
        return fromHsl(h, s, (l + delta).coerceIn(0f, 100f))
    }

    /** WCAGの相対輝度（0〜1）。 */
    fun luminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val v = channel / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear((color shr 16) and 0xFF) +
            0.7152 * linear((color shr 8) and 0xFF) +
            0.0722 * linear(color and 0xFF)
    }

    /** 二つの色のコントラスト比（1〜21）。 */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * [background]の上へ置く文字の色を、白と濃い灰色から選ぶ。白で[minimumContrast]以上あれば白を選び、
     * 足りなければコントラストの高い方を選ぶ（Simejiの灰色の吹き出しの白い文字を保ちつつ、明るい色では読める色にする）。
     */
    fun onColor(background: Int, minimumContrast: Double): Int {
        val light = contrast(background, LIGHT_ON_COLOR)
        if (light >= minimumContrast) return LIGHT_ON_COLOR
        val dark = contrast(background, DARK_ON_COLOR)
        return if (dark >= light) DARK_ON_COLOR else LIGHT_ON_COLOR
    }

    /** 二つの色がほぼ同じか（各成分の差が[tolerance]以下）。テストと選択中の表示に使う。 */
    fun isClose(a: Int, b: Int, tolerance: Int = 2): Boolean =
        listOf(16, 8, 0).all { abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= tolerance }
}

/**
 * キーの形の寸法（dp）。Simejiの実測と仕様案に合わせた値を一か所に置き、キーの描画が同じ値を使うようにする。
 */
object KeyboardDimens {
    /** 12キー・数字・記号の1行の高さ。Simejiの実測66.3dpに合わせる。 */
    const val ROW_HEIGHT_DP = 66f

    /** QWERTYの1行の高さ。数字行を含む5行で、ほかの面の4行とほぼ同じ高さになる。 */
    const val QWERTY_ROW_HEIGHT_DP = 53f

    /** キーボード全体の外周の余白。 */
    const val PANEL_PADDING_DP = 2f

    /** キーの描画を枠から内側へ縮める量。隣のキーとの見た目の隙間は2倍の4dpになる。 */
    const val KEY_INSET_DP = 2f

    /** キーの角丸の半径。 */
    const val KEY_CORNER_DP = 8f
}
