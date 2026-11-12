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
    fun decodesVersionTwoAirtimeAndRadioConfig() {
        val v1 = payload()
        v1[8] = 2
        val extra = ArrayList<Byte>()
        fun u8(v: Int) { extra += v.toByte() }
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun u32(v: Long) { u16((v shr 16).toInt()); u16(v.toInt()) }
        u32(123_456)      // airtime total ms
        u16(9912)         // airtime frame terakhir ms (frame 255 B pada SF12)
        u32(921_000)      // frekuensi kHz
        u8(14)            // daya dBm
        u16(1250)         // BW x10 kHz
        u8(5)             // CR 4/5
        u16(500)          // duty 5%
        u16(8000)         // batas anggaran ms
        val live = NodeLiveStats.decode(v1 + extra.toByteArray())!!
        assertEquals(123_456L, live.airtimeTotalMs)
        assertEquals(9912f, live.lastTxAirtimeMs)
        val radio = live.radio!!
        assertEquals(921.0, radio.frequencyMhz)
        assertEquals(14, radio.txPowerDbm)
        assertEquals(125.0f, radio.bandwidthKhz)
        assertEquals(5, radio.codingRate)
        assertEquals(5.0f, radio.dutyLimitPercent)
        assertEquals(8000, radio.budgetCapacityMs)
    }

    @Test
    fun decodesVersionThreeBattery() {
        val v1 = payload()
        v1[8] = 3
        val extra = ByteArray(18) + byteArrayOf((4130 shr 8).toByte(), 4130.toByte())
        val live = NodeLiveStats.decode(v1 + extra)!!
        assertEquals(4130, live.batteryMv)
        assertNull(NodeLiveStats.decode(payload())!!.batteryMv)
    }

    @Test
    fun decodesVersionFourBleStats() {
        val v1 = payload()
        v1[8] = 4
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        val extra = ByteArray(18) + u16(4130) + u16(5) + u16(3) + u16(1) + u16(0x208) + u16(42)
        val ble = NodeLiveStats.decode(v1 + extra)!!.ble!!
        assertEquals(5, ble.connects)
        assertEquals(3, ble.disconnects)
        assertEquals(1, ble.notifyFails)
        assertEquals("supervision_timeout", BleReasons.nimble(ble.lastReason))
        assertEquals(42, ble.lastDisconnectAgeSeconds)
        assertEquals("gatt_error_133", BleReasons.android(133))
    }

    @Test
    fun versionOneHasNoRadioConfig() {
        val live = NodeLiveStats.decode(payload())!!
        assertNull(live.radio)
        assertNull(live.airtimeTotalMs)
    }

    @Test
    fun oldFirmwareHasNoLiveBlock() {
        assertNull(NodeLiveStats.decode(payload().copyOf(8)))
    }
}
