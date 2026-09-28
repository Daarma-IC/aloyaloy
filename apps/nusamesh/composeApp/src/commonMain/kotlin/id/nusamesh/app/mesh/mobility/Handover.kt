package id.nusamesh.app.mesh.mobility

import kotlin.math.roundToInt

/**
 * Parameter handover antar Nusa Node (docs/MOBILITY_LAYER.md, CD-3 §4.6.3). Bukan angka baku: dipilih lewat
 * percobaan lapangan dan bisa diubah dari layar uji tanpa build ulang.
 *
 *  A3: RSSI tetangga > RSSI node pelayanan + [hysteresisDb], bertahan [tttChecks] cek (time-to-trigger).
 *  A2: RSSI node pelayanan ≤ [weakRssiDbm] → cukup unggul [weakGainDb].
 *  RSSI disaring EWMA: F = (1 − a)·F + a·M, a = [filterAlpha] (1.0 = tanpa filter).
 */
data class MobilityConfig(
    val handoverEnabled: Boolean = true,
    val hysteresisDb: Int = 8,
    val tttChecks: Int = 3,
    val checkIntervalMs: Long = 4_000L,
    val weakRssiDbm: Int = -90,
    val weakGainDb: Int = 4,
    val cooldownMs: Long = 30_000L,
    val filterAlpha: Float = 0.5f,
    val pingPongWindowMs: Long = 60_000L,
    val connectTimeoutMs: Long = 20_000L,
    val failedBanMs: Long = 60_000L,
    val scanFreshMs: Long = 12_000L,
    val loggingEnabled: Boolean = true,
) {
    val timeToTriggerMs get() = tttChecks * checkIntervalMs
}

/** Satu sampel RSSI iklan BLE sebuah node. [sampledAt] membedakan sampel baru dari yang sama. */
data class RssiSample(val nodeId: String, val rssi: Int, val sampledAt: Long)

sealed interface HandoverEvent {
    data class ConditionMet(val from: String, val to: String, val servingRssi: Int, val targetRssi: Int) : HandoverEvent
    data class ConditionReset(val target: String, val heldMs: Long, val reason: String) : HandoverEvent
    /** Time-to-trigger terpenuhi: pindah klaim ke [to], putus [from], sambung [to]. */
    data class Attempt(val from: String, val to: String, val servingRssi: Int, val targetRssi: Int, val sinceConditionMs: Long) : HandoverEvent
}

/**
 * Pemutus handover (hard handover, diputuskan HP, break-before-make) — logika checkNusaNodeHandover Nusa Mesh
 * tanpa ketergantungan BLE. Dipanggil tiap [MobilityConfig.checkIntervalMs] dengan sampel terbaru.
 */
class HandoverDecider(private val now: () -> Long) {
    private val filtered = HashMap<String, Float>()
    private val lastSampleAt = HashMap<String, Long>()
    private val bannedUntil = HashMap<String, Long>()
    private var candidate: String? = null
    private var streak = 0
    private var conditionSince = 0L
    private var lastHandoverAt = Long.MIN_VALUE / 2

    /** Dipanggil saat target gagal tersambung/terdaftar: lewati sementara. */
    fun ban(nodeId: String, config: MobilityConfig) { bannedUntil[nodeId] = now() + config.failedBanMs }

    fun isBanned(nodeId: String) = (bannedUntil[nodeId] ?: Long.MIN_VALUE) > now()

    /** Catat bahwa handover (atau pemilihan manual) baru saja terjadi; memulai cooldown. */
    fun markHandover() { lastHandoverAt = now(); resetStreak() }

    fun filteredRssi(nodeId: String): Int? = filtered[nodeId]?.roundToInt()

    private fun filter(s: RssiSample, alpha: Float): Int {
        val prev = filtered[s.nodeId]
        val v = when {
            prev == null -> s.rssi.toFloat()
            lastSampleAt[s.nodeId] == s.sampledAt -> prev
            else -> (1 - alpha) * prev + alpha * s.rssi
        }
        filtered[s.nodeId] = v
        lastSampleAt[s.nodeId] = s.sampledAt
        return v.roundToInt()
    }

    private fun resetStreak(reason: String? = null): HandoverEvent? {
        val c = candidate
        candidate = null
        streak = 0
        return if (c != null && reason != null) HandoverEvent.ConditionReset(c, now() - conditionSince, reason) else null
    }

    /**
     * @param serving node pelayanan saat ini (null = tidak ada), beserta sampel RSSI terbarunya.
     * @param neighbors sampel node lain yang terdengar (sudah disaring kesegarannya oleh pemanggil).
     * @param manualLock true bila user mengunci node — handover otomatis mati.
     */
    fun evaluate(
        serving: RssiSample?,
        neighbors: List<RssiSample>,
        config: MobilityConfig,
        manualLock: Boolean = false,
    ): HandoverEvent? {
        val t = now()
        bannedUntil.entries.removeAll { it.value <= t }
        if (!config.handoverEnabled || manualLock) return resetStreak("disabled_or_manual")
        if (t - lastHandoverAt < config.cooldownMs) return null
        if (serving == null) return resetStreak("no_serving")

        val servingRssi = filter(serving, config.filterAlpha)
        val best = neighbors
            .filter { it.nodeId != serving.nodeId && !isBanned(it.nodeId) }
            .map { it to filter(it, config.filterAlpha) }
            .maxByOrNull { it.second }
            ?: return resetStreak("no_candidate")
        val (target, targetRssi) = best
        val gain = targetRssi - servingRssi
        val shouldMove = gain > config.hysteresisDb ||
            (servingRssi <= config.weakRssiDbm && gain >= config.weakGainDb)
        if (!shouldMove) return resetStreak("gain=${gain}dB")

        if (candidate != target.nodeId) {
            // Kandidat baru (atau berganti): time-to-trigger dihitung ulang dari sekarang.
            candidate = target.nodeId
            streak = 1
            conditionSince = t
            if (config.tttChecks > 1) {
                return HandoverEvent.ConditionMet(serving.nodeId, target.nodeId, servingRssi, targetRssi)
            }
        } else {
            streak++
        }
        if (streak < config.tttChecks) return null

        val since = t - conditionSince
        lastHandoverAt = t
        candidate = null
        streak = 0
        return HandoverEvent.Attempt(serving.nodeId, target.nodeId, servingRssi, targetRssi, since)
    }
}
