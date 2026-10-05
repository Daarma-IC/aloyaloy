package id.nusamesh.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Paket peta offline (MBTiles raster) yang sudah diimpor. */
data class OfflinePackInfo(
    val name: String,
    val format: String,
    val minZoom: Int,
    val maxZoom: Int,
    /** [barat, selatan, timur, utara] atau null bila metadata tidak mencantumkan. */
    val bounds: List<Double>?,
    val sizeBytes: Long,
)

/**
 * Peta offline: (1) ubin OSM yang pernah dilihat disimpan otomatis — tidak mengunduh massal karena
 * dilarang kebijakan tile.openstreetmap.org; (2) paket MBTiles yang disiapkan posko dari sumber yang
 * mengizinkan pemakaian offline.
 */
interface OfflineMapActions {
    val pack: StateFlow<OfflinePackInfo?>
    /** Jumlah ubin OSM tersimpan & ukurannya, untuk ditampilkan. */
    val cacheSummary: StateFlow<String>
    fun importPack(onResult: (String) -> Unit)
    fun removePack(onResult: (String) -> Unit)
}

object UnavailableOfflineMaps : OfflineMapActions {
    override val pack = MutableStateFlow<OfflinePackInfo?>(null)
    override val cacheSummary = MutableStateFlow("Peta offline tersedia di aplikasi Android")
    override fun importPack(onResult: (String) -> Unit) = onResult("Impor peta offline tersedia di aplikasi Android")
    override fun removePack(onResult: (String) -> Unit) = onResult("Tidak ada paket peta")
}

object TileMath {
    data class Tile(val z: Int, val x: Int, val y: Int)

    /** "/15/26123/16801.png" (skema XYZ OSM) → Tile; null bila bukan ubin sah. */
    fun parse(path: String): Tile? {
        val parts = path.trim('/').substringBeforeLast('.').split('/')
        if (parts.size != 3) return null
        val (z, x, y) = parts.map { it.toIntOrNull() ?: return null }
        if (z !in 0..22) return null
        val max = 1 shl z
        if (x !in 0 until max || y !in 0 until max) return null
        return Tile(z, x, y)
    }

    /** MBTiles memakai baris TMS (asal di selatan), Leaflet/OSM memakai XYZ (asal di utara). */
    fun tmsRow(z: Int, y: Int) = (1 shl z) - 1 - y

    /** Metadata "bounds" MBTiles: "106.7,-6.3,106.9,-6.1" → [b, s, t, u]; null bila rusak / mustahil. */
    fun parseBounds(value: String?): List<Double>? {
        val parts = value?.split(',')?.map { it.trim().toDoubleOrNull() ?: return null } ?: return null
        if (parts.size != 4) return null
        val (w, s, e, n) = parts
        return parts.takeIf { w in -180.0..180.0 && e in -180.0..180.0 && s in -90.0..90.0 && n in -90.0..90.0 && w < e && s < n }
    }

    fun mimeOf(format: String): String? = when (format.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null // pbf (vektor) tidak didukung Leaflet raster
    }
}
