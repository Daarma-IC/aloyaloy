package id.nusamesh.app.ui

import id.nusamesh.app.mesh.protocol.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinateFormatTest {
    /** Acuan dari pyproj (EPSG:4326 → EPSG:327xx/326xx), dibulatkan ke bawah ke meter. */
    private val references = listOf(
        Triple(GeoPoint(-6.175392, 106.827153), "48M", 702_178L to 9_317_060L), // Monas
        Triple(GeoPoint(-7.540780, 110.445720), "49M", 438_851L to 9_166_430L), // Merapi
        Triple(GeoPoint(-8.409518, 115.188919), "50L", 300_592L to 9_069_967L), // Bali
        Triple(GeoPoint(3.595196, 98.672226), "47N", 463_598L to 397_388L),     // Medan
        Triple(GeoPoint(-2.533, 140.717), "54M", 468_539L to 9_720_022L),       // Jayapura
        Triple(GeoPoint(-0.0263, 109.3425), "49M", 315_535L to 9_997_091L),     // Pontianak (dekat ekuator)
        Triple(GeoPoint(5.55, 95.32), "46N", 757_025L to 613_964L),             // Banda Aceh (tepi zona)
    )

    @Test
    fun utmMatchesPyprojWithinOneMeter() {
        for ((point, zoneBand, expected) in references) {
            val utm = assertNotNull(utm(point))
            assertEquals(zoneBand, "${utm.zone}${utm.band}", "zona $point")
            assertTrue(abs(utm.easting - expected.first) <= 1, "easting $point: ${utm.easting} vs ${expected.first}")
            assertTrue(abs(utm.northing - expected.second) <= 1, "northing $point: ${utm.northing} vs ${expected.second}")
        }
        assertNull(utm(GeoPoint(-85.0, 0.0)))
    }

    @Test
    fun dmsAndDecimalFormatting() {
        val merapi = GeoPoint(-7.540780, 110.445720)
        assertEquals("7°32'26.8\"S 110°26'44.6\"T", formatCoordinate(merapi, CoordinateFormat.Dms))
        assertEquals("-7.54078, 110.44572", formatCoordinate(merapi, CoordinateFormat.Decimal))
        // 59,99" harus naik jadi menit berikutnya, bukan 60.0".
        assertEquals("0°01'00.0\"U 0°00'00.0\"T", formatCoordinate(GeoPoint(0.0166664, 0.0), CoordinateFormat.Dms))
        assertEquals("49M 438851mE 9166430mN", formatCoordinate(merapi, CoordinateFormat.Utm))
    }
}
