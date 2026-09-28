package id.nusamesh.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Profil gambar jalur LoRa (lewat Nusa Node), port dari Nusa Mesh Android: sisi terpanjang ≤ 128 px, WebP
 * ≤ 1200 B, agar satu gambar muat antrean LoRa. Penerima dapat memperjelasnya dengan FSRCNN ×3.
 *
 * Langkah degradasi HARUS sama dengan ml/fsrcnn/fsrcnn_kaggle.py (model dilatih pada artefak ini).
 */
object LoraImageCodec {
    const val MAX_SIDE = 128
    const val MIN_SIDE = 64
    const val MAX_BYTES = 1200
    private const val QUALITY_MIN = 5
    private const val QUALITY_MAX = 90
    private const val SIDE_SHRINK = 0.85

    /** Penanda nama file: penerima menjalankan FSRCNN hanya untuk gambar berawalan ini. */
    const val FILE_PREFIX = "lora_"
    const val MIME = "image/webp"

    fun encode(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = exifRotation(source)
        var side = MAX_SIDE
        while (true) {
            val small = downscale(source, bounds.outWidth, bounds.outHeight, rotation, side) ?: return null
            var lo = QUALITY_MIN
            var hi = QUALITY_MAX
            var best: ByteArray? = null
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val data = webp(small, mid)
                if (data.size <= MAX_BYTES) { best = data; lo = mid + 1 } else hi = mid - 1
            }
            if (best != null || side <= MIN_SIDE) return best ?: webp(small, QUALITY_MIN)
            side = maxOf(MIN_SIDE, (side * SIDE_SHRINK).toInt())
        }
    }

    /** inSampleSize pangkat 2, lalu createScaledBitmap(filter=true) — sama dengan android_like_downscale(). */
    private fun downscale(source: ByteArray, srcW: Int, srcH: Int, rotation: Int, targetLong: Int): Bitmap? {
        val longSide = maxOf(srcW, srcH)
        var sample = 1
        while (longSide / (sample * 2) >= targetLong * 2) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        if (rotation != 0) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        val s = targetLong.toDouble() / maxOf(bmp.width, bmp.height)
        val scaled = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s + 0.5).toInt()), maxOf(1, (bmp.height * s + 0.5).toInt()), true)
        return if (scaled.hasAlpha()) flattenOnWhite(scaled) else scaled
    }

    // Alpha di WebP makan byte percuma dan model dilatih pada RGB.
    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        out.setHasAlpha(false)
        return out
    }

    private fun webp(bmp: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        return ByteArrayOutputStream().use { out -> bmp.compress(format, quality, out); out.toByteArray() }
    }

    private fun exifRotation(source: ByteArray): Int = runCatching {
        when (ExifInterface(source.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
