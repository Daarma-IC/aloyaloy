package id.nusamesh.app.data

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

actual fun localUtcOffsetMinutes(epochMs: Long): Int {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    return (NSTimeZone.localTimeZone.secondsFromGMTForDate(date) / 60).toInt()
}
