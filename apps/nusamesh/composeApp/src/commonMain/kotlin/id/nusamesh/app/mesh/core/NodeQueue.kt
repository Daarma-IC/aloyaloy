package id.nusamesh.app.mesh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

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
 * Jeda antar-paket mengikuti dua pembatas firmware: anggaran duty cycle radio (nusa_radio.cpp) dan
 * token bucket per-HP (nusa_ble.cpp). Airtime dihitung dari ukuran TANPA padding, karena node membuang
 * padding PKCS#7 sebelum memancarkan — teks pendek = satu frame (~0,3 s), bukan dua.
 *
 * Tidak thread-safe: dipakai dari dispatcher engine yang satu jalur.
 */
class NodeQueue(private val myPeerId: ByteArray?, private val now: () -> Long) {
    companion object {
        // 96 KB pada MTU 517 menghasilkan sekitar 215 fragmen. Sisakan ruang untuk pesan teks
        // yang masuk selama voice masih menunggu jatah airtime radio.
        const val MAX_PENDING = 512
        const val MAX_RELAYED = 384
        const val RELAY_AGING_MS = 30_000L
        private const val TYPE_NODE_REGISTER = 0x40

        // --- Harus cocok dengan firmware/NusaNode/config.h: SF7 / BW125 / CR4/5 / preamble 8, duty 5% ---
        /** HP mengambil 4% airtime; sisanya untuk relay antar-node dan HELLO. */
        const val DUTY_SHARE = 0.04
        /** Boleh berutang sekian ms airtime: beberapa teks pendek keluar berurutan tanpa jeda. */
        const val BURST_AIR_MS = 1200.0
        /** Firmware: 0,3 paket/detik per HP (burst 3); di atas itu paket DIBUANG diam-diam oleh node. */
        const val RATE_INTERVAL_MS = 3500.0
        const val RATE_BURST = 2.0
        private const val RECHECK_MS = 500L

        private const val LORA_FRAME_DATA = 243      // NUSA_FRAG_MAX_DATA
        private const val LORA_FRAME_HEADER = 12     // NUSA_FRAME_HDR
        private const val SYMBOL_MS = 1.024          // 2^SF / BW = 128 / 125 kHz
        private const val PREAMBLE_MS = (8 + 4.25) * SYMBOL_MS

        /** Sama dengan nusaStripPadding() di firmware. */
        fun unpaddedLength(bytes: ByteArray): Int {
            val len = bytes.size
            if (len != 256 && len != 512 && len != 1024 && len != 2048) return len
            val pad = bytes[len - 1].toInt() and 0xFF
            return if (pad == 0 || pad > len) len else len - pad
        }

        /** Airtime satu frame LoRa (rumus Semtech, header eksplisit + CRC). */
        private fun frameAirtimeMs(frameBytes: Int): Double {
            val symbols = 8 + maxOf(0.0, ceil((8.0 * frameBytes - 4 * 7 + 28 + 16) / (4.0 * 7))) * 5
            return PREAMBLE_MS + symbols * SYMBOL_MS
        }

        /** Airtime total paket app setelah dipecah node menjadi frame LoRa. */
        fun airtimeMs(bytes: ByteArray): Double {
            var left = unpaddedLength(bytes).coerceAtLeast(1)
            var total = 0.0
            while (left > 0) {
                val chunk = minOf(left, LORA_FRAME_DATA)
                total += frameAirtimeMs(chunk + LORA_FRAME_HEADER)
                left -= chunk
            }
            return total
        }

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
        internal val batch: Batch?,
    ) {
        val priority = priorityOf(bytes)
        /** NODE_REGISTER tak pernah dipancarkan ke LoRa: tak perlu jeda airtime. */
        val localOnly = bytes.getOrNull(1)?.toInt()?.and(0xFF) == TYPE_NODE_REGISTER
        fun rank(t: Long) = priority * 2 + if (own || t - enqueuedAt >= RELAY_AGING_MS) 0 else 1
    }

    internal class Batch(
        private val total: Int,
        private val onProgress: (Int, Int) -> Unit,
        private val onComplete: (Boolean) -> Unit,
    ) {
        var sent = 0
            private set
        var finished = false
            private set

        fun sentOne() {
            if (finished) return
            sent += 1
            onProgress(sent, total)
            if (sent == total) finish(true)
        }

        fun finish(success: Boolean) {
            if (finished) return
            finished = true
            onComplete(success)
        }
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
    fun offerBatch(
        packets: List<ByteArray>,
        send: suspend (ByteArray) -> Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        if (closed || packets.isEmpty() || pending.size + packets.size > MAX_PENDING) {
            onComplete(false)
            return false
        }
        val t = now()
        val batch = Batch(packets.size, onProgress, onComplete)
        val items = packets.map { Item(it.copyOf(), t, isOwn(it), send, batch) }
        val relayedIncoming = items.count { !it.own }
        if (relayedIncoming > 0 && relayedCount + relayedIncoming > MAX_RELAYED) {
            batch.finish(false)
            return false
        }
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

    private var airCreditMs = BURST_AIR_MS
    private var rateTokens = RATE_BURST
    private var lastRefill = now()

    private fun refill(t: Long) {
        val dt = (t - lastRefill).coerceAtLeast(0)
        lastRefill = t
        airCreditMs = minOf(BURST_AIR_MS, airCreditMs + dt * DUTY_SHARE)
        rateTokens = minOf(RATE_BURST, rateTokens + dt / RATE_INTERVAL_MS)
    }

    /** Berapa lama lagi sampai paket ber-airtime berikutnya boleh dikirim (0 = sekarang). */
    fun waitMs(): Long {
        refill(now())
        val air = if (airCreditMs >= 0) 0.0 else -airCreditMs / DUTY_SHARE
        val rate = if (rateTokens >= 1.0) 0.0 else (1.0 - rateTokens) * RATE_INTERVAL_MS
        return ceil(maxOf(air, rate)).toLong()
    }

    private fun peekNext(): Item? {
        val t = now()
        return pending.minByOrNull { it.rank(t) }
    }

    /** Jalankan pengirim berjeda di [scope] (harus scope engine yang satu jalur). */
    fun start(scope: CoroutineScope) {
        if (worker != null) return
        worker = scope.launch {
            for (ignored in wake) {
                while (isActive) {
                    // Pilih ulang tiap putaran: teks yang masuk saat menunggu jatah airtime tetap
                    // mendahului fragmen voice/gambar yang sudah lama antre.
                    val next = peekNext() ?: break
                    if (!next.localOnly) {
                        val wait = waitMs()
                        if (wait > 0) { delay(minOf(wait, RECHECK_MS)); continue }
                    }
                    pending.remove(next)
                    if (next.send(next.bytes)) {
                        if (!next.localOnly) {
                            airCreditMs -= airtimeMs(next.bytes)
                            rateTokens -= 1.0
                        }
                        next.batch?.sentOne()
                    } else {
                        val batch = next.batch
                        if (batch != null) {
                            pending.removeAll { it.batch === batch }
                            batch.finish(false)
                        }
                    }
                }
            }
        }
    }

    fun close() {
        closed = true
        pending.mapNotNull { it.batch }.distinct().forEach { it.finish(false) }
        pending.clear()
        wake.close()
        worker?.cancel()
    }
}
