package id.nusamesh.app.media

import kotlin.math.roundToInt

/**
 * Konversi gambar ↔ tensor model super-resolution (NHWC, RGB 0..1, int8 terkuantisasi), dipisah dari
 * Android supaya bisa diuji: urutan kanal, padding tepi, kuantisasi & potongan hasil harus persis sama
 * dengan tools/sr-benchmark (yang hasilnya sudah divalidasi). Piksel = ARGB Int (seperti Bitmap Android).
 */
object SrTensors {
    /** Input uint8 [1,size,size,3]: gambar w×h ditaruh di kiri-atas, sisanya mengulang piksel tepi. */
    fun encodeUint8(pixels: IntArray, w: Int, h: Int, size: Int, scale: Float, zeroPoint: Int): ByteArray {
        require(w in 1..size && h in 1..size && pixels.size == w * h)
        val out = ByteArray(size * size * 3)
        var i = 0
        for (y in 0 until size) for (x in 0 until size) {
            val p = pixels[minOf(y, h - 1) * w + minOf(x, w - 1)]
            for (shift in intArrayOf(16, 8, 0)) {
                val v = ((p shr shift) and 0xFF) / 255f
                out[i++] = (v / scale + zeroPoint).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Output uint8 [1,outSize,outSize,3] → piksel ARGB, dipotong ke outW×outH (bagian dari gambar asli). */
    fun decodeUint8(tensor: ByteArray, outSize: Int, outW: Int, outH: Int, scale: Float, zeroPoint: Int): IntArray {
        require(tensor.size == outSize * outSize * 3 && outW <= outSize && outH <= outSize)
        val out = IntArray(outW * outH)
        for (y in 0 until outH) for (x in 0 until outW) {
            val base = (y * outSize + x) * 3
            fun ch(c: Int) = (((tensor[base + c].toInt() and 0xFF) - zeroPoint) * scale * 255f).roundToInt().coerceIn(0, 255)
            out[y * outW + x] = (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return out
    }

    /** Potongan size×size mulai (x0,y0) dari gambar w×h; koordinat di luar gambar mengulang piksel tepi. */
    fun crop(pixels: IntArray, w: Int, h: Int, x0: Int, y0: Int, size: Int): IntArray = IntArray(size * size) { i ->
        val x = (x0 + i % size).coerceIn(0, w - 1)
        val y = (y0 + i / size).coerceIn(0, h - 1)
        pixels[y * w + x]
    }
}

/**
 * Rencana tiling super-resolution untuk gambar lebih besar dari input model (gambar BLE ≤960 px):
 * potongan [tile] px dengan tumpang-tindih [overlap] di tiap sisi; hanya bagian tengah ("inti") tiap hasil
 * yang dipakai sehingga tidak ada garis sambungan, dan inti-inti menutup gambar tepat sekali.
 */
object SrTiling {
    /**
     * Tumpang-tindih per sisi. Diukur dengan Real-ESRGAN asli (8 foto BSD100/Urban100, beda warna di garis
     * sambungan vs batas piksel biasa): 0 px +93% (garis terlihat), 8 px +7,8%, 16 px +1,3% (tak terlihat).
     */
    const val OVERLAP = 16

    /** Satu potongan: ambil input mulai (srcX,srcY) ukuran tile; tempel inti [coreX,coreX+coreW) ke hasil ×scale. */
    data class Tile(val srcX: Int, val srcY: Int, val coreX: Int, val coreY: Int, val coreW: Int, val coreH: Int)

    fun plan(w: Int, h: Int, tile: Int = 128, overlap: Int = OVERLAP): List<Tile> {
        val step = tile - 2 * overlap
        val out = ArrayList<Tile>()
        var y = 0
        while (y < h) {
            val ch = minOf(step, h - y)
            var x = 0
            while (x < w) {
                val cw = minOf(step, w - x)
                out += Tile(srcX = x - overlap, srcY = y - overlap, coreX = x, coreY = y, coreW = cw, coreH = ch)
                x += step
            }
            y += step
        }
        return out
    }

    /**
     * Jalankan [upscaleTile] (input tile×tile → (tile×scale)²) untuk tiap potongan lalu tempel intinya.
     * Dipakai SuperResolution; dipisah supaya penyambungan bisa diuji tanpa model.
     */
    fun stitch(
        pixels: IntArray, w: Int, h: Int, scale: Int = 4, tile: Int = 128, overlap: Int = OVERLAP,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        upscaleTile: (IntArray) -> IntArray,
    ): IntArray {
        val ow = w * scale
        val out = IntArray(ow * h * scale)
        val tileOut = tile * scale
        val plan = plan(w, h, tile, overlap)
        plan.forEachIndexed { i, t ->
            val up = upscaleTile(SrTensors.crop(pixels, w, h, t.srcX, t.srcY, tile))
            val ox = (t.coreX - t.srcX) * scale
            val oy = (t.coreY - t.srcY) * scale
            for (row in 0 until t.coreH * scale) {
                up.copyInto(out, (t.coreY * scale + row) * ow + t.coreX * scale, (oy + row) * tileOut + ox, (oy + row) * tileOut + ox + t.coreW * scale)
            }
            onProgress(i + 1, plan.size)
        }
        return out
    }

    /** Jumlah potongan untuk gambar w×h. */
    fun count(w: Int, h: Int, tile: Int = 128, overlap: Int = OVERLAP): Int {
        val step = tile - 2 * overlap
        return ((w + step - 1) / step) * ((h + step - 1) / step)
    }

    /**
     * Skala input (≤1) supaya jumlah potongan ≤ [maxTiles] dan hasil ×[scale] tidak melebihi [maxOutput] px.
     * Gambar kecil (≤ tile) tidak diperkecil.
     */
    fun inputScale(w: Int, h: Int, maxTiles: Int, maxOutput: Int, scale: Int = 4, tile: Int = 128, overlap: Int = OVERLAP): Double {
        var s = minOf(1.0, maxOutput.toDouble() / (maxOf(w, h) * scale))
        while (s > 0.05 && count(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()), tile, overlap) > maxTiles) s *= 0.95
        return s
    }
}
