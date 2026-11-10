package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.MessagePadding
import id.nusamesh.app.mesh.protocol.MessageType
import id.nusamesh.app.mesh.protocol.SpecialRecipients
import id.nusamesh.app.mesh.protocol.WirePacket
import id.nusamesh.app.mesh.protocol.WireProtocol
import id.nusamesh.app.mesh.protocol.toHex
import kotlin.random.Random

/** Permintaan ke [senderHex] untuk mengirim ulang fragmen [missing] dari set [fragmentId]. */
class FragmentNack(val senderHex: String, val fragmentId: ByteArray, val missing: List<Int>)

/** Payload FRAGMENT_NACK: id set 8 B, jumlah 1 B, lalu indeks 2 B per fragmen. Muat satu frame LoRa. */
object FragmentNackCodec {
    const val MAX_INDICES = 64

    fun encode(fragmentId: ByteArray, missing: List<Int>): ByteArray {
        val indices = missing.take(MAX_INDICES)
        val out = ByteArray(9 + indices.size * 2)
        fragmentId.copyInto(out, 0, 0, 8)
        out[8] = indices.size.toByte()
        indices.forEachIndexed { i, index ->
            out[9 + i * 2] = (index ushr 8).toByte()
            out[10 + i * 2] = index.toByte()
        }
        return out
    }

    /** Mengembalikan (id set dalam hex, daftar indeks), atau null bila payload rusak. */
    fun decode(payload: ByteArray): Pair<String, List<Int>>? {
        if (payload.size < 9) return null
        val count = payload[8].toInt() and 0xFF
        if (count !in 1..MAX_INDICES || payload.size != 9 + count * 2) return null
        val indices = List(count) { i ->
            ((payload[9 + i * 2].toInt() and 0xFF) shl 8) or (payload[10 + i * 2].toInt() and 0xFF)
        }
        return payload.copyOfRange(0, 8).toHex() to indices
    }
}

/**
 * Fragmentasi paket besar agar muat satu GATT write/notify — format identik dengan
 * FragmentManager.kt Nusa Mesh (payload fragmen: id 8 B, index 2 B, total 2 B, tipe asli 1 B, data).
 *
 * Tidak thread-safe: dipanggil dari satu jalur eksekusi engine. MTU koneksi dan jam diberikan dari luar.
 */
