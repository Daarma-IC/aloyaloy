package id.nusamesh.app.media

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceSilenceTrimmerTest {
    private val rate = 8_000
    private val random = Random(7)

    private fun noise(ms: Int, amplitude: Int = 40) = ShortArray(rate * ms / 1000) { random.nextInt(-amplitude, amplitude + 1).toShort() }
    private fun tone(ms: Int) = ShortArray(rate * ms / 1000) { (6_000 * sin(2 * PI * 200 * it / rate)).toInt().toShort() }
    private fun ms(samples: Int) = samples * 1000 / rate

    @Test
    fun dropsLeadingTrailingSilenceAndShortensLongPauses() {
        val pcm = noise(1_500) + tone(1_000) + noise(2_000) + tone(1_000) + noise(1_500)
        val trimmed = VoiceSilenceTrimmer.trim(pcm, rate)
        // 2 dtk ucapan + pre-roll + hangover + jeda yang dipendekkan; 7 dtk aslinya.
        assertTrue(ms(trimmed.size) in 2_000..2_700, "durasi ${ms(trimmed.size)} ms")
        val loud = trimmed.count { it > 3_000 }
        assertEquals(pcm.count { it > 3_000 }, loud)
    }

    @Test
    fun keepsShortNaturalPauses() {
        val pcm = tone(500) + noise(100) + tone(500)
        assertEquals(pcm.size, VoiceSilenceTrimmer.trim(pcm, rate).size)
    }

    @Test
    fun continuousSpeechIsKeptWhole() {
        val pcm = tone(3_000)
        assertEquals(pcm.size, VoiceSilenceTrimmer.trim(pcm, rate).size)
    }

    @Test
    fun silenceOnlyBecomesEmpty() {
        assertEquals(0, VoiceSilenceTrimmer.trim(noise(3_000, amplitude = 200), rate).size)
        assertEquals(0, VoiceSilenceTrimmer.trim(ShortArray(0), rate).size)
    }
}
