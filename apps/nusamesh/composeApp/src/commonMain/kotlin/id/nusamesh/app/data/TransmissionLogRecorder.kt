package id.nusamesh.app.data

import id.nusamesh.app.media.AppendableFile
import id.nusamesh.app.media.DocumentActions
import id.nusamesh.app.mesh.engine.DiagnosticSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Status rekaman log yang sedang berjalan; null = tidak merekam. */
data class LogRecording(
    val fileName: String,
    val location: String,
    val startedAtMs: Long,
    val rows: Long = 0,
)

/**
 * Merekam log transmisi ke file CSV selama tombol rekam aktif. Setiap event baru (sequence lebih
 * besar dari yang sudah ditulis) langsung ditambahkan ke file, jadi rekaman tidak terkena batas
 * [id.nusamesh.app.mesh.engine.TransmissionDiagnostics.MAX_EVENTS] di memori.
 */
class TransmissionLogRecorder(
    private val scope: CoroutineScope,
    private val diagnostics: StateFlow<DiagnosticSnapshot>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _recording = MutableStateFlow<LogRecording?>(null)
    val recording: StateFlow<LogRecording?> = _recording.asStateFlow()
    private var job: Job? = null

    /** @return pesan untuk pengguna. */
    fun start(documents: DocumentActions, fileName: String, nowMs: Long): String {
        if (job != null) return "Log sudah direkam"
        val file = documents.openAppendable(fileName, "text/csv")
            ?: return "Gagal membuat file rekaman di Documents"
        if (!file.append("﻿" + DiagnosticRecords.csvHeader + "\r\n")) {
            file.close()
            return "Gagal menulis file rekaman"
        }
        // Hanya event setelah tombol rekam ditekan.
        var written = diagnostics.value.events.maxOfOrNull { it.sequence } ?: 0L
        _recording.value = LogRecording(fileName, file.location, nowMs)
        job = scope.launch(dispatcher) {
            try {
                diagnostics.collect { snapshot ->
                    val fresh = snapshot.events.filter { it.sequence > written }
                    if (fresh.isEmpty()) return@collect
                    written = fresh.maxOf { it.sequence }
                    val text = fresh.joinToString("") { DiagnosticRecords.csvRow(it) + "\r\n" }
                    if (!file.append(text)) throw WriteFailed()
                    _recording.update { it?.copy(rows = it.rows + fresh.size) }
                }
            } catch (e: WriteFailed) {
                // Penyimpanan penuh atau file dihapus: hentikan rekaman, baris yang sudah ditulis tetap ada.
                _recording.value = null
                job = null
            } finally {
                file.close()
            }
        }
        return "Mulai merekam ke ${file.location}"
    }

    /** @return pesan untuk pengguna, atau null bila tidak sedang merekam. */
    fun stop(): String? {
        val current = _recording.value ?: return null
        job?.cancel()
        job = null
        _recording.value = null
        return "Rekaman disimpan: ${current.location} (${current.rows} baris)"
    }
}

private class WriteFailed : Exception()

/** Untuk uji: menulis ke memori. */
class MemoryAppendableFile(override val location: String = "memori") : AppendableFile {
    val content = StringBuilder()
    var closed = false
        private set

    override fun append(text: String): Boolean {
        if (closed) return false
        content.append(text)
        return true
    }

    override fun close() {
        closed = true
    }
}
