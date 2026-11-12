package id.nusamesh.app.data

private fun jsOffsetMinutes(epochMs: Double): Int = js("-new Date(epochMs).getTimezoneOffset()")

actual fun localUtcOffsetMinutes(epochMs: Long): Int = jsOffsetMinutes(epochMs.toDouble())
