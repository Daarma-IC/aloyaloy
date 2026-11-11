package id.nusamesh.app.data

import id.nusamesh.app.media.AppendableFile
import id.nusamesh.app.media.DocumentActions
import id.nusamesh.app.mesh.engine.DiagnosticLayer
import id.nusamesh.app.mesh.engine.TransmissionDiagnostics
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransmissionLogRecorderTest {
    private class FakeDocuments(val file: MemoryAppendableFile?) : DocumentActions {
        override fun saveDocument(fileName: String, mimeType: String, content: String, onResult: (String) -> Unit) = Unit
        override fun openDocument(mimeTypes: List<String>, onOpened: (String) -> Unit, onError: (String) -> Unit) = Unit
        override fun openAppendable(fileName: String, mimeType: String): AppendableFile? = file
    }

    @Test
    fun recordsOnlyEventsAfterStartUntilStopped() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val diagnostics = TransmissionDiagnostics(now = { 1_000L })
        diagnostics.record(DiagnosticLayer.BLE, "BEFORE_START")
        val recorder = TransmissionLogRecorder(scope, diagnostics.snapshot, dispatcher)
        val file = MemoryAppendableFile("Documents/NusaMesh/log.csv")

        recorder.start(FakeDocuments(file), "log.csv", 1_000L)
        scope.advanceUntilIdle()
        diagnostics.record(DiagnosticLayer.LORA, "NODE_TX_FRAMES")
        diagnostics.record(DiagnosticLayer.LORA, "NODE_RX_FRAMES")
        scope.advanceUntilIdle()

        assertEquals(2, recorder.recording.value?.rows)
        val message = recorder.stop()
        assertTrue(message!!.contains("Documents/NusaMesh/log.csv"))
        assertNull(recorder.recording.value)

        diagnostics.record(DiagnosticLayer.LORA, "AFTER_STOP")
        scope.advanceUntilIdle()
        val text = file.content.toString()
        assertTrue(text.startsWith("﻿" + DiagnosticRecords.csvHeader))
        assertTrue("NODE_TX_FRAMES" in text && "NODE_RX_FRAMES" in text)
        assertFalse("BEFORE_START" in text || "AFTER_STOP" in text)
        assertTrue(file.closed)
    }

    @Test
    fun reportsWhenFileCannotBeCreated() {
        val scope = TestScope(StandardTestDispatcher())
        val diagnostics = TransmissionDiagnostics(now = { 0L })
        val recorder = TransmissionLogRecorder(scope, diagnostics.snapshot)
        val message = recorder.start(FakeDocuments(null), "log.csv", 0L)
        assertTrue(message.startsWith("Gagal"))
        assertNull(recorder.recording.value)
    }
}
