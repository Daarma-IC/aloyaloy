package id.nusamesh.app.mesh.protocol

data class EmergencyTelemetry(
    val action: Action,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    /** Persen baterai korban; kunci JSON tambahan, diabaikan versi lama. */
    val batteryPercent: Int? = null,
) {
    enum class Action { Alert, Cancel }

    fun encode(): String = "{\"action\":\"${action.name.lowercase()}\",\"lat\":$latitude," +
        "\"lon\":$longitude,\"accuracy\":$accuracyMeters,\"timestamp\":$timestampMs" +
        (batteryPercent?.let { ",\"battery\":$it" } ?: "") + "}"

    companion object {
        private fun number(value: String, key: String) = Regex("\\\"$key\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            .find(value)?.groupValues?.get(1)

        fun decode(value: String): EmergencyTelemetry? {
            val action = when {
                value.contains(Regex("\\\"action\\\"\\s*:\\s*\\\"alert\\\"", RegexOption.IGNORE_CASE)) -> Action.Alert
                value.contains(Regex("\\\"action\\\"\\s*:\\s*\\\"cancel\\\"", RegexOption.IGNORE_CASE)) -> Action.Cancel
                else -> return null
            }
            val latitude = number(value, "lat")?.toDoubleOrNull() ?: return null
            val longitude = number(value, "lon")?.toDoubleOrNull() ?: return null
            val accuracy = number(value, "accuracy")?.toFloatOrNull() ?: return null
            val timestamp = number(value, "timestamp")?.toLongOrNull() ?: return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || accuracy < 0f) return null
            val battery = number(value, "battery")?.toIntOrNull()?.takeIf { it in 0..100 }
            return EmergencyTelemetry(action, latitude, longitude, accuracy.coerceAtMost(10_000f), timestamp, battery)
        }
    }
}
