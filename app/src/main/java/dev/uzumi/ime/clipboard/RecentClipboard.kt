package dev.uzumi.ime.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import java.util.concurrent.atomic.AtomicLong

/**
 * 表示中のIMEが、現在のクリップボードを一時的な貼り付け候補として扱う。
 * Androidのクリップボード取得は既定IMEに許可されるため、呼出元はIME表示中だけ監視を開始し、非表示時に閉じる。
 */
class RecentClipboard(
    private val clipboardManager: ClipboardManager,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val lifetimeMillis: Long = RecentClipboardRules.DEFAULT_LIFETIME_MILLIS,
    private val previewCodePoints: Int = RecentClipboardRules.DEFAULT_PREVIEW_CODE_POINTS,
    private val onClipboardChanged: () -> Unit = {},
) : AutoCloseable {
    /** 候補バーへ表示する情報。プレビューがない場合は一般名「貼り付け」を使う。 */
    class Suggestion internal constructor(
        val preview: String?,
        val previewSuppressed: Boolean,
        val expiresAtMillis: Long,
        internal val identity: ClipboardIdentity,
    ) {
        val label: String
            get() = preview ?: "貼り付け"
    }

    /** 変更検出用の情報だけを持ち、クリップ本文は含めない。 */
    internal data class ClipboardIdentity(
        val changeVersion: Long,
        val timestamp: Long,
        val mimeTypes: List<String>,
        val clipboardSensitive: Boolean,
    )

    private val changeVersion = AtomicLong()

    @Volatile
    private var observing = false

    // ✕または貼り付けで閉じたclip。本文は持たず、同じclipの提案を再表示しないために使う。IMEを閉じても保持する。
    @Volatile
    private var dismissedIdentity: ClipboardIdentity? = null

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        changeVersion.incrementAndGet()
        if (observing) onClipboardChanged()
    }

    /** IME表示中のクリップボード変更を監視する。 */
    @Synchronized
    fun startObserving(): Boolean {
        if (observing) return true
        return try {
            clipboardManager.addPrimaryClipChangedListener(clipChangedListener)
            changeVersion.incrementAndGet()
            observing = true
            true
        } catch (_: RuntimeException) {
            observing = false
            false
        }
    }

    /** 機密clipまたは機密入力欄では本文を取得せず、一般名だけを返す。 */
    fun currentSuggestion(sensitiveField: Boolean = false): Suggestion? {
        if (!observing) return null
        val version = changeVersion.get()
        val metadata = snapshot(readDescription() ?: return null) ?: return null
        if (!RecentClipboardRules.isFresh(metadata.timestamp, clockMillis(), lifetimeMillis)) return null
        if (!RecentClipboardRules.hasPlainTextMimeType(metadata.mimeTypes)) return null

        if (dismissedIdentity?.let { matches(metadata, it) } == true) return null

        val previewSuppressed = metadata.clipboardSensitive || sensitiveField
        val preview = if (RecentClipboardRules.shouldReadTextBody(metadata.clipboardSensitive, sensitiveField)) {
            val clip = readPrimaryClip() ?: return null
            if (!sameMetadata(clipMetadata(clip), metadata)) return null
            val text = readTextItem(clip) ?: return null
            RecentClipboardRules.preview(text, previewCodePoints) ?: return null
        } else {
            null
        }

        if (!isCurrent(version, metadata, requireFresh = true)) return null
        return Suggestion(
            preview = preview,
            previewSuppressed = previewSuppressed,
            expiresAtMillis = metadata.timestamp + lifetimeMillis,
            identity = ClipboardIdentity(version, metadata.timestamp, metadata.mimeTypes, metadata.clipboardSensitive),
        )
    }

    /** 候補タップ時だけ本文を取得する。候補作成後にclipが変わった場合はnullを返す。 */
    fun readForPaste(suggestion: Suggestion): String? {
        if (!observing) return null
        val identity = suggestion.identity
        if (changeVersion.get() != identity.changeVersion) return null

        val metadata = snapshot(readDescription() ?: return null) ?: return null
        if (!matches(metadata, identity) ||
            !RecentClipboardRules.isFresh(metadata.timestamp, clockMillis(), lifetimeMillis)
        ) {
            return null
        }
        return readCurrentText(identity.changeVersion, metadata, requireFresh = true)
    }

    /** 本文を読まずに、期限にかかわらず貼り付けられるテキストclipがあるかをメタデータだけで判定する。 */
    fun hasCurrentText(): Boolean {
        if (!observing) return false
        val metadata = snapshot(readDescription() ?: return false) ?: return false
        return RecentClipboardRules.hasPlainTextMimeType(metadata.mimeTypes)
    }

    /** 通常の貼り付け操作で、タップされた時点の現在のテキストclipを読む。 */
    fun readCurrentForPaste(): String? {
        if (!observing) return null
        val version = changeVersion.get()
        val metadata = snapshot(readDescription() ?: return null) ?: return null
        return readCurrentText(version, metadata, requireFresh = false)
    }

    /** 提案を閉じ、同じclipでは自動提案を出さない。明示的な貼り付けは引き続きできる。 */
    fun dismiss(suggestion: Suggestion) {
        dismissedIdentity = suggestion.identity
    }

    /** IME非表示時に監視を止め、表示中に作った候補を無効にする。 */
    @Synchronized
    override fun close() {
        if (!observing) return
        observing = false
        changeVersion.incrementAndGet()
        try {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener)
        } catch (_: RuntimeException) {
            // 非表示後は監視状態を無効にしておく。
        }
    }

    private fun readCurrentText(version: Long, metadata: Metadata, requireFresh: Boolean): String? {
        if (!RecentClipboardRules.hasPlainTextMimeType(metadata.mimeTypes)) return null
        if (!isCurrent(version, metadata, requireFresh)) return null
        val clip = readPrimaryClip() ?: return null
        if (!sameMetadata(clipMetadata(clip), metadata)) return null
        val text = readTextItem(clip)?.takeIf(String::isNotEmpty) ?: return null
        if (!isCurrent(version, metadata, requireFresh)) return null
        return text
    }

    private fun isCurrent(version: Long, metadata: Metadata, requireFresh: Boolean): Boolean {
        if (!observing || changeVersion.get() != version) return false
        if (requireFresh && !RecentClipboardRules.isFresh(metadata.timestamp, clockMillis(), lifetimeMillis)) return false
        val current = snapshot(readDescription() ?: return false) ?: return false
        return changeVersion.get() == version && sameMetadata(current, metadata)
    }

    private fun readDescription(): ClipDescription? = try {
        clipboardManager.primaryClipDescription
    } catch (_: RuntimeException) {
        null
    }

    private fun readPrimaryClip(): ClipData? = try {
        clipboardManager.primaryClip
    } catch (_: RuntimeException) {
        null
    }

    private fun readTextItem(clip: ClipData): String? = try {
        if (clip.itemCount != 1) null else clip.getItemAt(0).text?.toString()
    } catch (_: RuntimeException) {
        null
    }

    private fun clipMetadata(clip: ClipData): Metadata? = try {
        snapshot(clip.description)
    } catch (_: RuntimeException) {
        null
    }

    private fun snapshot(description: ClipDescription): Metadata? = try {
        val mimeTypes = List(description.mimeTypeCount) { index -> description.getMimeType(index) }
        Metadata(
            timestamp = description.timestamp,
            mimeTypes = mimeTypes,
            clipboardSensitive = description.extras?.getBoolean(RecentClipboardRules.EXTRA_IS_SENSITIVE, false) == true,
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun sameMetadata(first: Metadata?, second: Metadata): Boolean =
        first != null && first.timestamp == second.timestamp && first.mimeTypes == second.mimeTypes &&
            first.clipboardSensitive == second.clipboardSensitive

    private fun matches(metadata: Metadata, identity: ClipboardIdentity): Boolean =
        metadata.timestamp == identity.timestamp && metadata.mimeTypes == identity.mimeTypes &&
            metadata.clipboardSensitive == identity.clipboardSensitive

    private data class Metadata(
        val timestamp: Long,
        val mimeTypes: List<String>,
        val clipboardSensitive: Boolean,
    )
}

/** Android frameworkに依存しない、期限・本文取得・表示短縮の規則。 */
internal object RecentClipboardRules {
    const val DEFAULT_LIFETIME_MILLIS = 60_000L
    const val DEFAULT_PREVIEW_CODE_POINTS = 24
    private const val PLAIN_TEXT_MIME_TYPE = "text/plain"

    /**
     * ClipDescription.EXTRA_IS_SENSITIVEと同じキー。API 33で定数が追加される前も、コピー元アプリは同じ文字列で機密clipを示すため、
     * minSdk 30でも版を分けずにこのキーでプレビューを抑止する。
     */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private val whitespace = Regex("\\s+")

    /** timestampはClipDescriptionと同じ壁時計のミリ秒で比較する。 */
    fun isFresh(timestamp: Long, nowMillis: Long, lifetimeMillis: Long): Boolean =
        timestamp > 0 && lifetimeMillis > 0 && timestamp <= nowMillis && nowMillis - timestamp <= lifetimeMillis

    fun hasPlainTextMimeType(mimeTypes: List<String>): Boolean =
        mimeTypes.any { it.equals(PLAIN_TEXT_MIME_TYPE, ignoreCase = true) }

    /** 機密欄または機密clipでは、候補作成のために本文を読まない。 */
    fun shouldReadTextBody(clipboardSensitive: Boolean, sensitiveField: Boolean): Boolean =
        !clipboardSensitive && !sensitiveField

    /** 表示用ラベルだけを短縮し、貼り付ける元本文は変更しない。 */
    fun preview(text: String, maxCodePoints: Int): String? {
        if (maxCodePoints <= 0) return null
        val normalized = text.replace(whitespace, " ").trim()
        if (normalized.isEmpty()) return null
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount <= maxCodePoints) return normalized

        val visibleCodePoints = maxCodePoints - 1
        if (visibleCodePoints == 0) return "…"
        val end = normalized.offsetByCodePoints(0, visibleCodePoints)
        return normalized.substring(0, end) + "…"
    }
}
