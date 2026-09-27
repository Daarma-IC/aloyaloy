package id.nusamesh.app.data

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
actual fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
