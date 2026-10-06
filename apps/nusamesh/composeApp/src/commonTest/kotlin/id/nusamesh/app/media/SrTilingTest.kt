package id.nusamesh.app.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SrTilingTest {
    /** "Model" palsu: perbesar 4× tiap piksel (nearest). Penyambungan benar ⇒ identik dengan memperbesar sekaligus. */
    private fun nearest4(pixels: IntArray, w: Int, h: Int) = IntArray(w * 4 * h * 4) { i -> pixels[(i / (w * 4)) / 4 * w + (i % (w * 4)) / 4] }

    @Test
    fun coresCoverEveryPixelExactlyOnce() {
        for ((w, h) in listOf(129 to 97, 960 to 720, 113 to 113, 112 to 300, 1 to 500)) {
            val hits = IntArray(w * h)
            SrTiling.plan(w, h).forEach { t ->
                assertEquals(t.coreX - SrTiling.OVERLAP, t.srcX); assertEquals(t.coreY - SrTiling.OVERLAP, t.srcY)
                for (y in t.coreY until t.coreY + t.coreH) for (x in t.coreX until t.coreX + t.coreW) hits[y * w + x]++
            }
            assertTrue(hits.all { it == 1 }, "${w}x$h: ada celah/tumpang-tindih")
            assertEquals(SrTiling.count(w, h), SrTiling.plan(w, h).size)
        }
    }

    @Test
    fun stitchedResultIsSeamless() {
        val w = 300; val h = 211
        val pixels = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        var lastProgress = 0 to 0
        val stitched = SrTiling.stitch(pixels, w, h, onProgress = { d, t -> lastProgress = d to t }) { tile -> nearest4(tile, 128, 128) }
        assertContentEquals(nearest4(pixels, w, h), stitched)
        assertEquals(SrTiling.count(w, h) to SrTiling.count(w, h), lastProgress)
    }

    @Test
    fun budgetLimitsTilesAndOutputSize() {
        val s = SrTiling.inputScale(960, 720, maxTiles = 16, maxOutput = 2048)
        val w = (960 * s).toInt(); val h = (720 * s).toInt()
        assertTrue(SrTiling.count(w, h) <= 16, "potongan ${SrTiling.count(w, h)}")
        assertTrue(maxOf(w, h) * 4 <= 2048)
        assertEquals(1.0, SrTiling.inputScale(400, 300, maxTiles = 64, maxOutput = 2048), "gambar kecil tidak diperkecil")
        val crop = SrTensors.crop(IntArray(4) { it }, 2, 2, -3, -3, 6)
        assertEquals(0, crop[0]); assertEquals(3, crop[35])   // luar gambar = piksel tepi terdekat
    }
}
