package id.nusamesh.app.media

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * AI super-resolution ×4 untuk gambar LoRa (≤128 px): model TFLite int8 dari Qualcomm AI Hub (BSD-3),
 * input 128×128 RGB → output 512×512. Dipilih dari benchmark tools/sr-benchmark (gambar LoRa nyata:
 * 128 px + WebP ≤1,2 KB): Real-ESRGAN x4v3 terbaik secara persepsi; SESR-M5 & XLSR cadangan HP lambat.
 *
 * Tidak thread-safe: panggil dari satu thread latar (lihat MainActivity).
 */
class SuperResolution(private val context: Context) {
    enum class Model(val asset: String, val label: String) {
        RealEsrgan("sr/real_esrgan_x4v3_w8a8.tflite", "Real-ESRGAN x4v3"),
        Sesr("sr/sesr_m5_w8a8.tflite", "SESR-M5"),
        Xlsr("sr/xlsr_w8a8.tflite", "XLSR"),
    }

    private val interpreters = HashMap<Model, Interpreter>()
    private val prefs = context.getSharedPreferences("nusamesh_sr", Context.MODE_PRIVATE)

    private fun interpreter(model: Model): Interpreter = interpreters.getOrPut(model) {
        val buffer: MappedByteBuffer = context.assets.openFd(model.asset).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
        Interpreter(buffer, Interpreter.Options().setNumThreads(THREADS))
    }

    /** Gambar ≤128 px (profil LoRa): satu kali jalan, di-pad tepi lalu dipotong kembali ×4. */
    fun upscale(source: Bitmap, model: Model): Bitmap {
        val src = if (maxOf(source.width, source.height) > SIZE) {
            val s = SIZE.toFloat() / maxOf(source.width, source.height)
            Bitmap.createScaledBitmap(source, maxOf(1, (source.width * s).roundToInt()), maxOf(1, (source.height * s).roundToInt()), true)
        } else source
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        return Bitmap.createBitmap(runTile(pixels, w, h, model), w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)
    }

    /**
     * Gambar kecil saja (≤ [MAX_INPUT] px, mis. profil LoRa): ≤128 px sekali jalan, 129–256 px dipotong 128 px
     * bertumpang-tindih (SrTiling) pada resolusi ASLI. Gambar TIDAK PERNAH diperkecil dulu: uji di HP
     * menunjukkan gambar BLE (±960 px, mis. screenshot berteks) yang diperkecil lalu diperbesar AI justru
     * lebih buruk — teks yang hilang saat diperkecil "dikarang ulang" model.
     */
    fun enhance(source: Bitmap, model: Model, onProgress: (Int, Int) -> Unit): Bitmap {
        require(maxOf(source.width, source.height) <= MAX_INPUT) { "Gambar sudah cukup detail; AI hanya untuk gambar kecil (profil LoRa)" }
        if (maxOf(source.width, source.height) <= SIZE) return upscale(source, model).also { onProgress(1, 1) }
        val src = source
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val ow = w * SCALE
        val out = SrTiling.stitch(pixels, w, h, SCALE, SIZE, OVERLAP, onProgress) { tile -> runTile(tile, SIZE, SIZE, model) }
        return Bitmap.createBitmap(out, ow, h * SCALE, Bitmap.Config.ARGB_8888)
    }

    /** Satu inferensi: piksel w×h (≤128) → piksel (w×4)×(h×4). */
    private fun runTile(pixels: IntArray, w: Int, h: Int, model: Model): IntArray {
        val it = interpreter(model)
        val input = it.getInputTensor(0)
        val output = it.getOutputTensor(0)
        check(input.dataType() == DataType.UINT8 && output.dataType() == DataType.UINT8) { "Model ${model.label} bukan int8" }
        val inQ = input.quantizationParams()
        val outQ = output.quantizationParams()
        val inBuf = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder())
        inBuf.put(SrTensors.encodeUint8(pixels, w, h, SIZE, inQ.scale, inQ.zeroPoint)).rewind()
        val outBuf = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())
        it.run(inBuf, outBuf)
        val raw = ByteArray(output.numBytes()).also { outBuf.rewind(); outBuf.get(it) }
        // Output int8 Real-ESRGAN memakai skala/zero-point sendiri (bukan 0..255 langsung).
        return SrTensors.decodeUint8(raw, SIZE * SCALE, w * SCALE, h * SCALE, outQ.scale, outQ.zeroPoint)
    }

    /** Waktu median (3 kali, setelah 1 pemanasan) tiap model pada gambar uji 128×128. */
    fun benchmark(): List<Pair<Model, Result<Long>>> {
        val probe = Bitmap.createBitmap(IntArray(SIZE * SIZE) { i -> 0xFF000000.toInt() or ((i * 2654435761L).toInt() and 0xFFFFFF) }, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return Model.entries.map { model ->
            model to runCatching {
                upscale(probe, model)
                List(3) { val t = System.nanoTime(); upscale(probe, model); (System.nanoTime() - t) / 1_000_000 }.sorted()[1]
            }
        }
    }

    /** Model terbaik yang masih ≤ [MAX_MS] di HP ini + waktunya per potongan; diukur sekali lalu diingat. */
    fun chosenModel(): Pair<Model, Long> {
        val saved = prefs.getString(KEY_MODEL, null)?.let { name -> Model.entries.firstOrNull { it.name == name } }
        if (saved != null && prefs.contains(KEY_MS)) return saved to prefs.getLong(KEY_MS, MAX_MS)
        val results = benchmark()
        val pick = choose(results)
        return pick to (results.firstOrNull { it.first == pick }?.second?.getOrNull() ?: MAX_MS)
    }

    fun choose(results: List<Pair<Model, Result<Long>>>): Model {
        val pick = results.firstOrNull { (_, r) -> (r.getOrNull() ?: Long.MAX_VALUE) <= MAX_MS }?.first
            ?: results.filter { it.second.isSuccess }.minByOrNull { it.second.getOrThrow() }?.first
            ?: Model.Xlsr
        val ms = results.firstOrNull { it.first == pick }?.second?.getOrNull() ?: MAX_MS
        prefs.edit().putString(KEY_MODEL, pick.name).putLong(KEY_MS, ms).apply()
        return pick
    }

    fun assetSizeKb(model: Model) = runCatching { context.assets.openFd(model.asset).use { (it.length / 1024).toInt() } }.getOrDefault(0)

    companion object {
        const val SIZE = 128
        const val SCALE = 4
        /** Lebih lambat dari ini terasa macet: turun ke model yang lebih ringan. */
        const val MAX_MS = 1_500L
        /** Gambar lebih besar sudah cukup detail; AI hanya mengarang & lambat (lihat [enhance]). */
        const val MAX_INPUT = 256
        private const val OVERLAP = SrTiling.OVERLAP
        private const val THREADS = 4
        private const val KEY_MODEL = "model"
        private const val KEY_MS = "ms_per_tile"
    }
}
