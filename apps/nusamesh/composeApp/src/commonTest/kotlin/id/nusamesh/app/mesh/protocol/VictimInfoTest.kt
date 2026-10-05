package id.nusamesh.app.mesh.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VictimInfoTest {
    private val full = VictimInfo(3, Triage.Merah, setOf(VictimNeed.Evakuasi, VictimNeed.Tandu), evacuated = false)

    @Test
    fun composeIsHumanReadableAndParsesBack() {
        val label = VictimInfo.compose(full, "kaki patah, tertimbun")
        // Urutan kebutuhan tetap (bukan urutan pilih) supaya teks stabil.
        assertEquals("[3 org; MERAH; perlu tandu, evakuasi] kaki patah, tertimbun", label)
        val (victim, rest) = VictimInfo.parse(label)
        assertEquals(full, victim)
        assertEquals("kaki patah, tertimbun", rest)
        assertEquals(VictimInfo(1, evacuated = true) to "", VictimInfo.parse(VictimInfo.compose(VictimInfo(evacuated = true), "")))
    }

    @Test
    fun plainBracketLabelIsNotMistakenForVictimInfo() {
        assertEquals(null to "[catatan] jalan licin", VictimInfo.parse("[catatan] jalan licin"))
        assertEquals(null to "tanpa detail", VictimInfo.parse("tanpa detail"))
    }

    @Test
    fun victimWaypointStillFitsOneLoraFrame() {
        val wp = WaypointTelemetry(
            "a1b2c3d4", WaypointType.Korban, GeoPoint(-7.54078, 110.44572),
            VictimInfo.compose(VictimInfo(12, Triage.Kuning, VictimNeed.entries.toSet(), true), "x".repeat(50)),
        )
        val encoded = wp.encode()
        assertTrue(encoded.encodeToByteArray().size <= 160, "ukuran ${encoded.length}")
        val decoded = assertNotNull(WaypointTelemetry.decode(encoded))
        val (victim, _) = VictimInfo.parse(decoded.label)
        assertEquals(12, victim?.count)
        assertTrue(victim?.evacuated == true)
        assertNull(VictimInfo.parse("[0 org] x").first, "jumlah 0 tidak sah")
    }
}
