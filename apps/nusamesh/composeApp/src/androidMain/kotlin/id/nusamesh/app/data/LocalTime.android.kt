package id.nusamesh.app.data

import java.util.TimeZone

actual fun localUtcOffsetMinutes(epochMs: Long): Int = TimeZone.getDefault().getOffset(epochMs) / 60_000
