package id.nusamesh.app.data

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
actual fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun jsClock(epochMs: Double): String =
    js("new Date(epochMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })")

actual fun formatClock(epochMs: Long): String = jsClock(epochMs.toDouble())
