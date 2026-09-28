package id.nusamesh.app.data

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970

actual fun currentEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()


private val clockFormatter by lazy { NSDateFormatter().apply { dateFormat = "HH:mm" } }

actual fun formatClock(epochMs: Long): String =
    clockFormatter.stringFromDate(NSDate.create(timeIntervalSince1970 = epochMs / 1000.0))
