package dev.uzumi.ime.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * きせかえの背景画像を、アプリの非公開領域（no_backup。Androidの自動バックアップの対象外）へ保存し、読み出す。
 * 写真は選んだときに一度だけ縮小・切り抜きして保存し、元の写真への参照や読み取り権限は持ち続けない。
 */
object KeyboardBackgroundImage {
    // 保存先のファイル名。画像は1枚だけ持ち、選び直すと置き換える。
    private const val FILE_NAME = "keyboard_background.jpg"

    /** 保存する画像の幅の上限（px）。高解像度の端末でも読み込みの時間とメモリを抑える。 */
    private const val MAX_WIDTH_PX = 1440

    /** JPEGの画質。背景として十分で、ファイルを大きくしすぎない値。 */
    private const val JPEG_QUALITY = 90

    /** ぼかしを計算するときに縮める倍率の逆数。縮めた画像をぼかして拡大して描き、計算量を減らす。 */
    private const val BLUR_DOWNSCALE = 4

    // 最後に読み込んだ画像。入力Viewを作り直すたびに読み直さないよう、保存時刻とぼかしが同じなら使い回す。
    @Volatile
    private var cached: Triple<Long, Int, Bitmap>? = null

    /** 保存先のファイル。 */
    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /**
     * [uri]の写真を読み、キーボードの大きさ（[keyboardWidthPx]×[keyboardHeightPx]）の縦横比で中央を切り抜いて保存する。
     * 画面より大きい写真は読み込み時に縮める。UI thread外で呼ぶ。保存できなければfalseを返す。
     */
    fun save(context: Context, uri: Uri, keyboardWidthPx: Int, keyboardHeightPx: Int): Boolean {
        if (keyboardWidthPx <= 0 || keyboardHeightPx <= 0) return false
        val targetWidth = minOf(keyboardWidthPx, MAX_WIDTH_PX)
        val targetHeight = max(1, (keyboardHeightPx.toLong() * targetWidth / keyboardWidthPx).toInt())
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = coverScale(info.size.width, info.size.height, targetWidth, targetHeight)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()),
                    )
                }
            }
            val crop = centerCropRect(decoded.width, decoded.height, targetWidth, targetHeight)
            val cropped = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
            val scaled = if (cropped.width > targetWidth) {
                Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
            } else {
                cropped
            }
            writeAtomically(file(context), scaled)
        } catch (error: IOException) {
            false
        } catch (error: SecurityException) {
            false
        } catch (error: IllegalArgumentException) {
            false
        } finally {
            cached = null
        }
    }

    /** 画像のファイルを消す。 */
    fun remove(context: Context) {
        cached = null
        file(context).delete()
    }

    /**
     * 保存した画像を読み、[blurDp]が0より大きければぼかした画像を返す。画像が無い・読めなければnull。
     * [stamp]とぼかしが前回と同じなら、前回読んだ画像を返す。
     */
    fun load(context: Context, stamp: Long, blurDp: Int): Bitmap? {
        if (stamp == 0L) return null
        cached?.let { (cachedStamp, cachedBlur, bitmap) -> if (cachedStamp == stamp && cachedBlur == blurDp) return bitmap }
        val original = BitmapFactory.decodeFile(file(context).path) ?: return null
        val result = if (blurDp > 0) blurred(original, blurDp * context.resources.displayMetrics.density) else original
        cached = Triple(stamp, blurDp, result)
        return result
    }

    /** 一時ファイルへ書いてから置き換え、書き込み途中で止まっても壊れた画像を残さない。 */
    private fun writeAtomically(target: File, bitmap: Bitmap): Boolean {
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return false
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return false
        }
        return true
    }

    /** 画像を縮めてからぼかす。描画時に拡大するため、[radiusPx]は元の画像のpxで受け取る。 */
    private fun blurred(original: Bitmap, radiusPx: Float): Bitmap {
        val width = max(1, original.width / BLUR_DOWNSCALE)
        val height = max(1, original.height / BLUR_DOWNSCALE)
        val small = Bitmap.createScaledBitmap(original, width, height, true)
        var pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val radius = max(1, (radiusPx / BLUR_DOWNSCALE).roundToInt())
        // 箱型のぼかしを3回重ね、ガウスぼかしに近い滑らかさにする
        repeat(3) { pixels = boxBlur(pixels, width, height, radius) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** 画像から切り抜く範囲（px）。 */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * 幅[sourceWidth]×高さ[sourceHeight]の画像から、[targetWidth]:[targetHeight]の縦横比で中央を切り抜く範囲を返す（center crop）。
 */
fun centerCropRect(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): CropRect {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        return CropRect(0, 0, max(0, sourceWidth), max(0, sourceHeight))
    }
    // 元の画像の方が横長なら左右を、縦長なら上下を削る。比較は掛け算で行い、割り算の誤差を避ける。
    return if (sourceWidth.toLong() * targetHeight > targetWidth.toLong() * sourceHeight) {
        val width = max(1, (sourceHeight.toLong() * targetWidth / targetHeight).toInt())
        CropRect((sourceWidth - width) / 2, 0, width, sourceHeight)
    } else {
        val height = max(1, (sourceWidth.toLong() * targetHeight / targetWidth).toInt())
        CropRect(0, (sourceHeight - height) / 2, sourceWidth, height)
    }
}

/** 画像が目標の大きさを覆う最小の倍率。1より大きければ拡大が必要（読み込み時は縮めない）。 */
fun coverScale(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Float {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1f
    return max(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
}

/**
 * ARGBの画素の並び（行優先、幅[width]×高さ[height]）を、半径[radius]の箱型で横と縦にぼかした新しい並びを返す。
 * 端の画素は外側へ同じ色が続くものとして扱う。
 */
fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
    if (radius <= 0 || width <= 0 || height <= 0) return pixels.copyOf()
    val horizontal = IntArray(pixels.size)
    for (y in 0 until height) blurLine(pixels, horizontal, y * width, 1, width, radius)
    val result = IntArray(pixels.size)
    for (x in 0 until width) blurLine(horizontal, result, x, width, height, radius)
    return result
}

/** 一本の行または列（開始位置[start]、間隔[step]、長さ[length]）を、各成分の移動平均でぼかす。 */
private fun blurLine(source: IntArray, target: IntArray, start: Int, step: Int, length: Int, radius: Int) {
    val window = radius * 2 + 1
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    fun pixelAt(index: Int): Int = source[start + index.coerceIn(0, length - 1) * step]
    for (i in -radius..radius) {
        val p = pixelAt(i)
        a += p ushr 24
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    for (i in 0 until length) {
        target[start + i * step] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
        val removed = pixelAt(i - radius)
        val added = pixelAt(i + radius + 1)
        a += (added ushr 24) - (removed ushr 24)
        r += ((added shr 16) and 0xFF) - ((removed shr 16) and 0xFF)
        g += ((added shr 8) and 0xFF) - ((removed shr 8) and 0xFF)
        b += (added and 0xFF) - (removed and 0xFF)
    }
}

/**
 * キーボードの背景画像を描く。地色で塗ってから、画像を描画範囲いっぱいに中央で切り抜いて描き、明るさを掛ける。
 * 入力View全体の背景に使い、候補バーとキーの下へ同じ画像を敷く。
 */
class KeyboardBackgroundDrawable(
    private val bitmap: Bitmap,
    brightnessPercent: Int,
    private val baseColor: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val scale = brightnessPercent / 100f
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(scale, scale, scale, 1f) })
    }
    private val sourceRect = Rect()

    override fun draw(canvas: Canvas) {
        canvas.drawColor(baseColor)
        val bounds = bounds
        if (bounds.isEmpty) return
        val crop = centerCropRect(bitmap.width, bitmap.height, bounds.width(), bounds.height())
        sourceRect.set(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height)
        canvas.drawBitmap(bitmap, sourceRect, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Drawable.getOpacityはAPI 29で非推奨だが、minSdk 30でも実装が必要")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
