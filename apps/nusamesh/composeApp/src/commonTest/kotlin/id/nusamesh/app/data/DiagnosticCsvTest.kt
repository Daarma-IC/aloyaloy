package id.nusamesh.app.data

import id.nusamesh.app.mesh.engine.DiagnosticEvent
import id.nusamesh.app.mesh.engine.DiagnosticLayer
import id.nusamesh.app.mesh.engine.DiagnosticLevel
import id.nusamesh.app.mesh.engine.NodeDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticCsvTest {
    @Test
    fun localTimeUsesDeviceOffset() {
        // 2026-11-12 06:12:26.769 UTC = 13:12:26.769 WIB.
        assertEquals("2026-11-12T13:12:26.769+07:00", LocalTime.iso(1_794_463_946_769, offsetMinutes = 420))
        assertEquals("2026-11-12_13-12-26", LocalTime.fileStamp(1_794_463_946_769, offsetMinutes = 420))
        assertEquals("2026-11-12T01:12:26.769-05:00", LocalTime.iso(1_794_463_946_769, offsetMinutes = -300))
    }

    @Test
    fun csvQuotesOnlyWhenNeeded() {
        val event = DiagnosticEvent(11917, "s1", 1_794_463_946_769, DiagnosticLayer.LORA, "NODE_RX_FRAMES",
            DiagnosticLevel.INFO, deviceId = "4e55534e00000003", rssiDbm = -87.0, snrDb = 7.800000190734863,
            detail = "frames=1; total=256")
        NodeDirectory.clear()
        val row = DiagnosticRecords.csvRow(event)
        assertTrue(row.startsWith("11917,s1,1794463946769,LORA,NODE_RX_FRAMES,INFO,4e55534e00000003,,,,-87.0,7.8,,frames=1; total=256,"), row)
        assertTrue(row.endsWith(",3,"), row)
        assertTrue(DiagnosticRecords.csvHeader.endsWith(",detail,local_time,node_id,node_name"))
    }

    @Test
    fun nodeColumnsResolveBleMacOfNode() {
        NodeDirectory.clear()
        NodeDirectory.update(listOf(Triple("4e55534e00000003", "NusaNode-C", listOf("14:2B:2F:93:76:92"))))
        val viaMac = DiagnosticEvent(1, "s1", 0, DiagnosticLayer.MESH, "RX_PACKET", DiagnosticLevel.INFO,
            deviceId = "14:2b:2f:93:76:92")
        assertTrue(DiagnosticRecords.csvRow(viaMac).endsWith(",3,NusaNode-C"))
        val phone = DiagnosticEvent(2, "s1", 0, DiagnosticLayer.BLE, "RSSI_SCAN", DiagnosticLevel.INFO,
            deviceId = "7B:CF:6F:20:38:BB")
        assertTrue(DiagnosticRecords.csvRow(phone).endsWith(",,"))
        NodeDirectory.clear()
    }
}
