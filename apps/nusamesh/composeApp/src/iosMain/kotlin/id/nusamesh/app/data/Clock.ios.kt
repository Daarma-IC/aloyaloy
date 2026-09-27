package id.nusamesh.app.data

import platform.Foundation.NSDate

actual fun currentEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

