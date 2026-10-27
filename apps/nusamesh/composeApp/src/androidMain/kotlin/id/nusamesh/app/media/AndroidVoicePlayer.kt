package id.nusamesh.app.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One selected incoming stream. Bounded PCM/packet buffering; stop also interrupts blocked writes. */
class AndroidVoicePlayer(private val onError: (String) -> Unit, private val onEvent: (String) -> Unit) {
    private var queue = ArrayBlockingQueue<IncomingVoiceChunk>(VoiceStream.MAX_CHUNKS)
    @Volatile private var enabled = false
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var active: String? = null
    @Synchronized fun enable(value: Boolean) { enabled = value; if(!value) stop() }
    @Synchronized fun offer(event: IncomingVoiceChunk) {
        if (!enabled) return
        if (active == null) {
            if (event.chunk.sequence != 0 || event.chunk.cancelled) return
            active = event.key
            queue = ArrayBlockingQueue(VoiceStream.MAX_CHUNKS)
            val inbox = queue
            val token = ++generation
            Thread({ play(token, inbox) }, "NusaMesh-codec2-stream-player").start()
        }
        if (active != event.key) return // Preserve current speaker; never mix predictor states.
        if (event.chunk.cancelled) { stop(); return }
        if (!queue.offer(event)) { stop(); onError("Buffer suara penuh") }
    }
    private fun play(token: Int, inbox: ArrayBlockingQueue<IncomingVoiceChunk>) {
        var owned: AudioTrack? = null
        try {
            AndroidCodec2.Session().use { decoder ->
                val min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min, 10240)).build()
                owned = output
                if(Build.VERSION.SDK_INT >= 31) output.setStartThresholdInFrames(320)
                synchronized(this) { if(token != generation) return; track = output }
                var written = 0; var started = false; var lastData = System.nanoTime()
                var buffering = false
                while (token == generation) {
                    val event = inbox.poll(500, TimeUnit.MILLISECONDS)
                    if(event == null) {
                        if(System.nanoTime()-lastData > VoiceStream.TIMEOUT_MS*1_000_000) error("Sesi suara kedaluwarsa")
                        if(started && !buffering && output.playbackHeadPosition >= written) { onEvent("buffering"); buffering = true }
                        continue
                    }
                    lastData = System.nanoTime(); val chunk = event.chunk
                    if(chunk.frames.isNotEmpty()) {
                        val began = System.nanoTime()
                        val pcm = decoder.decode(chunk.frames)
                        onEvent("decode stream=${event.key}; seq=${chunk.sequence}; decode_us=${(System.nanoTime()-began)/1000}")
                        val first = !started
                        if(first) { output.play(); started = true }
                        var at = 0
                        while(at < chunk.samples && token == generation) {
                            val n = output.write(pcm, at, chunk.samples-at, AudioTrack.WRITE_BLOCKING)
                            check(n > 0) { "Pemutar suara berhenti" }; at += n; written += n
                        }
                        if(first && token == generation) onEvent("submitted stream=${event.key}; arrival_to_write_ms=${System.currentTimeMillis()-event.receivedAtMs}")
                        if(buffering) { buffering = false; onEvent("resumed") }
                    }
                    if(chunk.end) {
                        // Pre-31 devices may wait for a full hardware buffer, even for a tiny final
                        // chunk. Silence primes/drains it; it is not part of the saved voice note.
                        val tail = ShortArray(output.bufferCapacityInFrames)
                        var at = 0
                        while(at < tail.size && token == generation) {
                            val n = output.write(tail,at,tail.size-at,AudioTrack.WRITE_BLOCKING)
                            check(n > 0) { "Pemutar suara berhenti" }; at += n
                        }
                        val deadline = System.nanoTime() + 3_000_000_000L
                        while(token == generation && output.playbackHeadPosition < written && System.nanoTime()<deadline) Thread.sleep(20)
                        if(token == generation) {
                            check(output.playbackHeadPosition >= written) { "Pemutar tidak menghabiskan buffer suara" }
                            onEvent("finished underruns=${output.underrunCount}")
                        }
                        break
                    }
                }
            }
        } catch(e: Exception) { if(token == generation) onError(e.message ?: "Pemutar stream gagal") }
        finally {
            synchronized(this) { if(token == generation) { track = null; active = null; queue.clear() } }
            owned?.let { runCatching { it.stop() }; runCatching { it.release() } }
        }
    }
    @Synchronized fun stop() {
        generation++; active = null; queue.clear()
        runCatching { track?.pause() }; runCatching { track?.flush() }; track = null
    }
}
