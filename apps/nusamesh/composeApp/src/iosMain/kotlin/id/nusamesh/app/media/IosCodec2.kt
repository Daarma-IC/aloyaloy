@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.nusamesh.app.media

import id.nusamesh.codec2bridge.nusa_codec2_decode
import id.nusamesh.codec2bridge.nusa_codec2_decoded_samples
import id.nusamesh.codec2bridge.nusa_codec2_encode
import id.nusamesh.codec2bridge.nusa_codec2_encoded_size
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

object IosCodec2 {
    const val MIME = "audio/x-codec2"
    const val SAMPLE_RATE = 8_000

    fun encode(pcm: ShortArray): ByteArray {
        val size = nusa_codec2_encoded_size(pcm.size.toUInt()).toInt()
        require(size > 0)
        return ByteArray(size).also { output ->
            val written = pcm.usePinned { source ->
                output.usePinned { target ->
                    nusa_codec2_encode(source.addressOf(0), pcm.size.toUInt(), target.addressOf(0).reinterpret(), size.toUInt())
                }
            }
            require(written.toInt() == size) { "Codec2 encode gagal" }
        }
    }

    fun decode(encoded: ByteArray): ShortArray {
        require(encoded.isNotEmpty())
        val samples = encoded.usePinned { nusa_codec2_decoded_samples(it.addressOf(0).reinterpret(), encoded.size.toUInt()) }.toInt()
        require(samples > 0) { "Data Codec2 tidak valid" }
        return ShortArray(samples).also { pcm ->
            val written = encoded.usePinned { source ->
                pcm.usePinned { target ->
                    nusa_codec2_decode(source.addressOf(0).reinterpret(), encoded.size.toUInt(), target.addressOf(0), samples.toUInt())
                }
            }
            require(written.toInt() == samples) { "Codec2 decode gagal" }
        }
    }

    fun pcmFromWav(wav: ByteArray): ShortArray {
        require(wav.size >= 44 && wav.ascii(0, 4) == "RIFF" && wav.ascii(8, 4) == "WAVE")
        var offset = 12
        while (offset + 8 <= wav.size) {
            val id = wav.ascii(offset, 4)
            val size = wav.le32(offset + 4)
            val start = offset + 8
            if (id == "data") {
                val count = minOf(size, wav.size - start) / 2
                return ShortArray(count) { i -> ((wav[start + i*2].toInt() and 0xff) or (wav[start + i*2 + 1].toInt() shl 8)).toShort() }
            }
            offset = start + size + (size and 1)
        }
        error("Chunk PCM WAV tidak ditemukan")
    }

    fun wavFromPcm(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        return ByteArray(44 + dataSize).also { out ->
            out.putAscii(0, "RIFF"); out.putLe32(4, 36 + dataSize); out.putAscii(8, "WAVEfmt ")
            out.putLe32(16, 16); out.putLe16(20, 1); out.putLe16(22, 1); out.putLe32(24, SAMPLE_RATE)
            out.putLe32(28, SAMPLE_RATE * 2); out.putLe16(32, 2); out.putLe16(34, 16); out.putAscii(36, "data"); out.putLe32(40, dataSize)
            pcm.forEachIndexed { i, value -> out.putLe16(44 + i*2, value.toInt()) }
        }
    }

    private fun ByteArray.ascii(offset: Int, count: Int) = copyOfRange(offset, offset + count).decodeToString()
    private fun ByteArray.le32(offset: Int) = (this[offset].toInt() and 0xff) or ((this[offset+1].toInt() and 0xff) shl 8) or ((this[offset+2].toInt() and 0xff) shl 16) or ((this[offset+3].toInt() and 0xff) shl 24)
    private fun ByteArray.putAscii(offset: Int, value: String) = value.encodeToByteArray().copyInto(this, offset)
    private fun ByteArray.putLe16(offset: Int, value: Int) { this[offset]=value.toByte(); this[offset+1]=(value ushr 8).toByte() }
    private fun ByteArray.putLe32(offset: Int, value: Int) { putLe16(offset,value); putLe16(offset+2,value ushr 16) }
}
