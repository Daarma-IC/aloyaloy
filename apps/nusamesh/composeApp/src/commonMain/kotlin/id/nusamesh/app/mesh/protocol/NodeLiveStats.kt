package id.nusamesh.app.mesh.protocol

/**
 * Statistik radio live dari Nusa Node: blok tambahan di payload LORA_HEALTH (0x18), byte 8..37.
 * Format sama dengan nusaBuildLoraHealth() di firmware/NusaNode/src/protocol/nusa_appproto.h.
 * Firmware lama tidak mengirim blok ini; [decode] lalu mengembalikan null.
 */
data class NodeLiveStats(
    /** Radio bisa kirim dan terima. false pada mode dua radio berarti companion belum siap. */
    val radioReady: Boolean,
    /** Node memakai mode dua radio (board companion khusus terima). */
    val dualRadio: Boolean,
    val txFrames: Long,
    val rxFrames: Long,
    /** Frame dibuang karena antrean TX penuh. */
    val droppedFrames: Long,
    /** Frame tiba tetapi CRC rusak. */
    val crcErrors: Long,
    val queueDepth: Int,
    /** Sisa anggaran airtime duty cycle (ms). */
    val budgetMs: Int,
    val lastRssiDbm: Float,
    val lastSnrDb: Float,
    /** Detik sejak frame LoRa terakhir diterima; null = belum pernah. */
    val lastRxAgeSeconds: Int?,
    /** Detik sejak frame LoRa terakhir dikirim; null = belum pernah. */
    val lastTxAgeSeconds: Int?,
) {
    companion object {
        const val OFFSET = 8
        const val SIZE = 30
        private const val VERSION = 1
        private const val NEVER = 0xFFFF

        fun decode(payload: ByteArray): NodeLiveStats? {
            if (payload.size < OFFSET + SIZE) return null
            fun u8(i: Int) = payload[OFFSET + i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun s16(i: Int) = u16(i).toShort().toInt()
            fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
            if (u8(0) != VERSION) return null
            val flags = u8(1)
            return NodeLiveStats(
                radioReady = flags and 0x01 != 0,
                dualRadio = flags and 0x02 != 0,
                txFrames = u32(2),
                rxFrames = u32(6),
                droppedFrames = u32(10),
                crcErrors = u32(14),
                queueDepth = u16(18),
                budgetMs = u16(20),
                lastRssiDbm = s16(22) / 10f,
                lastSnrDb = s16(24) / 10f,
                lastRxAgeSeconds = u16(26).takeIf { it != NEVER },
                lastTxAgeSeconds = u16(28).takeIf { it != NEVER },
            )
        }
    }
}