class Fragmenter(
    private val random: Random = Random.Default,
    private val now: () -> Long,
) {
    companion object {
        const val FALLBACK_THRESHOLD = 400
        const val FALLBACK_FRAGMENT_SIZE = 380
        // Transfer 96 KB dapat membutuhkan lebih dari satu jam karena duty-cycle LoRa.
        const val FRAGMENT_TIMEOUT_MS = 3 * 60 * 60_000L
        private const val PACKET_HEADER_SIZE = 16 + 8 + 8 + 2
        private const val FRAGMENT_HEADER_SIZE = 13
        private const val MIN_FRAGMENT_DATA = 20
        private const val ATT_MAX_VALUE = 512           // NimBLE di Nusa Node menolak write > 512 B
        private const val MAX_PENDING_SETS = 32
        private const val MAX_TOTAL = 4096
        /** Diam minimum sebelum set dianggap macet; jarak antar-fragmen LoRa SF7 bisa ±25 detik. */
        const val NACK_MIN_IDLE_MS = 45_000L
        private const val NACK_MAX_IDLE_MS = 15 * 60_000L
        /** Sebar waktu NACK antar-penerima broadcast supaya tidak bertabrakan di LoRa. */
        private const val NACK_JITTER_MS = 5_000
        /** Berhenti meminta setelah sekian putaran berturut-turut tanpa fragmen baru. */
        const val NACK_MAX_ROUNDS = 4

        fun maxWirePacketSize(mtu: Int) = minOf(mtu - 3, ATT_MAX_VALUE)

        fun fragmentDataSize(mtu: Int): Int {
            val limit = maxWirePacketSize(mtu)
            var raw = limit
            while (raw > 0 && paddedSize(raw) > limit) raw--
            if (raw == 0) raw = limit
            return (raw - PACKET_HEADER_SIZE - FRAGMENT_HEADER_SIZE).coerceAtLeast(MIN_FRAGMENT_DATA)
        }

        private fun paddedSize(raw: Int): Int {
            val block = MessagePadding.optimalBlockSize(raw)
            return if (block > raw && block - raw <= 255) block else raw
        }
    }

    private class Pending(
        val id: ByteArray, val sender: String,
        val originalType: Int, val total: Int, val firstSeen: Long,
    ) {
        val parts = HashMap<Int, ByteArray>()
        var lastNewAt = firstSeen
        var maxGapMs = 0L
        var nackRounds = 0
        var nextNackAt = Long.MAX_VALUE
    }

    /** Batas bawah "diam" sesuai SF node aktif; diperbarui engine dari laporan kesehatan node. */
    var nackMinIdleMs = NACK_MIN_IDLE_MS

    private val pending = HashMap<String, Pending>()
    // Fragmen duplikat yang tiba SETELAH paket utuh tidak boleh membuka set baru yang tak akan pernah selesai.
    private val recentlyCompleted = LinkedHashSet<String>()

    /** MTU koneksi yang sedang aktif (diperbarui transport). Kosong → nilai cadangan. */
    var connectionMtus: List<Int> = emptyList()

    val pendingSets get() = pending.size

    fun split(packet: WirePacket): List<WirePacket> {
        val type = packet.messageType
        if (type == MessageType.ANNOUNCE || type == MessageType.FRAGMENT_START ||
            type == MessageType.FRAGMENT_CONTINUE || type == MessageType.FRAGMENT_END
        ) return listOf(packet)

        val data = WireProtocol.encode(packet, random = random)
        val broadcast = SpecialRecipients.isBroadcast(packet.recipientId)
        val (threshold, size) = sizing(broadcast)
        if (data.size <= threshold) return listOf(packet)

        val id = random.nextBytes(8)
        val total = (data.size + size - 1) / size
        return List(total) { i ->
            val chunk = data.copyOfRange(i * size, minOf((i + 1) * size, data.size))
            val payload = ByteArray(FRAGMENT_HEADER_SIZE + chunk.size)
            id.copyInto(payload, 0)
            payload[8] = (i ushr 8).toByte(); payload[9] = i.toByte()
            payload[10] = (total ushr 8).toByte(); payload[11] = total.toByte()
            payload[12] = packet.type.toByte()
            chunk.copyInto(payload, FRAGMENT_HEADER_SIZE)
            WirePacket(
                type = when (i) {
                    0 -> MessageType.FRAGMENT_START.value
                    total - 1 -> MessageType.FRAGMENT_END.value
                    else -> MessageType.FRAGMENT_CONTINUE.value
                },
                senderId = packet.senderId, recipientId = packet.recipientId,
                timestamp = packet.timestamp, payload = payload, signature = null, ttl = packet.ttl,
                voicePriority = packet.voicePriority,
            )
        }
    }

    /** Terima satu fragmen. Mengembalikan paket utuh bila semua bagian sudah tiba. */
    fun accept(fragment: WirePacket): WirePacket? {
        val p = fragment.payload
        if (p.size < FRAGMENT_HEADER_SIZE) return null
        val key = p.copyOfRange(0, 8).toHex()
        if (key in recentlyCompleted) return null
        val index = ((p[8].toInt() and 0xFF) shl 8) or (p[9].toInt() and 0xFF)
        val total = ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF)
        val originalType = p[12].toInt() and 0xFF
        if (total !in 1..MAX_TOTAL || index !in 0 until total) return null
        val set = pending[key]
        if (set == null && pending.size >= MAX_PENDING_SETS) return null
        if (set != null && (set.originalType != originalType || set.total != total)) return null
        val t = now()
        val entry = set ?: Pending(p.copyOfRange(0, 8), fragment.senderHex, originalType, total, t).also { pending[key] = it }
        if (index !in entry.parts) noteProgress(entry, t)
        entry.parts[index] = p.copyOfRange(FRAGMENT_HEADER_SIZE, p.size)
        if (entry.parts.size < total) return null

        pending.remove(key)
        recentlyCompleted += key
        if (recentlyCompleted.size > 64) recentlyCompleted.remove(recentlyCompleted.first())
        val out = ByteArray(entry.parts.values.sumOf { it.size })
        var off = 0
        for (i in 0 until total) {
            val part = entry.parts[i] ?: return null
            part.copyInto(out, off)
            off += part.size
        }
        return WireProtocol.decode(out)
    }

    private fun noteProgress(set: Pending, t: Long) {
        // Jeda saat NACK sedang berjalan bukan cerminan laju pengirim, jadi tidak ikut dipelajari.
        if (set.parts.isNotEmpty() && set.nackRounds == 0) set.maxGapMs = maxOf(set.maxGapMs, t - set.lastNewAt)
        set.lastNewAt = t
        set.nackRounds = 0
        set.nextNackAt = t + idleMs(set) + random.nextInt(NACK_JITTER_MS)
    }

    private fun idleMs(set: Pending) = maxOf(nackMinIdleMs, minOf(set.maxGapMs * 3, NACK_MAX_IDLE_MS))

    /** Set yang macet dan perlu diminta ulang sekarang. Tiap panggilan memajukan jadwal set yang dikembalikan. */
    fun dueNacks(): List<FragmentNack> {
        val t = now()
        return pending.values.mapNotNull { set ->
            if (t < set.nextNackAt || set.nackRounds >= NACK_MAX_ROUNDS) return@mapNotNull null
            val missing = (0 until set.total).filter { it !in set.parts }.take(FragmentNackCodec.MAX_INDICES)
            set.nackRounds += 1
            set.nextNackAt = t + (idleMs(set) shl set.nackRounds) + random.nextInt(NACK_JITTER_MS)
            FragmentNack(set.sender, set.id, missing)
        }
    }

    /** Buang set fragmen yang terlalu lama tak lengkap. Mengembalikan jumlah yang dibuang. */
    fun expire(): Int {
        val cutoff = now() - FRAGMENT_TIMEOUT_MS
        val old = pending.filterValues { it.firstSeen < cutoff }.keys
        old.forEach { pending.remove(it) }
        return old.size
    }

    private fun sizing(broadcast: Boolean): Pair<Int, Int> {
        val mtus = connectionMtus.filter { it > 0 }
        if (mtus.isEmpty()) return FALLBACK_THRESHOLD to FALLBACK_FRAGMENT_SIZE
        // Broadcast memakai MTU terkecil (harus muat di semua koneksi), unicast rata-rata.
        val mtu = if (broadcast) mtus.min() else mtus.average().toInt()
        return maxOf(maxWirePacketSize(mtu), FALLBACK_THRESHOLD) to fragmentDataSize(mtu)
    }
}
