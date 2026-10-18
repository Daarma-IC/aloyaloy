package id.nusamesh.app.mesh.engine

import id.nusamesh.app.data.DiagnosticRecords
import kotlin.test.*

class TransmissionDiagnosticsTest {
    @Test fun devicesKeepSeparateBleAndMatchedLoraMeasurements() {
        val d = TransmissionDiagnostics({ 0 }, "test")
        d.record(DiagnosticLayer.BLE, "TX_ACCEPTED", deviceId = "a", bytes = 25)
        d.record(DiagnosticLayer.BLE, "TX_FAILED", deviceId = "b", bytes = 90)
        d.record(DiagnosticLayer.BLE, "RX_BYTES", deviceId = "b", bytes = 40)
        d.record(DiagnosticLayer.LORA, "RX_META_MATCHED", deviceId = "a", rssiDbm = -95.0, snrDb = 4.5)
        d.record(DiagnosticLayer.LORA, "RX_META_MISMATCH", deviceId = "a", rssiDbm = -70.0, snrDb = 9.0)
        d.record(DiagnosticLayer.LORA, "NODE_HEALTH", deviceId = "a", rssiDbm = -60.0, snrDb = 12.0)
        val stats = d.snapshot.value.devices
        assertEquals(25L, stats.getValue("a").txBytes)
        assertEquals(0L, stats.getValue("b").txBytes)
        assertEquals(40L, stats.getValue("b").rxBytes)
        assertEquals(-95.0, stats.getValue("a").loraRssiDbm)
        assertEquals(4.5, stats.getValue("a").loraSnrDb)
        assertEquals(1L, stats.getValue("b").failedWrites)
    }
    @Test fun ackTimingUsesMonotonicClockAndCountsOnlyFirstReceiver() {
        var wall = 10_000L
        var tick = 0L
        val d = TransmissionDiagnostics({ wall }, "test", monotonicMs = { tick })
        d.messageSubmitted("message")
        tick = 50
        d.messageSubmitted("message")
        wall = -999_999 // Device wall clock jumps backwards during a transmission.
        tick = 170
        d.acknowledgement("message", "receiver-a")
        d.acknowledgement("message", "receiver-b")
        d.acknowledgement("unknown", "receiver-a")
        val s = d.snapshot.value
        assertEquals(listOf(170L), s.ackDurations)
        assertEquals(1, s.acknowledgedMessages)
        assertEquals(1, s.count("MESSAGE_RESUBMITTED"))
        assertEquals(1, s.count("ACK_ADDITIONAL"))
        assertEquals(1, s.count("ACK_UNMATCHED"))
        assertEquals(0, s.trackedMessages)
    }

    @Test fun boundedLogDoesNotEraseSessionMetricsAndClearDoesNotForgetInFlightMessages() {
        val d = TransmissionDiagnostics({ 0L }, "test")
        d.messageSubmitted("waiting")
        repeat(1100) { d.record(DiagnosticLayer.BLE, "TX_ACCEPTED", bytes = 32, durationMs = it.toLong()) }
        val s = d.snapshot.value
        assertEquals(1000, s.events.size)
        assertEquals(256, s.bleDurations.size)
        assertEquals(35_200, s.txBytes)
        assertEquals(1100, s.count("TX_ACCEPTED"))
        assertEquals(101, s.trimmedEvents)
        d.clearLog()
        d.acknowledgement("waiting", "receiver")
        assertEquals(1, d.snapshot.value.acknowledgedMessages)
        assertEquals(35_200, d.snapshot.value.txBytes)
    }

    @Test fun bleAcceptanceDenominatorUsesCompletedWritesNotMessagesOrRadioTransmissions() {
        val d = TransmissionDiagnostics({ 0 }, "test")
        assertNull(d.snapshot.value.bleAcceptancePercent)
        repeat(3) { d.record(DiagnosticLayer.BLE, "TX_ACCEPTED", bytes = 10) }
        d.record(DiagnosticLayer.BLE, "TX_FAILED", bytes = 999)
        d.record(DiagnosticLayer.BLE, "TX_START")
        d.record(DiagnosticLayer.BLE, "TX_CANCELLED")
        assertEquals(75.0, d.snapshot.value.bleAcceptancePercent)
        assertEquals(30, d.snapshot.value.txBytes)
        assertEquals(0, d.snapshot.value.acknowledgedMessages)
    }

    @Test fun persistenceAndCsvPreserveMetadataWithoutSpreadsheetInjection() {
        val event = DiagnosticEvent(12, "session", 42, DiagnosticLayer.BLE, "CONNECT_FAILED", DiagnosticLevel.ERROR,
            deviceId = "=formula", detail = "failure, \"quoted\"\nnext line")
        assertEquals(listOf(event), DiagnosticRecords.decode(DiagnosticRecords.encode(listOf(event))))
        val csv = DiagnosticRecords.eventsCsv(listOf(event))
        assertTrue(csv.contains("'=formula"))
        assertTrue(csv.contains("\"\"quoted\"\""))
        assertTrue(DiagnosticRecords.decode("broken json").isEmpty())
        val restored = TransmissionDiagnostics({ 100 }, "new", listOf(event))
        restored.record(DiagnosticLayer.SYSTEM, "NEW_SESSION")
        assertEquals(13, restored.snapshot.value.events.last().sequence)
        assertEquals(0, restored.snapshot.value.txBytes)
    }

    @Test fun trackingEvictionIsVisibleAndCannotManufactureDelivery() {
        val d = TransmissionDiagnostics({ 0 }, "test")
        repeat(TransmissionDiagnostics.MAX_TRACKED + 1) { d.messageSubmitted("m$it") }
        d.acknowledgement("m0", "peer")
        assertEquals(1, d.snapshot.value.trackingEvictions)
        assertEquals(0, d.snapshot.value.acknowledgedMessages)
        assertEquals(1, d.snapshot.value.count("ACK_UNMATCHED"))
        d.persistenceResult(false)
        d.persistenceResult(false)
        assertEquals(1, d.snapshot.value.count("LOG_STORAGE_FAILED"))
        d.persistenceResult(true)
        assertEquals(1, d.snapshot.value.count("LOG_STORAGE_RECOVERED"))
    }
}
