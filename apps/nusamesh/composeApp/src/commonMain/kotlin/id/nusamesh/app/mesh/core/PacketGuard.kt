package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.MessageType
import id.nusamesh.app.mesh.protocol.WirePacket
import id.nusamesh.app.mesh.protocol.toHex
import kotlin.math.abs

/** Batas umur paket — sama dengan PacketAgePolicy Nusa Mesh: data boleh telat (LoRa), kontrol ketat. */
object PacketAgePolicy {
    const val DATA_WINDOW_MS = 15 * 60_000L
    const val CONTROL_WINDOW_MS = 5 * 60_000L

    fun accepts(type: Int, timestamp: Long, now: Long): Boolean {
        if (timestamp < 0 || timestamp > now + CONTROL_WINDOW_MS) return false
        val window = when (MessageType.fromValue(type)) {
            MessageType.MESSAGE, MessageType.NOISE_ENCRYPTED,
            MessageType.FRAGMENT_START, MessageType.FRAGMENT_CONTINUE, MessageType.FRAGMENT_END -> DATA_WINDOW_MS
            else -> CONTROL_WINDOW_MS
        }
        return timestamp >= now - window
    }
}

enum class GuardVerdict { Accept, Own, TtlExpired, Empty, TooOld, Duplicate }

/**
 * Validasi paket masuk (SecurityManager.validatePacket Nusa Mesh): paket sendiri, TTL 0, payload kosong,
 * umur, dan duplikat.
 *
 * Kunci duplikat memakai pengirim ASLI (senderId), bukan tetangga yang meneruskan — kalau tidak, paket yang
 * tiba lewat dua jalur lolos dua kali dan di-relay dua kali (boros airtime LoRa).
 */
class PacketGuard(
    private val myPeerId: String,
    private val now: () -> Long,
    private val maxEntries: Int = 10_000,
) {
    private val seen = LinkedHashMap<String, Long>()

    fun check(packet: WirePacket): GuardVerdict {
        if (packet.senderHex == myPeerId) return GuardVerdict.Own
        if (packet.ttl == 0) return GuardVerdict.TtlExpired
        if (packet.payload.isEmpty()) return GuardVerdict.Empty
        val t = now()
        if (!PacketAgePolicy.accepts(packet.type, packet.timestamp, t)) return GuardVerdict.TooOld
        val key = messageKey(packet)
        if (key in seen) return GuardVerdict.Duplicate
        seen[key] = t
        if (seen.size > maxEntries) seen.remove(seen.keys.first())
        return GuardVerdict.Accept
    }

    /** Buang catatan yang lebih tua dari jendela data (setelah itu paket lama pasti ditolak TooOld). */
    fun expire() {
        val cutoff = now() - PacketAgePolicy.DATA_WINDOW_MS
        seen.entries.removeAll { it.value < cutoff }
    }

    val size get() = seen.size

    companion object {
        fun messageKey(p: WirePacket): String {
            val t = p.messageType
            val fragment = t == MessageType.FRAGMENT_START || t == MessageType.FRAGMENT_CONTINUE || t == MessageType.FRAGMENT_END
            val body = if (fragment) p.payload else p.payload.copyOfRange(0, minOf(64, p.payload.size))
            val typePart = if (fragment) "-${p.type}" else ""
            return "${p.timestamp}-${p.senderId.toHex()}$typePart-${fnv1a64(body)}"
        }

        fun fnv1a64(data: ByteArray): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in data) {
                h = h xor (b.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}

/** Tidak dipakai langsung; menjaga agar `abs` tetap tersedia bila kebijakan waktu diperluas. */
internal fun ageMs(timestamp: Long, now: Long) = abs(now - timestamp)
