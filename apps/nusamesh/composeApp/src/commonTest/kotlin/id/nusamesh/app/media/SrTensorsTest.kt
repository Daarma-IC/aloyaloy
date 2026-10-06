package id.nusamesh.app.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden test: checksum dari rumus Python yang sama dengan tools/sr-benchmark (numpy: pad tepi, kuantisasi,
 * dekuantisasi, potong). Bila Kotlin beda satu piksel/kanal pun, checksum berubah.
 */
class SrTensorsTest {
    private val w = 37
    private val h = 29

    private fun pixels() = IntArray(w * h) { i ->
        val r = (i * 7 + 3) % 256; val g = (i * 13 + 5) % 256; val b = (i * 29 + 11) % 256
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun inputMatchesPythonPipeline() {
        val enc = SrTensors.encodeUint8(pixels(), w, h, 128, 0.003921568859368563f, 0)
        var sum = 0L
        enc.forEachIndexed { i, v -> sum += (v.toLong() and 0xFF) * (i % 9973 + 1) }
        assertEquals(27286249779L, sum)
    }

    @Test
    fun outputDequantizationMatchesPythonPipeline() {
        val tensor = ByteArray(512 * 512 * 3) { i -> ((i.toLong() * 31 + 7) % 256).toByte() }
        // Skala & zero-point output Real-ESRGAN x4v3 int8 (bukan 0..255 langsung).
        val out = SrTensors.decodeUint8(tensor, 512, w * 4, h * 4, 0.004972266033291817f, 25)
        var sum = 0L
        out.forEachIndexed { i, v -> sum += v.toLong() * (i % 9973 + 1) }
        assertEquals(-633532126783536L, sum)
    }

    @Test
    fun knownValuesFromModelQuantization() {
        // Dari interpreter Python: nilai mentah 25 = hitam, 100 → 95, ≥226 → 255.
        fun px(q: Int) = SrTensors.decodeUint8(ByteArray(3) { q.toByte() }, 1, 1, 1, 0.004972266033291817f, 25)[0] and 0xFF
        assertEquals(listOf(0, 0, 1, 95, 255, 255), listOf(0, 25, 26, 100, 226, 255).map(::px))
    }
}
