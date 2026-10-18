package id.nusamesh.app.ui

import id.nusamesh.app.mesh.engine.TransferEstimate

/** Only presentation of a live forecast. Neither reaching zero nor a stale clock proves delivery. */
internal fun transferRemainingLabel(estimate: TransferEstimate?, estimatedAtMs: Long?, nowMs: Long): String {
    val remaining = estimate?.remainingMs ?: return "Menghitung sisa waktu…"
    val age = estimatedAtMs?.let { (nowMs-it).coerceAtLeast(0) } ?: return "Menghitung sisa waktu…"
    if(age > 5_000) return "Memperbarui perkiraan waktu…"
    val left = (remaining-age).coerceAtLeast(0)
    if(left == 0L) return "Menunggu hasil kirim…"
    val seconds = (left+999)/1000
    val duration = when {
        seconds >= 3600 -> "${seconds/3600} jam ${(seconds%3600)/60} mnt"
        seconds >= 60 -> "${seconds/60} mnt ${seconds%60} dtk"
        else -> "$seconds dtk"
    }
    return "Perkiraan sisa kirim: ~$duration"
}
