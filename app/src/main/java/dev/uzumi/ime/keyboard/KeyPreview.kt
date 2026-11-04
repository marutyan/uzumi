package dev.uzumi.ime.keyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 拡大表示を置く位置（px、入力Viewの左上が原点）。
 */
data class PreviewPlacement(val left: Float, val top: Float)

/**
 * 押したキーの拡大表示の位置を決める。キーの真上に隙間を空けて置き、左右と上端は入力Viewの内側へ収める。
 * 上端に収まらない最上段のキーでは、表示を下へずらして候補バーへ重ねる。
 */
fun placeKeyPreview(
    keyLeft: Float,
    keyTop: Float,
    keyWidth: Float,
    containerWidth: Float,
    previewWidth: Float,
    previewHeight: Float,
    gap: Float,
): PreviewPlacement {
    val maxLeft = (containerWidth - previewWidth).coerceAtLeast(0f)
    val left = (keyLeft + (keyWidth - previewWidth) / 2f).coerceIn(0f, maxLeft)
    val top = (keyTop - gap - previewHeight).coerceAtLeast(0f)
    return PreviewPlacement(left, top)
}

/**
 * 押したキーの文字を、キーの上へ大きく表示する（Simejiの「ポップアップ」に当たる）。
 * 入力View全体の重ね描き層（ViewOverlay）へ描くため、キーボードの枠の外、候補バーの上にも出せる。
 */
class KeyPreviewPopup(
    private val host: View,
    private val colors: KeyboardColors,
    private val typeface: Typeface = Typeface.DEFAULT,
) {
    private val density = host.resources.displayMetrics.density
    private val drawable = PreviewDrawable()
    private var attachedRoot: View? = null
    private val keyLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    /** キー[key]の上に[text]を表示する。表示中なら位置と文字を置き換える。 */
    fun show(key: View, text: String) {
        val root = host.rootView ?: return
        if (attachedRoot !== root) {
            hide()
            root.overlay.add(drawable)
            attachedRoot = root
        }
        key.getLocationInWindow(keyLocation)
        root.getLocationInWindow(rootLocation)
        val keyLeft = (keyLocation[0] - rootLocation[0]).toFloat()
        val keyTop = (keyLocation[1] - rootLocation[1]).toFloat()
        val width = minOf(PREVIEW_WIDTH_DP * density, key.width + PREVIEW_EXTRA_WIDTH_DP * density)
        val height = PREVIEW_HEIGHT_DP * density
        val place = placeKeyPreview(keyLeft, keyTop, key.width.toFloat(), root.width.toFloat(), width, height, PREVIEW_GAP_DP * density)
        drawable.text = text
        drawable.setBounds(place.left.toInt(), place.top.toInt(), (place.left + width).toInt(), (place.top + height).toInt())
        drawable.invalidateSelf()
    }

    /** 拡大表示を消す。 */
    fun hide() {
        attachedRoot?.overlay?.remove(drawable)
        attachedRoot = null
    }

    /** 角丸の背景と中央の文字だけを描く、拡大表示の本体。 */
    private inner class PreviewDrawable : Drawable() {
        var text: String = ""
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.popupBackground }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.onPopup
            textAlign = Paint.Align.CENTER
            textSize = PREVIEW_TEXT_DP * density
            typeface = this@KeyPreviewPopup.typeface
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            val radius = KeyboardDimens.KEY_CORNER_DP * density
            canvas.drawRoundRect(rect, radius, radius, background)
            val y = rect.centerY() - (label.descent() + label.ascent()) / 2f
            canvas.drawText(text, rect.centerX(), y, label)
        }

        override fun setAlpha(alpha: Int) {
            background.alpha = alpha
            label.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            background.colorFilter = colorFilter
            label.colorFilter = colorFilter
        }

        @Deprecated("Drawable.getOpacityはAPI 29で非推奨だが、minSdk 30でも実装が必要")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** 拡大表示の幅の上限（dp）。仕様の64×60dp。 */
        const val PREVIEW_WIDTH_DP = 64f

        /** 幅の狭いキー（QWERTY）では、キーの幅にこの値を足した幅にする（dp）。 */
        const val PREVIEW_EXTRA_WIDTH_DP = 12f

        /** 拡大表示の高さ（dp）。 */
        const val PREVIEW_HEIGHT_DP = 60f

        /** キーの上端と拡大表示の間（dp）。 */
        const val PREVIEW_GAP_DP = 8f

        /** 拡大表示の文字の大きさ（dp）。 */
        const val PREVIEW_TEXT_DP = 30f
    }
}

