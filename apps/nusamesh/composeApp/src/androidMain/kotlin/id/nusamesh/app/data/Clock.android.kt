package id.nusamesh.app.data

actual fun currentEpochMillis(): Long = System.currentTimeMillis()


actual fun formatClock(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
