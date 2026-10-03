package id.nusamesh.app.mesh.protocol

data class LocationTelemetry(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
) {
    fun encode(): String = listOf(PREFIX, latitude, longitude, accuracyMeters, timestampMs).joinToString("|")

    companion object {
        const val PREFIX = "@meshta-location-v1"

        fun decode(value: String): LocationTelemetry? {
            val fields = value.split('|')
            if (fields.size != 5 || fields[0] != PREFIX) return null
            val latitude = fields[1].toDoubleOrNull() ?: return null
            val longitude = fields[2].toDoubleOrNull() ?: return null
            val accuracy = fields[3].toFloatOrNull() ?: return null
            val timestamp = fields[4].toLongOrNull() ?: return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || accuracy < 0f) return null
            return LocationTelemetry(latitude, longitude, accuracy.coerceAtMost(10_000f), timestamp)
        }
    }
}
