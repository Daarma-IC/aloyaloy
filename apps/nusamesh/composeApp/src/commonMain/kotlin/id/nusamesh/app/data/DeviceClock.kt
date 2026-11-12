package id.nusamesh.app.data

/**
 * Info sumber jam perangkat untuk log (uji lapangan 12 Nov: delay antar ponsel tidak valid tanpa
 * info sinkron jam). Platform mengisi [describe]; default hanya zona waktu.
 */
object DeviceClock {
    var describe: () -> String = { "" }

    fun detail(nowMs: Long): String {
        val extra = describe().takeIf { it.isNotBlank() }?.let { "; $it" } ?: ""
        return "utc_offset_min=${localUtcOffsetMinutes(nowMs)}; device_time=${LocalTime.iso(nowMs)}$extra"
    }
}