/** フリックの吹き出し一枚の方向と文字。 */
data class FlickBubbleLabel(val direction: FlickDirection, val text: String)

/** 押しているかなキーについて、中央の拡大表示に出す文字（出さなければnull）と、出す吹き出しを決めた結果。 */
data class FlickPreview(val centerText: String?, val bubbles: List<FlickBubbleLabel>)

// 十字のガイドで吹き出しを出す方向。
private val CROSS_DIRECTIONS = listOf(FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN, FlickDirection.LEFT)

/**
 * かなキー[type]を押して[direction]へ動かしている間の表示を決める。文字は入力と同じ配列定義
 * （[KeyboardLayoutData.getKanaChar]）から取り、吹き出しの文字が離したときに入る文字と必ず一致するようにする。
 * 中央の拡大表示はキーの真上に出るため、上の吹き出しと重ならないよう、吹き出しの方式では中央にいる間だけ出し、
 * 四方向の吹き出しを常に出す十字のガイドでは出さない。
 */
fun flickPreview(type: KanaKeyType, direction: FlickDirection, mode: FlickDisplayMode): FlickPreview {
    val center = KeyboardLayoutData.getKanaChar(type, FlickDirection.CENTER)
    val current = KeyboardLayoutData.getKanaChar(type, direction).ifEmpty { center }
    fun label(at: FlickDirection): FlickBubbleLabel? =
        KeyboardLayoutData.getKanaChar(type, at).takeIf { it.isNotEmpty() }?.let { FlickBubbleLabel(at, it) }
    return when (mode) {
        FlickDisplayMode.IN_KEY -> FlickPreview(current, emptyList())
        FlickDisplayMode.BUBBLE -> if (direction == FlickDirection.CENTER) {
            FlickPreview(center, emptyList())
        } else {
            FlickPreview(null, listOfNotNull(label(direction)))
        }
        FlickDisplayMode.CROSS -> FlickPreview(null, CROSS_DIRECTIONS.mapNotNull(::label))
        FlickDisplayMode.NONE -> FlickPreview(center.takeIf { direction == FlickDirection.CENTER }, emptyList())
    }
}

/**
 * 吹き出し一枚の置き方（px）。形は上向き（先が下のキーを指す）で幅[width]・高さ[height]・先の長さ[tip]に作り、
 * 中心（[centerX], [centerY]）の周りに[rotation]度だけ時計回りに回して描く。
 */
data class FlickBubbleGeometry(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val tip: Float,
    val rotation: Float,
)

/**
 * キー（左上[keyLeft], [keyTop]、幅[keyWidth]、高さ[keyHeight]、px）の[direction]側に出す吹き出しの置き方を返す。
 * 寸法はSimejiの実測（上下66×54dp・先14dp、左右68×47dp・先20dp）に[scale]を掛け、先をキーの縁から内側へ重ねる。
 */
fun flickBubbleGeometry(
    direction: FlickDirection,
    keyLeft: Float,
    keyTop: Float,
    keyWidth: Float,
    keyHeight: Float,
    density: Float,
    scale: Float,
): FlickBubbleGeometry {
    val unit = density * scale
    val vertical = direction == FlickDirection.UP || direction == FlickDirection.DOWN
    // 上向きの形での幅（向きと直角）と高さ（向きに沿う長さ）。左右の吹き出しは90度回すため、実測の縦横を入れ替える。
    val width = (if (vertical) BUBBLE_VERTICAL_WIDTH_DP else BUBBLE_HORIZONTAL_HEIGHT_DP) * unit
    val height = (if (vertical) BUBBLE_VERTICAL_HEIGHT_DP else BUBBLE_HORIZONTAL_WIDTH_DP) * unit
    val tip = (if (vertical) BUBBLE_VERTICAL_TIP_DP else BUBBLE_HORIZONTAL_TIP_DP) * unit
    val overlap = (if (vertical) BUBBLE_VERTICAL_OVERLAP_DP else BUBBLE_HORIZONTAL_OVERLAP_DP) * unit
    val centerX = keyLeft + keyWidth / 2f
    val centerY = keyTop + keyHeight / 2f
    return when (direction) {
        FlickDirection.UP -> FlickBubbleGeometry(centerX, keyTop + overlap - height / 2f, width, height, tip, 0f)
        FlickDirection.DOWN -> FlickBubbleGeometry(centerX, keyTop + keyHeight - overlap + height / 2f, width, height, tip, 180f)
        FlickDirection.RIGHT -> FlickBubbleGeometry(keyLeft + keyWidth - overlap + height / 2f, centerY, width, height, tip, 90f)
        FlickDirection.LEFT -> FlickBubbleGeometry(keyLeft + overlap - height / 2f, centerY, width, height, tip, 270f)
        FlickDirection.CENTER -> FlickBubbleGeometry(centerX, centerY, width, height, tip, 0f)
    }
}

