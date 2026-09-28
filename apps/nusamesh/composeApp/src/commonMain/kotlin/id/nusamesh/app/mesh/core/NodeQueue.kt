package id.nusamesh.app.mesh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Antrean kirim ke satu Nusa Node — kebijakan HCMA (docs/HCMA.md, NodePacketQueue.kt Nusa Mesh).
 *
 * Node cuma punya 3 slot BLE; HP yang tak kebagian menitip lewat HP perantara, dan node tak bisa
 * membedakan pesan asli perantara dari titipan (semua memakai jatah rate limit slot perantara). Maka
 * keadilan diatur DI SINI, di HP perantara:
 *  - urut kelas: ACK/read receipt/NODE_REGISTER → pesan → media/lainnya → announce/gossip;
 *  - dalam kelas yang sama, pesan milik sendiri didahulukan;
 *  - titipan yang sudah menunggu ≥ [RELAY_AGING_MS] disetarakan (tidak kelaparan);
 *  - titipan maksimal [MAX_RELAYED] dari [MAX_PENDING] slot.
 * Jeda antar-paket mengikuti anggaran airtime firmware (SF7/BW125, duty 5%).
 *
 * Tidak thread-safe: dipakai dari dispatcher engine yang satu jalur.
 */
class NodeQueue(private val myPeerId: ByteArray?, private val now: () -> Long) {
    companion object {
        const val MAX_PENDING = 64
        const val MAX_RELAYED = 40
        const val RELAY_AGING_MS = 30_000L
        private const val TYPE_NODE_REGISTER = 0x40

        fun spacingMs(bytes: Int): Long = maxOf(4000L, ((bytes + 242L) / 243L) * 10_000L)

        fun priorityOf(bytes: ByteArray): Int = when (bytes.getOrNull(1)?.toInt()?.and(0xFF)) {
            TYPE_NODE_REGISTER, 0x0A, 0x0C -> 0
            0x04 -> 1
            0x01, 0x13, 0x30, 0x31 -> 3
            else -> 2
        }
    }

    class Item internal constructor(
        val bytes: ByteArray,
        val enqueuedAt: Long,
        val own: Boolean,
        internal val send: suspend (ByteArray) -> Boolean,
    ) {
        val priority = priorityOf(bytes)
        /** NODE_REGISTER tak pernah dipancarkan ke LoRa: tak perlu jeda airtime. */
        val localOnly = bytes.getOrNull(1)?.toInt()?.and(0xFF) == TYPE_NODE_REGISTER
        fun rank(t: Long) = priority * 2 + if (own || t - enqueuedAt >= RELAY_AGING_MS) 0 else 1
    }

    private val pending = ArrayList<Item>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var worker: Job? = null

    val size get() = pending.size
    val relayedCount get() = pending.count { !it.own }

    /** senderID ada di offset 14 (header v1) / 16 (v2). */
    fun isOwn(bytes: ByteArray): Boolean {
        val me = myPeerId ?: return true
        val off = if ((bytes.getOrNull(0)?.toInt() ?: 1) >= 2) 16 else 14
        if (bytes.size < off + 8) return true
        for (i in 0 until 8) if (bytes[off + i] != me[i]) return false
        return true
    }

    fun offer(bytes: ByteArray, send: suspend (ByteArray) -> Boolean): Boolean = offerBatch(listOf(bytes), send)

    /** Semua-atau-tidak: fragmen satu pesan tidak boleh masuk setengah. */
    fun offerBatch(packets: List<ByteArray>, send: suspend (ByteArray) -> Boolean): Boolean {
        if (closed || pending.size + packets.size > MAX_PENDING) return false
        val t = now()
        val items = packets.map { Item(it.copyOf(), t, isOwn(it), send) }
        val relayedIncoming = items.count { !it.own }
        if (relayedIncoming > 0 && relayedCount + relayedIncoming > MAX_RELAYED) return false
        for (item in items) {
            // Gossip berkala boleh dilewati saat sibuk; jangan menumpuk di belakang voice.
            if (item.priority == 3 && pending.isNotEmpty()) continue
            pending += item
        }
        wake.trySend(Unit)
        return true
    }

    /** Ambil item berikutnya menurut kebijakan (FIFO bila rank sama). */
    fun takeNext(): Item? {
        if (pending.isEmpty()) return null
        val t = now()
        val idx = pending.indices.minBy { pending[it].rank(t) }
        return pending.removeAt(idx)
    }

    /** Jalankan pengirim berjeda di [scope] (harus scope engine yang satu jalur). */
    fun start(scope: CoroutineScope) {
        if (worker != null) return
        worker = scope.launch {
            for (ignored in wake) {
                while (isActive) {
                    val item = takeNext() ?: break
                    if (item.send(item.bytes) && !item.localOnly) delay(spacingMs(item.bytes.size))
                }
            }
        }
    }

    fun close() {
        closed = true
        pending.clear()
        wake.close()
        worker?.cancel()
    }
}
