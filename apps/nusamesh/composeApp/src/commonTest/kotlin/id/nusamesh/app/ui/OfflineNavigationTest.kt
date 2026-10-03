package id.nusamesh.app.ui

import id.nusamesh.app.domain.TrackedUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineNavigationTest {
    private fun unit(id: String, latitude: Double, longitude: Double) = TrackedUser(
        peerId = id,
        name = id,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 5f,
        updatedAtMs = 1L,
    )

    @Test
    fun pointsEastAndGivesRightTurnFromNorth() {
        val result = guidance(unit("a", 0.0, 0.0), unit("b", 0.0, 0.001), heading = 0f)
        assertTrue(result.distanceMeters in 110.0..112.5)
        assertEquals(90, result.bearing.toInt())
        assertEquals("T", result.cardinal)
        assertTrue(result.turnInstruction.startsWith("Putar kanan"))
    }
}