/**
 * 上向き（先が下）の吹き出しの形を[path]へ作る。幅[w]・高さ[h]・先の長さ[tip]（px）、[unit]は1dpのpx（大きさの倍率込み）。
 */
fun buildFlickBubblePath(path: Path, shape: FlickBubbleShape, w: Float, h: Float, tip: Float, unit: Float) {
    path.reset()
    val body = h - tip
    when (shape) {
        FlickBubbleShape.PENTAGON -> {
            // Simejiの五角形。上の角だけを4dpの角丸にし、下は先へ向かって細くする。
            val r = PENTAGON_CORNER_DP * unit
            path.moveTo(r, 0f)
            path.lineTo(w - r, 0f)
            path.quadTo(w, 0f, w, r)
            path.lineTo(w, body)
            path.lineTo(w / 2f, h)
            path.lineTo(0f, body)
            path.lineTo(0f, r)
            path.quadTo(0f, 0f, r, 0f)
        }
        FlickBubbleShape.ROUNDED -> {
            val r = min(10f * unit, body / 2f)
            path.addRoundRect(RectF(0f, 0f, w, body), r, r, Path.Direction.CW)
            addTip(path, w, body, h, w * 0.12f)
        }
        FlickBubbleShape.CIRCLE -> {
            val radius = min(w, body) / 2f
            path.addCircle(w / 2f, body - radius, radius, Path.Direction.CW)
            addTip(path, w, body - unit * 3f, h, 8f * unit)
        }
        FlickBubbleShape.DROP -> {
            path.moveTo(w / 2f, h)
            path.cubicTo(w * 0.02f, h * 0.55f, w * 0.05f, 0f, w / 2f, 0f)
            path.cubicTo(w * 0.95f, 0f, w * 0.98f, h * 0.55f, w / 2f, h)
        }
        FlickBubbleShape.HEART -> {
            path.moveTo(w / 2f, h)
            path.cubicTo(w * 0.1f, h * 0.62f, 0f, h * 0.42f, 0f, h * 0.25f)
            path.cubicTo(0f, h * 0.06f, w * 0.2f, 0f, w * 0.32f, 0f)
            path.cubicTo(w * 0.42f, 0f, w * 0.48f, h * 0.08f, w / 2f, h * 0.16f)
            path.cubicTo(w * 0.52f, h * 0.08f, w * 0.58f, 0f, w * 0.68f, 0f)
            path.cubicTo(w * 0.8f, 0f, w, h * 0.06f, w, h * 0.25f)
            path.cubicTo(w, h * 0.42f, w * 0.9f, h * 0.62f, w / 2f, h)
        }
        FlickBubbleShape.STAR -> {
            // 10頂点の星。外側と内側の頂点を交互に結ぶ。
            val cx = w / 2f
            val cy = h * 0.5f
            val outer = min(w, h) * 0.52f
            val inner = outer * 0.5f
            for (i in 0 until 10) {
                val angle = Math.PI / 5.0 * i + Math.PI
                val radius = if (i % 2 == 0) outer else inner
                val x = cx + (radius * sin(angle)).toFloat()
                val y = cy + (radius * cos(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        FlickBubbleShape.CLOUD -> {
            // 円をいくつか重ねて雲にする。同じ向きの輪郭を重ねて塗るため、重なりは二重に塗られない。
            path.addCircle(w * 0.3f, body * 0.6f, body * 0.3f, Path.Direction.CW)
            path.addCircle(w * 0.55f, body * 0.42f, body * 0.4f, Path.Direction.CW)
            path.addCircle(w * 0.78f, body * 0.62f, body * 0.28f, Path.Direction.CW)
            path.addRoundRect(RectF(w * 0.12f, body * 0.55f, w * 0.9f, body), body * 0.2f, body * 0.2f, Path.Direction.CW)
            addTip(path, w, body - unit * 2f, h, w * 0.1f)
        }
    }
    path.close()
}

/** 吹き出しの下の辺（高さ[top]）から先（高さ[h]）へ向かう三角形を足す。[halfWidth]は根元の幅の半分。 */
private fun addTip(path: Path, w: Float, top: Float, h: Float, halfWidth: Float) {
    path.moveTo(w / 2f - halfWidth, top)
    path.lineTo(w / 2f + halfWidth, top)
    path.lineTo(w / 2f, h)
    path.close()
}

/**
 * フリックの方向の吹き出しを、入力View全体の重ね描き層（ViewOverlay）へ描く。キーの外、候補バーの上にも出せる。
 * 吹き出しの形・色・大きさ・不透明度はきせかえの設定に従う。
 */
class FlickBubblePopup(
    private val host: View,
    private val colors: KeyboardColors,
    private val appearance: KeyboardAppearance,
    private val typeface: Typeface,
) {
    private val density = host.resources.displayMetrics.density
    private val drawable = BubbleDrawable()
    private var attachedRoot: View? = null
    private val keyLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    /** キー[key]の周りに[bubbles]を出す。十字のガイドでは、押している方向[selected]の吹き出しを枠で示す。空なら消す。 */
    fun show(key: View, bubbles: List<FlickBubbleLabel>, selected: FlickDirection) {
        if (bubbles.isEmpty()) {
            hide()
            return
        }
        val root = host.rootView ?: return
        if (attachedRoot !== root) {
            hide()
            root.overlay.add(drawable)
            attachedRoot = root
        }
        key.getLocationInWindow(keyLocation)
        root.getLocationInWindow(rootLocation)
        val left = (keyLocation[0] - rootLocation[0]).toFloat()
        val top = (keyLocation[1] - rootLocation[1]).toFloat()
        val scale = appearance.flickSize / 100f
        drawable.items = bubbles.map { bubble ->
            BubbleItem(
                geometry = flickBubbleGeometry(bubble.direction, left, top, key.width.toFloat(), key.height.toFloat(), density, scale),
                text = bubble.text,
                selected = bubbles.size > 1 && bubble.direction == selected,
            )
        }
        drawable.unit = density * scale
        drawable.setBounds(0, 0, root.width, root.height)
        drawable.invalidateSelf()
    }

    /** 吹き出しを消す。 */
    fun hide() {
        attachedRoot?.overlay?.remove(drawable)
        attachedRoot = null
    }

    /** 描く吹き出し一枚。 */
    private data class BubbleItem(val geometry: FlickBubbleGeometry, val text: String, val selected: Boolean)

    /** すべての吹き出しを形のPathと中央の文字で描く。 */
    private inner class BubbleDrawable : Drawable() {
        var items: List<BubbleItem> = emptyList()
        var unit = density
        private val path = Path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ThemeColorMath.withAlpha(colors.flick, appearance.flickOpacity / 100f)
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = colors.onFlick
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.onFlick
            textAlign = Paint.Align.CENTER
            typeface = this@FlickBubblePopup.typeface
        }

        override fun draw(canvas: Canvas) {
            label.textSize = BUBBLE_TEXT_DP * unit
            stroke.strokeWidth = 2f * density
            items.forEach { item ->
                val g = item.geometry
                buildFlickBubblePath(path, appearance.flickShape, g.width, g.height, g.tip, unit)
                canvas.save()
                canvas.translate(g.centerX, g.centerY)
                canvas.rotate(g.rotation)
                canvas.translate(-g.width / 2f, -g.height / 2f)
                canvas.drawPath(path, fill)
                if (item.selected) canvas.drawPath(path, stroke)
                canvas.restore()
                // 文字は回さず、形の本体（先を除いた部分）の中心へ置く。上向きでの中心からのずれを、吹き出しの向きへ回す。
                val offset = -g.tip / 2f
                val radians = Math.toRadians(g.rotation.toDouble())
                val x = g.centerX - (offset * sin(radians)).toFloat()
                val y = g.centerY + (offset * cos(radians)).toFloat()
                canvas.drawText(item.text, x, y - (label.descent() + label.ascent()) / 2f, label)
            }
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            label.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            label.colorFilter = colorFilter
        }

        @Deprecated("Drawable.getOpacityはAPI 29で非推奨だが、minSdk 30でも実装が必要")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

// 吹き出しの寸法（dp）。Simejiの実測: 上下の吹き出しは66×54dp・先14dp、左右は68×47dp・先20dp、角丸4dp。
private const val BUBBLE_VERTICAL_WIDTH_DP = 66f
private const val BUBBLE_VERTICAL_HEIGHT_DP = 54f
private const val BUBBLE_VERTICAL_TIP_DP = 14f
private const val BUBBLE_HORIZONTAL_WIDTH_DP = 68f
private const val BUBBLE_HORIZONTAL_HEIGHT_DP = 47f
private const val BUBBLE_HORIZONTAL_TIP_DP = 20f
private const val PENTAGON_CORNER_DP = 4f

// 吹き出しの先をキーの縁から内側へ重ねる量（dp）。Simejiの画面では上下で約19dp、左右で約30dp重なっていた。
private const val BUBBLE_VERTICAL_OVERLAP_DP = 18f
private const val BUBBLE_HORIZONTAL_OVERLAP_DP = 28f

/** 吹き出しの文字の大きさ（dp）。 */
private const val BUBBLE_TEXT_DP = 23f

/**
 * 削除キーを左へドラッグしている間、キーの左に「離すと何が消えるか」を示す案内。
 * 閾値の手前では「1文字」、超えたら警告色で「行頭まで」と消える文字数を出す。入力欄の文字には手を加えない。
 */
class DeleteDragHint(private val host: View, private val colors: KeyboardColors) {
    private val density = host.resources.displayMetrics.density
    private val drawable = HintDrawable()
    private var attachedRoot: View? = null
    private val keyLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    /** 削除キー[key]の左に、状態[state]の案内を出す。[lineLength]は行頭までの文字数（分からなければnull）。 */
    fun show(key: View, state: DeleteDragState, lineLength: Int?) {
        val text = deleteDragHintText(state, lineLength)
        // 案内が無い状態（ドラッグしていない、または消す文字が無い）では札を出さない
        if (text.isEmpty()) {
            hide()
            return
        }
        val root = host.rootView ?: return
        if (attachedRoot !== root) {
            hide()
            root.overlay.add(drawable)
            attachedRoot = root
        }
        drawable.text = text
        drawable.armed = state == DeleteDragState.ARMED
        key.getLocationInWindow(keyLocation)
        root.getLocationInWindow(rootLocation)
        val height = HINT_HEIGHT_DP * density
        val width = drawable.textWidth() + HINT_PADDING_DP * 2 * density
        val right = (keyLocation[0] - rootLocation[0]).toFloat()
        val top = (keyLocation[1] - rootLocation[1]) + (key.height - height) / 2f
        val left = (right - width).coerceAtLeast(0f)
        drawable.setBounds(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt())
        drawable.invalidateSelf()
    }

    /** 案内を消す。 */
    fun hide() {
        attachedRoot?.overlay?.remove(drawable)
        attachedRoot = null
    }

    /** 左端を丸めた札と文字を描く。 */
    private inner class HintDrawable : Drawable() {
        var text: String = ""
        var armed = false
        private val background = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = HINT_TEXT_DP * density
        }
        private val rect = RectF()

        /** 文字の幅（px）。札の幅を決めるために使う。 */
        fun textWidth(): Float = label.measureText(text)

        override fun draw(canvas: Canvas) {
            background.color = if (armed) colors.danger else colors.functionPressed
            label.color = if (armed) colors.onDanger else colors.text
            label.isFakeBoldText = armed
            rect.set(bounds)
            val radius = rect.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, background)
            val y = rect.centerY() - (label.descent() + label.ascent()) / 2f
            canvas.drawText(text, rect.centerX(), y, label)
        }

        override fun setAlpha(alpha: Int) {
            background.alpha = alpha
            label.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            background.colorFilter = colorFilter
            label.colorFilter = colorFilter
        }

        @Deprecated("Drawable.getOpacityはAPI 29で非推奨だが、minSdk 30でも実装が必要")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** 案内の札の高さ（dp）。 */
        const val HINT_HEIGHT_DP = 44f

        /** 札の左右の余白（dp）。 */
        const val HINT_PADDING_DP = 14f

        /** 案内の文字の大きさ（dp）。 */
        const val HINT_TEXT_DP = 14f
    }
}

/**
 * 削除ドラッグの案内の文言を決める。行頭までの文字数が分かる場合は、離す前に消える量を示す。
 * 消す文字が0文字（空の欄など）なら離しても何も消えないため、空文字を返して案内を出さない。
 */
fun deleteDragHintText(state: DeleteDragState, lineLength: Int?): String = when (state) {
    DeleteDragState.ARMED -> when (lineLength) {
        null -> "× 行頭まで"
        0 -> ""
        else -> "× 行頭まで ${lineLength}文字"
    }
    DeleteDragState.DRAGGING -> "× 1文字"
    DeleteDragState.CANCELED -> "取り消し"
    DeleteDragState.NONE -> ""
}
