package id.nusamesh.app.mesh.protocol

import id.nusamesh.app.data.QosRecords
import id.nusamesh.app.domain.DeliveryPath
import id.nusamesh.app.domain.QosRun
import id.nusamesh.app.domain.QosSample
import id.nusamesh.app.domain.RxMeasurement
import id.nusamesh.app.mesh.core.NodeQueue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QosTest {
    @Test
    fun msgIdMatchesFirmwareImplementation() {
        // Acuan: kode C nusaMsgId() dari firmware/NusaNode/nusa_frame.h, dikompilasi & dijalankan apa adanya.
        val data = ByteArray(64) { (it * 37 + 5).toByte() }
        val expected = mapOf(
            0 to "cbf29ce484222325", 1 to "af63b84c8601af60", 3 to "adb2e7185347b0da",
            25 to "3f3884e8b2af9d09", 64 to "d342175b75abf5de",
        )
        for ((len, hex) in expected) {
            assertEquals(hex, LoraRxMeta.msgIdOf(data.copyOf(len)).toULong().toString(16).padStart(16, '0'), "panjang $len")
        }
        // Byte TTL (indeks 2) tidak ikut: paket yang sama dengan TTL berbeda tetap satu msgId.
        val other = data.copyOf(25).also { it[2] = 99 }
        assertEquals(LoraRxMeta.msgIdOf(data.copyOf(25)), LoraRxMeta.msgIdOf(other))
    }

    @Test
    fun decodesFirmwareRxMetaPayload() {
        // [ver][msgId BE][rssi x10 BE][snr x10 BE][sf][bw x10 BE][hop][txPower]
        val payload = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0x12, 0x34, (-1123 shr 8).toByte(), (-1123).toByte(), (-75 shr 8).toByte(), (-75).toByte(), 10, 0x04, 0xE2.toByte(), 0xFF.toByte(), 17)
        val meta = assertNotNull(LoraRxMeta.decode(payload))
        assertEquals(0x1234L, meta.msgId)
        assertEquals(-112.3f, meta.rssiDbm)
        assertEquals(-7.5f, meta.snrDb)
        assertEquals(10, meta.spreadingFactor)
        assertEquals(125f, meta.bandwidthKHz)
        assertNull(meta.hopLeft, "0xFF = hop tak diketahui (paket terarah)")
        assertEquals(17, meta.txPowerDbm)
        assertNull(LoraRxMeta.decode(payload.copyOf(10)))
        assertNull(LoraRxMeta.decode(payload.copyOf().also { it[13] = 6 }), "SF di luar 7..12 ditolak")
    }

    @Test
    fun airtimeFollowsSemtechFormulaPerSf() {
        // Nilai acuan kalkulator Semtech: payload PHY 10 byte, BW125, CR4/5, header eksplisit, CRC, preamble 8.
        // airtimeMs() menambah header frame Nusa 12 byte, jadi paket app 0 byte = frame 12 byte.
        fun frame(bytes: Int, sf: Int) = NodeQueue.airtimeMs(ByteArray(maxOf(1, bytes - 12)), sf)
        assertTrue(abs(frame(13, 7) - 46.3) < 0.5, "SF7 13 B = ${frame(13, 7)}")
        assertTrue(abs(frame(13, 12) - 1155.1) < 1.0, "SF12 13 B (LDRO) = ${frame(13, 12)}")
        val full = ByteArray(243)
        val bySf = (7..12).map { NodeQueue.airtimeMs(full, it) }
        assertTrue(bySf.zipWithNext().all { (a, b) -> b > a * 1.6 }, "tiap naik SF airtime ±2× lipat: $bySf")
    }

    @Test
    fun probeRoundTripAndPspIgnoresDuplicates() {
        val probe = QosProbe("r1", 7, 50, 9, GeoPoint(-7.54078, 110.44572), "500 m|SF9")
        val back = assertNotNull(QosProbe.decode(probe.encode()))
        assertEquals("500 m/SF9", back.label)
        assertEquals(9, back.senderSf)
        assertEquals(7, back.seq)
        assertNull(QosProbe.decode("@meshta-qos-v1|r1|51|50|9|||x"), "seq > total ditolak")
        assertNull(QosProbe.decode(QosProbe("r1", 1, 5, null, null, "x").encode())?.senderPoint)

        fun sample(seq: Int, rssi: Float, d: Double) = QosSample(seq, RxMeasurement(1L, DeliveryPath.Node, loraRssiDbm = rssi, loraSnrDb = 5f, spreadingFactor = 9, distanceMeters = d))
        val run = QosRun("r1", "p", "Ayu", "500 m", total = 10, senderSf = 9, startedAtMs = 0,
            samples = listOf(sample(1, -100f, 500.0), sample(2, -102f, 510.0), sample(2, -101f, 505.0), sample(4, -98f, 495.0)))
        assertEquals(3, run.received, "seq 2 lewat dua jalur dihitung sekali")
        assertEquals(30.0, run.pspPercent)
        assertEquals(-100.25, run.meanRssi)
        assertEquals(9, run.measuredSf)
    }

    @Test
    fun recordsSurviveRestartAndExportCsv() {
        val rx = RxMeasurement(1_759_650_000_000L, DeliveryPath.Node, -97.5f, 6.3f, 9, 125f, 2, true, -61, 512.4, 0, 1800)
        val runs = listOf(QosRun("r1", "p1", "Ayu, Tim A", "500 m", 20, 9, listOf(QosSample(1, rx), QosSample(3, rx)), 1_759_650_000_000L))
        val restored = QosRecords.decode(QosRecords.encode(runs))
        assertEquals(runs.single().samples, restored.single().samples)
        assertEquals("Ayu, Tim A", restored.single().senderName)

        val packets = QosRecords.packetsCsv(restored).lines()
        assertEquals("run_id,label,sender,seq,total,received_at_utc,rssi_dbm,snr_db,sf,bw_khz,hop_left,meta_matched,ble_rssi_dbm,distance_m,sender_position_age_s,latency_ms,path", packets[0])
        assertEquals("r1,500 m,\"Ayu, Tim A\",1,20,2025-10-05T07:40:00Z,-97.5,6.3,9,125.0,2,1,-61,512.4,0,1800,Node", packets[1])
        val summary = QosRecords.summaryCsv(restored).lines()
        assertTrue(summary[1].startsWith("r1,500 m,\"Ayu, Tim A\",9,20,2,10.0,-97.5,6.3,512.4,1800.0,"), summary[1])
    }
}
