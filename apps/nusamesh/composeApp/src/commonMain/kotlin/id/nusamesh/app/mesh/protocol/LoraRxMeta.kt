package id.nusamesh.app.mesh.protocol

/**
 * Data ukur dari Nusa Node untuk satu paket yang tiba lewat LoRa (paket RX_META 0x19, dikirim node
 * tepat sebelum paketnya). Dasar uji QoS: daya terima vs jarak, PSP vs jarak per SF.
 */
data class LoraRxMeta(
    /** FNV-1a 64-bit paket app (sama dengan nusaMsgId() firmware). */
    val msgId: Long,
    val rssiDbm: Float,
    val snrDb: Float,
    val spreadingFactor: Int,
    val bandwidthKHz: Float,
    /** Sisa hop LoRa saat diterima; null bila tak diketahui (paket terarah mobility). */
    val hopLeft: Int?,
    val txPowerDbm: Int,
    /** true bila msgId cocok dengan paket yang dipasangkan; false = dipasangkan berdasarkan urutan saja. */
    val matched: Boolean = false,
) {
    companion object {
        /** Payload v1, 18 byte — lihat nusaBuildRxMeta() di firmware/NusaNode/nusa_appproto.h. */
        fun decode(payload: ByteArray): LoraRxMeta? {
            if (payload.size < 18 || payload[0].toInt() != 1) return null
            fun u8(i: Int) = payload[i].toInt() and 0xFF
            fun s16(i: Int) = ((u8(i) shl 8) or u8(i + 1)).toShort().toInt()
            var id = 0L
            for (i in 1..8) id = (id shl 8) or u8(i).toLong()
            val sf = u8(13)
            if (sf !in 7..12) return null
            return LoraRxMeta(
                msgId = id,
                rssiDbm = s16(9) / 10f,
                snrDb = s16(11) / 10f,
                spreadingFactor = sf,
                bandwidthKHz = ((u8(14) shl 8) or u8(15)) / 10f,
                hopLeft = u8(16).takeIf { it != 0xFF },
                txPowerDbm = payload[17].toInt(),
            )
        }

        /** nusaMsgId() firmware: FNV-1a 64-bit atas paket app, byte TTL (indeks 2) dianggap nol. */
        fun msgIdOf(packet: ByteArray): Long {
            var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (i in packet.indices) {
                val b = if (i == 2) 0 else packet[i].toInt() and 0xFF
                hash = (hash xor b.toLong()) * 0x100000001b3L
            }
            return hash
        }
    }
}
