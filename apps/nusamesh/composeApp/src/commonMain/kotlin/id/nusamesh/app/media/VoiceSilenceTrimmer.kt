package id.nusamesh.app.media

import kotlin.math.sqrt

/**
 * Buang hening dari rekaman PCM 16-bit mono sebelum dikompresi Codec2, supaya byte yang lewat LoRa
 * hanya berisi ucapan. Hening di awal/akhir dibuang habis; jeda di tengah dipendekkan ke [MAX_GAP_MS]
 * agar ucapan tetap terdengar alami.
 */
object VoiceSilenceTrimmer {
    const val FRAME_MS = 20
    /** Ambang mutlak (~-42 dBFS): di bawah ini selalu dianggap hening. */
    const val MIN_SPEECH_RMS = 250.0
    /** Ucapan harus sekian kali di atas lantai derau (~+8 dB). */
    const val NOISE_FACTOR = 2.5
    /** Ambang tak boleh melebihi -20 dB dari frame terkeras, agar rekaman yang penuh ucapan tidak terbuang. */
    const val PEAK_FACTOR = 0.1
    const val PRE_ROLL_MS = 60
    const val HANGOVER_MS = 200
    const val MAX_GAP_MS = 160

    fun trim(pcm: ShortArray, sampleRate: Int): ShortArray {
        val frameSize = sampleRate * FRAME_MS / 1000
        val frameCount = (pcm.size + frameSize - 1) / frameSize
        if (frameCount == 0) return ShortArray(0)
        val rms = DoubleArray(frameCount) { frame ->
            val start = frame * frameSize
            val end = minOf(start + frameSize, pcm.size)
            var sum = 0.0
            for (i in start until end) sum += pcm[i].toDouble() * pcm[i]
            sqrt(sum / (end - start))
        }
        val sorted = rms.sorted()
        val noiseFloor = sorted[frameCount / 5]
        val threshold = maxOf(MIN_SPEECH_RMS, minOf(noiseFloor * NOISE_FACTOR, sorted.last() * PEAK_FACTOR))
        val speech = BooleanArray(frameCount) { rms[it] >= threshold }
        if (speech.none { it }) return ShortArray(0)

        val preRoll = PRE_ROLL_MS / FRAME_MS
        val hangover = HANGOVER_MS / FRAME_MS
        val keep = BooleanArray(frameCount)
        for (frame in 0 until frameCount) if (speech[frame]) {
            for (k in maxOf(0, frame - preRoll)..minOf(frameCount - 1, frame + hangover)) keep[k] = true
        }

        // Jeda panjang di antara ucapan dipendekkan; ujung awal/akhir yang hening tidak disertakan.
        val maxGap = MAX_GAP_MS / FRAME_MS
        val first = keep.indexOfFirst { it }
        val last = keep.indexOfLast { it }
        var gap = 0
        for (frame in first..last) {
            if (keep[frame]) gap = 0 else if (++gap <= maxGap) keep[frame] = true
        }

        val out = ShortArray(keep.count { it } * frameSize)
        var written = 0
        for (frame in 0 until frameCount) if (keep[frame]) {
            val start = frame * frameSize
            val end = minOf(start + frameSize, pcm.size)
            pcm.copyInto(out, written, start, end)
            written += end - start
        }
        return out.copyOf(written)
    }
}
