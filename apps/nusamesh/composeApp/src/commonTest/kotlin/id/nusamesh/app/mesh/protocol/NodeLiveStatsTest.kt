package id.nusamesh.app.mesh.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeLiveStatsTest {
    /** Payload LORA_HEALTH seperti nusaBuildLoraHealth() firmware dengan blok live. */
    private fun payload(
        flags: Int = 0x01,
        tx: Long = 70_000,
        rx: Long = 12,
        rssiTenths: Int = -725,
        snrTenths: Int = 90,
        rxAge: Int = 3,
        txAge: Int = 0xFFFF,
    ): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) { out += v.toByte() }
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun u32(v: Long) { u16((v shr 16).toInt()); u16(v.toInt()) }
        // Byte 0..7: tetangga, rssi, snr, user, slot, sf, mode uji, admission.
        listOf(1, -70, 18, 1, 3, 7, 0, 1).forEach(::u8)
        u8(1)
        u8(flags)
        u32(tx)
        u32(rx)
        u32(2)
        u32(5)
        u16(9)
        u16(8000)
        u16(rssiTenths)
        u16(snrTenths)
        u16(rxAge)
        u16(txAge)
        return out.toByteArray()
    }

    @Test
    fun decodesLiveBlock() {
        val live = NodeLiveStats.decode(payload())!!
        assertTrue(live.radioReady)
        assertFalse(live.dualRadio)
        assertEquals(70_000, live.txFrames)
        assertEquals(12, live.rxFrames)
        assertEquals(2, live.droppedFrames)
        assertEquals(5, live.crcErrors)
        assertEquals(9, live.queueDepth)
        assertEquals(8000, live.budgetMs)
        assertEquals(-72.5f, live.lastRssiDbm)
        assertEquals(9.0f, live.lastSnrDb)
        assertEquals(3, live.lastRxAgeSeconds)
        assertNull(live.lastTxAgeSeconds)
    }

    @Test
    fun companionNotReady() {
        val live = NodeLiveStats.decode(payload(flags = 0x02))!!
        assertFalse(live.radioReady)
        assertTrue(live.dualRadio)
    }

    @Test
    fun oldFirmwareHasNoLiveBlock() {
        assertNull(NodeLiveStats.decode(payload().copyOf(8)))
    }
}
