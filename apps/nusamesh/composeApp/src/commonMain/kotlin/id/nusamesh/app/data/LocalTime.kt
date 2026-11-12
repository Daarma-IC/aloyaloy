package id.nusamesh.app.data

import kotlin.math.abs

/** Selisih zona waktu perangkat terhadap UTC (menit) pada saat [epochMs], mis. WIB = 420. */
expect fun localUtcOffsetMinutes(epochMs: Long): Int

/** Waktu dalam zona perangkat, untuk log dan nama file yang dibaca manusia. */
object LocalTime {
    /** "2026-11-12T13:12:26.769+07:00" */
    fun iso(epochMs: Long, offsetMinutes: Int = localUtcOffsetMinutes(epochMs)): String {
        val base = GpxCodec.isoTime(epochMs + offsetMinutes * 60_000L).removeSuffix("Z")
        val millis = epochMs.mod(1000L).toString().padStart(3, '0')
        val sign = if (offsetMinutes < 0) "-" else "+"
        val minutes = abs(offsetMinutes)
        return "$base.$millis$sign${two(minutes / 60)}:${two(minutes % 60)}"
    }

    /** "2026-11-12_13-12-26", aman untuk nama file. */
    fun fileStamp(epochMs: Long, offsetMinutes: Int = localUtcOffsetMinutes(epochMs)): String =
        GpxCodec.isoTime(epochMs + offsetMinutes * 60_000L).removeSuffix("Z").replace('T', '_').replace(':', '-')

    private fun two(v: Int) = v.toString().padStart(2, '0')
}
