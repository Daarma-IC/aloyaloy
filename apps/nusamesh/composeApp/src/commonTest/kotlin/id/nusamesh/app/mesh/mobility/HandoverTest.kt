package id.nusamesh.app.mesh.mobility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoverTest {
    private var t = 1_000_000L
    private val cfg = MobilityConfig(filterAlpha = 1f)   // tanpa filter: angka mudah diikuti

    private fun tick(d: HandoverDecider, serving: Int, target: Int, c: MobilityConfig = cfg): HandoverEvent? {
        t += c.checkIntervalMs
        return d.evaluate(RssiSample("A", serving, t), listOf(RssiSample("B", target, t)), c)
    }

    @Test
    fun a3NeedsHysteresisAndTimeToTrigger() {
        val d = HandoverDecider { t }
        assertNull(tick(d, -70, -63), "unggul 7 dB < hysteresis 8 dB")
        assertIs<HandoverEvent.ConditionMet>(tick(d, -70, -60))
        assertNull(tick(d, -70, -60))
        val a = assertIs<HandoverEvent.Attempt>(tick(d, -70, -60))
        assertEquals("B", a.to)
        assertEquals(2 * cfg.checkIntervalMs, a.sinceConditionMs)
    }

    @Test
    fun conditionResetsWhenGainDisappears() {
        val d = HandoverDecider { t }
        tick(d, -70, -60)
        val r = assertIs<HandoverEvent.ConditionReset>(tick(d, -70, -69))
        assertEquals("B", r.target)
    }

    @Test
    fun a2WeakServingMovesWithSmallGain() {
        val d = HandoverDecider { t }
        assertIs<HandoverEvent.ConditionMet>(tick(d, -92, -87))
    }

    @Test
    fun cooldownBlocksImmediatePingPong() {
        val d = HandoverDecider { t }
        repeat(3) { tick(d, -80, -60) }
        // Sesaat setelah pindah ke B, A terlihat lebih kuat lagi: tidak boleh langsung balik.
        t += 1
        assertNull(d.evaluate(RssiSample("B", -80, t), listOf(RssiSample("A", -60, t)), cfg))
    }

    @Test
    fun bannedTargetIsSkippedAndManualLockDisables() {
        val d = HandoverDecider { t }
        d.ban("B", cfg)
        assertNull(tick(d, -80, -60))
        t += cfg.failedBanMs
        assertIs<HandoverEvent.ConditionMet>(tick(d, -80, -60))
        t += cfg.checkIntervalMs
        assertIs<HandoverEvent.ConditionReset>(d.evaluate(RssiSample("A", -80, t), listOf(RssiSample("B", -60, t)), cfg, manualLock = true))
    }

    @Test
    fun ewmaDampensSingleSpike() {
        val d = HandoverDecider { t }
        val c = cfg.copy(filterAlpha = 0.5f)
        tick(d, -75, -75, c)
        // Lonjakan satu sampel +16 dB → setelah filter hanya +8 dB: belum melewati hysteresis (> 8).
        assertNull(tick(d, -75, -59, c))
        assertTrue((d.filteredRssi("B") ?: 0) <= -66)
    }
}
