package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.MessageType
import id.nusamesh.app.mesh.protocol.WirePacket
import id.nusamesh.app.mesh.protocol.toHex

/**
 * Fragmen milik sendiri yang boleh dikirim ulang saat penerima mengirim FRAGMENT_NACK.
 *
 * Fragmen ulang memakai timestamp baru: firmware dan [PacketGuard] mengenali duplikat dari isi paket,
 * jadi salinan identik akan dibuang node sendiri. Perakitan tetap aman karena memakai id set + indeks.
 *
 * Tidak thread-safe: dipakai dari dispatcher engine yang satu jalur.
 */
class SentFragmentCache(private val now: () -> Long) {
    companion object {
        private const val MAX_SETS = 16
        private const val MAX_BYTES = 512 * 1024
        /** Beberapa penerima broadcast bisa meminta fragmen yang sama; cukup dilayani sekali per jendela. */
        const val RESEND_COOLDOWN_MS = 60_000L
        const val MAX_RESENDS = 5
    }

    private class Entry(val parts: List<WirePacket>, val allowLora: Boolean, val createdAt: Long) {
        var sending = true
        val lastResendAt = LongArray(parts.size) { Long.MIN_VALUE / 2 }
        val resends = IntArray(parts.size)
        val bytes = parts.sumOf { it.payload.size }
    }

    class Resend(val packets: List<WirePacket>, val allowLora: Boolean)

    private val entries = LinkedHashMap<String, Entry>()
    private var totalBytes = 0

    /** Simpan set fragmen. Mengembalikan kuncinya, atau null bila [parts] bukan set fragmen. */
    fun remember(parts: List<WirePacket>, allowLora: Boolean): String? {
        val first = parts.firstOrNull() ?: return null
        if (parts.size < 2 || first.type != MessageType.FRAGMENT_START.value || first.payload.size < 8) return null
        val key = first.payload.copyOfRange(0, 8).toHex()
        val entry = Entry(parts, allowLora, now())
        entries[key] = entry
        totalBytes += entry.bytes
        while ((entries.size > MAX_SETS || totalBytes > MAX_BYTES) && entries.size > 1) remove(entries.keys.first())
        return key
    }

    /** Semua fragmen asli sudah keluar antrean; NACK sebelum ini berarti fragmennya memang belum dikirim. */
    fun finished(key: String) {
        entries[key]?.sending = false
    }

    /** Fragmen yang perlu dikirim ulang untuk permintaan ini, atau null bila tidak ada. */
    fun take(key: String, indices: List<Int>): Resend? {
        val entry = entries[key] ?: return null
        if (entry.sending) return null
        val t = now()
        val packets = indices.distinct()
            .filter { it in entry.parts.indices && entry.resends[it] < MAX_RESENDS && t - entry.lastResendAt[it] >= RESEND_COOLDOWN_MS }
            .map { index ->
                entry.lastResendAt[index] = t
                entry.resends[index] += 1
                entry.parts[index].withTimestamp(t)
            }
        return if (packets.isEmpty()) null else Resend(packets, entry.allowLora)
    }

    fun expire() {
        val cutoff = now() - Fragmenter.FRAGMENT_TIMEOUT_MS
        entries.filterValues { it.createdAt < cutoff }.keys.toList().forEach(::remove)
    }

    private fun remove(key: String) {
        entries.remove(key)?.let { totalBytes -= it.bytes }
    }
}
