package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.WirePacket
import id.nusamesh.app.mesh.protocol.WireProtocol
import id.nusamesh.app.mesh.protocol.peerIdBytes
import id.nusamesh.app.mesh.protocol.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FragmentRecoveryTest {
    private var t = 1_700_000_000_000L
    private val sender = peerIdBytes("a1a1a1a1a1a1a1a1")
    private val idle = Fragmenter.NACK_MIN_IDLE_MS + 5_000

    private fun parts(size: Int = 3000): List<WirePacket> = Fragmenter(Random(1), { t }).split(
        WirePacket(type = 4, senderId = sender, timestamp = t, payload = Random(2).nextBytes(size), ttl = 7),
    )

    private fun receiver() = Fragmenter(Random(3), { t })

    @Test
    fun codecRoundTripsAndRejectsMalformedPayloads() {
        val id = ByteArray(8) { it.toByte() }
        val encoded = FragmentNackCodec.encode(id, listOf(1, 300, 4095))
        assertEquals(id.toHex() to listOf(1, 300, 4095), FragmentNackCodec.decode(encoded))
        assertNull(FragmentNackCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(FragmentNackCodec.decode(ByteArray(9)))
        val capped = FragmentNackCodec.encode(id, List(200) { it })
        assertEquals(FragmentNackCodec.MAX_INDICES, FragmentNackCodec.decode(capped)?.second?.size)
    }

    @Test
    fun nackListsOnlyMissingFragmentsAfterTheSetGoesQuiet() {
        val rx = receiver()
        val all = parts()
        all.filterIndexed { i, _ -> i != 1 }.forEach { assertNull(rx.accept(it)) }
        assertTrue(rx.dueNacks().isEmpty(), "a set that is still arriving must not be nacked")
        t += idle
        val nack = rx.dueNacks().single()
        assertEquals("a1a1a1a1a1a1a1a1", nack.senderHex)
        assertEquals(listOf(1), nack.missing)
        assertTrue(rx.dueNacks().isEmpty(), "next round must wait for its backoff")
    }

    @Test
    fun stopsAfterMaxRoundsWithoutProgressAndNewFragmentsResetTheCount() {
        val rx = receiver()
        val all = parts()
        rx.accept(all[0])
        var rounds = 0
        repeat(40) { t += 10 * 60_000L; rounds += rx.dueNacks().size }
        assertEquals(Fragmenter.NACK_MAX_ROUNDS, rounds)
        rx.accept(all[1])
        t += idle
        assertEquals(1, rx.dueNacks().size)
    }

    @Test
    fun learnedSenderPaceDelaysTheNack() {
        val rx = receiver()
        val all = parts()
        rx.accept(all[0])
        t += 40_000
        rx.accept(all[1])
        t += 100_000
        assertTrue(rx.dueNacks().isEmpty(), "three times the observed gap has not passed yet")
        t += 30_000
        assertEquals(1, rx.dueNacks().size)
    }

    @Test
    fun recoveredFragmentCompletesTheOriginalPacket() {
        val rx = receiver()
        val all = parts()
        val original = WireProtocol.decode(WireProtocol.encode(all[0]))
        assertNotNull(original)
        all.drop(1).forEach { rx.accept(it) }
        t += idle
        val nack = rx.dueNacks().single()
        val cache = SentFragmentCache { t }
        val key = assertNotNull(cache.remember(all, allowLora = true))
        cache.finished(key)
        val resend = assertNotNull(cache.take(nack.fragmentId.toHex(), nack.missing))
        val rebuilt = assertNotNull(rx.accept(resend.packets.single()))
        assertContentEquals(Random(2).nextBytes(3000), rebuilt.payload)
    }

    @Test
    fun resentFragmentGetsAFreshTimestampSoNodeDedupDoesNotDropIt() {
        val all = parts()
        val cache = SentFragmentCache { t }
        val key = assertNotNull(cache.remember(all, allowLora = false))
        cache.finished(key)
        t += 1_000
        val resend = assertNotNull(cache.take(key, listOf(0)))
        assertEquals(false, resend.allowLora)
        assertNotEquals(all[0].timestamp, resend.packets[0].timestamp)
        assertContentEquals(all[0].payload, resend.packets[0].payload)
    }

    @Test
    fun cacheIgnoresNacksWhileOriginalsAreStillQueued() {
        val cache = SentFragmentCache { t }
        val key = assertNotNull(cache.remember(parts(), allowLora = true))
        assertNull(cache.take(key, listOf(0)))
    }

    @Test
    fun cacheEnforcesCooldownAndResendCap() {
        val cache = SentFragmentCache { t }
        val key = assertNotNull(cache.remember(parts(), allowLora = true))
        cache.finished(key)
        assertNotNull(cache.take(key, listOf(0)))
        assertNull(cache.take(key, listOf(0)), "a second receiver inside the cooldown must not cost more airtime")
        repeat(SentFragmentCache.MAX_RESENDS - 1) {
            t += SentFragmentCache.RESEND_COOLDOWN_MS
            assertNotNull(cache.take(key, listOf(0)))
        }
        t += SentFragmentCache.RESEND_COOLDOWN_MS
        assertNull(cache.take(key, listOf(0)))
    }

    @Test
    fun cacheIgnoresUnfragmentedPacketsAndUnknownIndices() {
        val cache = SentFragmentCache { t }
        val single = WirePacket(type = 4, senderId = sender, timestamp = t, payload = ByteArray(10), ttl = 7)
        assertNull(cache.remember(listOf(single), allowLora = true))
        val key = assertNotNull(cache.remember(parts(), allowLora = true))
        cache.finished(key)
        assertNull(cache.take(key, listOf(9_999)))
        assertNull(cache.take("0000000000000000", listOf(0)))
    }

    @Test
    fun cacheDropsExpiredAndOldestSets() {
        val cache = SentFragmentCache { t }
        val first = assertNotNull(cache.remember(parts(), allowLora = true))
        cache.finished(first)
        t += Fragmenter.FRAGMENT_TIMEOUT_MS + 1
        cache.expire()
        assertNull(cache.take(first, listOf(0)))
    }
}
