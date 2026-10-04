package id.nusamesh.app.mesh.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmergencyTelemetryTest {
    @Test
    fun alertAndCancelRoundTrip() {
        for (action in EmergencyTelemetry.Action.entries) {
            val value = EmergencyTelemetry(action, -7.86978, 111.404097, 6.5f, 123456789L)
            assertEquals(value, EmergencyTelemetry.decode(value.encode()))
        }
    }

    @Test
    fun rejectsInvalidOrIncompleteEmergency() {
        assertNull(EmergencyTelemetry.decode("{\"action\":\"alert\",\"lat\":99}"))
        assertNull(EmergencyTelemetry.decode("pesan biasa"))
    }
}
