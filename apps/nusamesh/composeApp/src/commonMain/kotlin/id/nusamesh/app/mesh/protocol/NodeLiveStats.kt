package id.nusamesh.app.mesh.protocol

/**
 * Statistik radio live dari Nusa Node: blok tambahan di payload LORA_HEALTH (0x18), mulai byte 8.
 * Format sama dengan nusaBuildLoraHealth() di firmware/NusaNode/src/protocol/nusa_appproto.h.
 * Versi 1 = byte 8..37 (counter radio). Versi 2 menambah airtime dan konfigurasi radio (byte 38..55).
 * Versi 3 menambah tegangan baterai node (byte 56..57).
 * Versi 4 menambah statistik koneksi BLE HP <-> node dari sisi node (byte 58..67).
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
    /** Versi 2 ke atas: total airtime yang dipancarkan sejak boot (ms). */
    val airtimeTotalMs: Long? = null,
    val lastTxAirtimeMs: Float? = null,
    val radio: NodeRadioConfig? = null,
    /** Versi 3 ke atas: tegangan baterai node (mV); null = tidak terbaca. */
    val batteryMv: Int? = null,
    /** Versi 4 ke atas: koneksi BLE dari sudut pandang node. */
    val ble: NodeBleStats? = null,
) {
    companion object {
        const val OFFSET = 8
        const val SIZE = 30
        const val SIZE_V2 = 48
        const val SIZE_V3 = 50
        const val SIZE_V4 = 60
        private const val NEVER = 0xFFFF

        fun decode(payload: ByteArray): NodeLiveStats? {
            if (payload.size < OFFSET + SIZE) return null
            fun u8(i: Int) = payload[OFFSET + i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun s16(i: Int) = u16(i).toShort().toInt()
            fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
            val version = u8(0)
            if (version < 1) return null
            val flags = u8(1)
            val v2 = version >= 2 && payload.size >= OFFSET + SIZE_V2
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
                airtimeTotalMs = if (v2) u32(30) else null,
                lastTxAirtimeMs = if (v2) u16(34).toFloat() else null,
                radio = if (v2) NodeRadioConfig(
                    frequencyMhz = u32(36) / 1000.0,
                    txPowerDbm = payload[OFFSET + 40].toInt(),
                    bandwidthKhz = u16(41) / 10f,
                    codingRate = u8(43),
                    dutyLimitPercent = u16(44) / 100f,
                    budgetCapacityMs = u16(46),
                ) else null,
                batteryMv = if (version >= 3 && payload.size >= OFFSET + SIZE_V3) u16(48).takeIf { it > 0 } else null,
                ble = if (version >= 4 && payload.size >= OFFSET + SIZE_V4) NodeBleStats(
                    connects = u16(50),
                    disconnects = u16(52),
                    notifyFails = u16(54),
                    lastReason = u16(56),
                    lastDisconnectAgeSeconds = u16(58).takeIf { it != NEVER },
                ) else null,
            )
        }
    }
}

/** Konfigurasi radio yang dilaporkan node (blok live versi 2). */
data class NodeRadioConfig(
    val frequencyMhz: Double,
    val txPowerDbm: Int,
    val bandwidthKhz: Float,
    /** 5..8 berarti 4/5..4/8. */
    val codingRate: Int,
    val dutyLimitPercent: Float,
    /** Batas tabungan anggaran airtime (ms): sisa anggaran tidak bisa melebihi ini. */
    val budgetCapacityMs: Int,
)

/** Koneksi BLE HP <-> node menurut node (blok live versi 4), sejak node menyala. */
data class NodeBleStats(
    val connects: Int,
    val disconnects: Int,
    val notifyFails: Int,
    /** Kode alasan putus terakhir NimBLE; lihat [BleReasons.nimble]. */
    val lastReason: Int,
    val lastDisconnectAgeSeconds: Int?,
)

/** Nama kode alasan putus BLE supaya log bisa dibaca tanpa tabel kode. */
object BleReasons {
    /** Status GATT Android (onConnectionStateChange). */
    fun android(status: Int): String = when (status) {
        0 -> "success"
        8 -> "connection_timeout"
        19 -> "remote_terminated"
        22 -> "local_terminated"
        34 -> "ll_response_timeout"
        62 -> "failed_to_establish"
        133 -> "gatt_error_133"
        else -> "status_$status"
    }

    /** Kode NimBLE: 0x200 + kode HCI. */
    fun nimble(code: Int): String = when (code) {
        0 -> "none"
        0x208 -> "supervision_timeout"
        0x213 -> "remote_user_terminated"
        0x214 -> "remote_low_resources"
        0x215 -> "remote_power_off"
        0x216 -> "local_host_terminated"
        0x222 -> "ll_response_timeout"
        0x23e -> "failed_to_establish"
        else -> "code_0x" + code.toString(16)
    }
}
