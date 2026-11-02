package dev.uzumi.ime.keyboard

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject

/** 絵文字・顔文字の表示カテゴリをassetから読み込んだ値として表す。 */
internal data class EmojiPickerCategory(
    val id: String,
    val label: String,
    val spokenLabel: String,
)

/** 一覧の一項目を表し、本文、TalkBack名、所属カテゴリ、種類だけを保持する。 */
internal data class EmojiPickerItem(
    val value: String,
    val label: String,
    val categories: Set<String>,
    val isKaomoji: Boolean,
)

/** 読み込み済みの項目と、表示可能なカテゴリをまとめる。 */
internal data class EmojiPickerCatalog(
    val categories: List<EmojiPickerCategory>,
    val items: List<EmojiPickerItem>,
)

/** SharedPreferencesへ保存する最近使った項目の規則を持つ。 */
internal object EmojiRecentHistory {
    /** 履歴に残す項目数の上限。 */
    const val MAX_ITEMS = 48

    /** 入力が成功し機密欄でないときだけ、重複を除いて値を先頭へ移す。 */
    fun updated(
        current: List<String>,
        value: String,
        insertionSucceeded: Boolean,
        isSensitive: Boolean,
    ): List<String> {
        if (!insertionSucceeded || isSensitive || value.isEmpty()) return current
        val next = LinkedHashSet<String>()
        next.add(value)
        current.forEach { next.add(it) }
        return next.take(MAX_ITEMS)
    }
}

/** 最近使った絵文字だけをアプリの非バックアップSharedPreferencesに保存する。 */
internal class EmojiRecentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 保存済み履歴を重複のない順序で読み、件数を上限内に保つ。 */
    fun load(): List<String> {
        val encoded = preferences.getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).takeIf { it.isNotEmpty() } ?: continue
                    if (value !in this) add(value)
                    if (size == EmojiRecentHistory.MAX_ITEMS) break
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 入力成功と欄の機密状態を確認し、履歴が変わった場合だけ保存する。 */
    fun record(value: String, insertionSucceeded: Boolean, isSensitive: Boolean): List<String> {
        val current = load()
        val updated = EmojiRecentHistory.updated(current, value, insertionSucceeded, isSensitive)
        if (updated != current) {
            val array = JSONArray().apply { updated.forEach { put(it) } }
            preferences.edit().putString(KEY_RECENT, array.toString()).apply()
        }
        return updated
    }

    companion object {
        /** 履歴を設定や他のアプリ状態と分けて置くSharedPreferences名。 */
        private const val PREFERENCES_NAME = "uzumi_emoji_picker"

        /** 保存対象は選んだ絵文字・顔文字の値だけに限定する。 */
        private const val KEY_RECENT = "recent_values"
    }
}

/** 初回に一覧を開いたときだけ生成済みassetを読み、未対応絵文字を除外する。 */
internal object EmojiPickerCatalogLoader {
    /** 起動時の読み込みを避け、絵文字一覧を開いた時だけ参照するasset名。 */
    private const val ASSET_PATH = "emoji/emoji.json"

    /** JSON assetからカテゴリと項目を読み込み、絵文字フォントに存在しない項目を外す。 */
    fun load(context: Context): EmojiPickerCatalog {
        val encoded = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(encoded)
        val categoriesJson = root.getJSONArray("categories")
        val categories = buildList {
            for (index in 0 until categoriesJson.length()) {
                val item = categoriesJson.getJSONObject(index)
                add(EmojiPickerCategory(item.getString("id"), item.getString("label"), item.getString("spoken")))
            }
        }
        val supportedEmoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
        val itemJson = root.getJSONArray("items")
        val items = buildList {
            for (index in 0 until itemJson.length()) {
                val item = itemJson.getJSONObject(index)
                val value = item.getString("value")
                val isKaomoji = item.getString("kind") == "kaomoji"
                if (!isKaomoji && !supportedEmoji.hasGlyph(value)) continue
                val itemCategories = item.getJSONArray("categories").toStringSet()
                add(EmojiPickerItem(value, item.getString("label"), itemCategories, isKaomoji))
            }
        }
        val visibleIds = items.flatMap { it.categories }.toSet()
        return EmojiPickerCatalog(categories.filter { it.id in visibleIds }, items)
    }

    /** JSON配列のカテゴリIDを順序を保ったSetへ変換する。 */
    private fun JSONArray.toStringSet(): Set<String> = buildSet {
        for (index in 0 until length()) add(getString(index))
    }

}
