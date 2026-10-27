package id.nusamesh.app.media

import kotlin.test.*

class Codec2FormatTest {
    private fun note(samples: Int, version: Int = 2): ByteArray {
        val spf = if (version == 1) 160 else 320
        val bpf = if (version == 1) 8 else 6
        val frames = (samples + spf - 1) / spf
        return ByteArray(12 + frames * bpf).also { out ->
            "NC2".encodeToByteArray().copyInto(out)
            out[3] = version.toByte()
            for (i in 0..3) { out[4 + i] = (samples ushr (24 - i * 8)).toByte(); out[8 + i] = (frames ushr (24 - i * 8)).toByte() }
        }
    }

    @Test fun roundsPartialFramesAndPreservesOld3200Notes() {
        for (count in listOf(1, 319, 320, 321, 8000, 80000)) {
            assertEquals(count, Codec2Format.samples(note(count)))
            assertEquals(note(count).size, Codec2Format.encodedSize(count))
            assertEquals(count, Codec2Format.samples(note(count, 1)))
        }
        assertEquals(1512, Codec2Format.encodedSize(80000))
    }

    @Test fun rejectsTruncationTrailingFramesAndHostileSampleCounts() {
        val valid = note(8000)
        assertNull(Codec2Format.samples(valid.copyOf(valid.size - 1)))
        assertNull(Codec2Format.samples(valid + ByteArray(6)))
        assertNull(Codec2Format.samples(valid.copyOf().also { for (i in 4..7) it[i] = -1 }))
        assertNull(Codec2Format.samples(valid.copyOf().also { it[3] = 99 }))
        assertNull(Codec2Format.samples(note(0)))
    }
}
