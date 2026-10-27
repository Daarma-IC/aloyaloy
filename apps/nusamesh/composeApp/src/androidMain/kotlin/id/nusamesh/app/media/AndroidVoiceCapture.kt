package id.nusamesh.app.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessageKind
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Capture + persistent encoding on one worker. Never allocate or re-create Codec2 per frame. */
class AndroidVoiceCapture(private val post: (() -> Unit) -> Unit, private val metric: (String) -> Unit = {}) {
    @Volatile private var stopping = false
    @Volatile private var cancelling = false
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var complete: ((ChatAttachment) -> Unit)? = null
    private var failure: ((String) -> Unit)? = null
    private var result: ChatAttachment? = null
    private var error: String? = null
    @Volatile private var done = false
    var encodeMicros = 0L; private set

    fun start(onChunk: (VoiceChunk) -> Unit, onError: (String) -> Unit) {
        check(worker == null)
        failure = onError
        val min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "Perangkat tidak mendukung rekaman 8 kHz" }
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, 4096)).build()
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("Perekam gagal diinisialisasi") }
        recorder = record
        worker = Thread({
            val id = SecureRandom().nextLong()
            val captureBegan = System.nanoTime()
            var seq = 0; var sentSamples = 0
            val archive = ByteArrayOutputStream()
            try {
                AndroidCodec2.Session().use { codec ->
                    val pending = ShortArray(VoiceStream.NEXT_FRAMES * 320)
                    var used = 0; var target = VoiceStream.FIRST_FRAMES * 320
                    fun emit(count: Int, end: Boolean) {
                        val padded = ShortArray(((count + 319) / 320) * 320)
                        pending.copyInto(padded, 0, 0, count)
                        val began = System.nanoTime()
                        val frames = if(count == 0) ByteArray(0) else codec.encode(padded)
                        val elapsed = (System.nanoTime()-began)/1000
                        encodeMicros += elapsed
                        metric("stream=$id; seq=$seq; samples=$count; codec_bytes=${frames.size}; encode_us=$elapsed; capture_ms=${(System.nanoTime()-captureBegan)/1_000_000}")
                        archive.write(frames)
                        val chunk = VoiceChunk(id, seq++, sentSamples, count, frames, end)
                        sentSamples += count
                        post { onChunk(chunk) }
                        used = 0; target = pending.size
                    }
                    record.startRecording()
                    val buffer = ShortArray(1024)
                    while (!stopping && sentSamples + used < VoiceStream.MAX_SAMPLES) {
                        val got = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (got < 0) { if(stopping) break else error("Perekam berhenti: $got") }
                        if (got == 0) continue
                        var at = 0
                        while (at < got && sentSamples + used < VoiceStream.MAX_SAMPLES) {
                            val take = minOf(got-at, target-used, VoiceStream.MAX_SAMPLES-sentSamples-used)
                            buffer.copyInto(pending, used, at, at+take); used += take; at += take
                            if (used == target) emit(used, false)
                        }
                    }
                    if(cancelling) {
                        post { onChunk(VoiceChunk(id,seq,sentSamples,0,ByteArray(0),end=true,cancelled=true)) }
                    } else {
                        check(sentSamples + used >= 320) { "Rekaman terlalu pendek" }
                        emit(used, true)
                        result = ChatAttachment("voice-$id.c2", AndroidCodec2.MIME,
                            VoiceStream.note(sentSamples, archive.toByteArray()), ChatMessageKind.Voice,
                            (sentSamples + 7999)/8000)
                    }
                }
            } catch (t: Exception) {
                error = t.message ?: "Rekam Codec2 gagal"
                post { onChunk(VoiceChunk(id, seq.coerceAtMost(63), sentSamples, 0, ByteArray(0), end=true, cancelled=true)) }
            } finally {
                runCatching { record.stop() }; record.release(); recorder = null; done = true
                post { deliver() }
            }
        }, "NusaMesh-codec2-capture").also { it.start() }
    }
    private fun deliver() {
        if (!done) return
        val callback = complete
        if(callback != null) {
            complete = null
            result?.let(callback) ?: failure?.invoke(error ?: "Rekaman dibatalkan")
        } else if(error != null) { val callbackError = failure; failure = null; callbackError?.invoke(error!!) }
    }
    fun stop(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
        complete = onRecorded; failure = onError; stopping = true
        runCatching { recorder?.stop() }
        deliver()
    }
    fun cancel() { cancelling = true; stopping = true; complete = null; failure = null; runCatching { recorder?.stop() } }
}
