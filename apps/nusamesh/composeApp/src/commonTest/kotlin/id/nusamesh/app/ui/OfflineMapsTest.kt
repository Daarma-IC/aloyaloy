package id.nusamesh.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineMapsTest {
    @Test
    fun parsesOsmAndPackTilePaths() {
        assertEquals(TileMath.Tile(15, 26123, 16801), TileMath.parse("/15/26123/16801.png"))
        assertEquals(TileMath.Tile(3, 7, 0), TileMath.parse("3/7/0"))
        assertNull(TileMath.parse("/3/8/0.png"), "x di luar 0..2^z-1")
        assertNull(TileMath.parse("/23/0/0.png"))
        assertNull(TileMath.parse("/map.js"))
        assertNull(TileMath.parse("/a/b/c.png"))
    }

    @Test
    fun mbtilesUsesTmsRows() {
        assertEquals(0, TileMath.tmsRow(0, 0))
        assertEquals(7, TileMath.tmsRow(3, 0))
        assertEquals(0, TileMath.tmsRow(3, 7))
        assertEquals(32767 - 16801, TileMath.tmsRow(15, 16801))
    }

    @Test
    fun boundsAndFormats() {
        assertEquals(listOf(106.7, -6.3, 106.9, -6.1), TileMath.parseBounds("106.7, -6.3,106.9,-6.1"))
        assertNull(TileMath.parseBounds("106.9,-6.3,106.7,-6.1"), "barat > timur")
        assertNull(TileMath.parseBounds("1,2,3"))
        assertNull(TileMath.parseBounds(null))
        assertEquals("image/jpeg", TileMath.mimeOf("JPG"))
        assertNull(TileMath.mimeOf("pbf"), "MBTiles vektor tidak didukung")
    }
}
