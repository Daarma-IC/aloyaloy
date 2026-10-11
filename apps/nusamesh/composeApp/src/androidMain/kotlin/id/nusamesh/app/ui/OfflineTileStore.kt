package id.nusamesh.app.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ubin peta offline tingkat proses, dipakai WebView peta (shouldInterceptRequest):
 *  - Cache OSM: ubin yang DILIHAT pengguna disimpan; saat offline disajikan dari cache. Tidak ada unduh
 *    massal (dilarang kebijakan tile.openstreetmap.org) — tim menjelajahi area operasi di posko sebelum
 *    berangkat.
 *  - Paket MBTiles raster yang diimpor (disiapkan posko dari sumber yang mengizinkan pemakaian offline).
 * Dipanggil dari thread IO WebView; operasi file/DB disinkronkan.
 */
object OfflineTileStore {
    private const val TAG = "NusaMeshTiles"
    private const val FRESH_MS = 7L * 24 * 60 * 60 * 1000 // hormati cache minimal 7 hari (kebijakan OSM)
    private const val MAX_CACHE_BYTES = 300L * 1024 * 1024
    private const val USER_AGENT = "NusaMesh/0.2 (aplikasi mesh tanggap darurat; Android)"
    /** Maksimal 5 tingkat (32×): lebih dari itu potongan terlalu kecil untuk berguna. */
    private const val MAX_OVERZOOM = 5
    private const val BUILT_IN_DIR = "offline-tiles"

    private lateinit var appContext: Context
    private lateinit var cacheDir: File
    private lateinit var packFile: File
    private var pack: SQLiteDatabase? = null
    /** Peta bawaan di aset APK (offline-tiles/z/x/y.png); null bila APK dibuat tanpa peta. */
    private var builtIn: OfflinePackInfo? = null
    private var userPack: OfflinePackInfo? = null
    private var writesSinceTrim = 0

    private val mutablePack = MutableStateFlow<OfflinePackInfo?>(null)
    val packInfo: StateFlow<OfflinePackInfo?> = mutablePack
    private val mutableSummary = MutableStateFlow("")
    val cacheSummary: StateFlow<String> = mutableSummary

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        cacheDir = File(appContext.filesDir, "tiles/osm").apply { mkdirs() }
        packFile = File(appContext.filesDir, "tiles/offline.mbtiles")
        builtIn = loadBuiltIn()
        if (packFile.exists()) userPack = openPack() ?: run { packFile.delete(); null }
        publishPack()
        refreshSummary()
    }

    // ------------------------------------------------------------------------------------------
    //  Cache OSM
    // ------------------------------------------------------------------------------------------
    /** Ubin OSM: cache segar → jaringan (lalu disimpan) → cache basi → null (tampil peta wilayah kasar). */
    fun osmTile(tile: TileMath.Tile): ByteArray? {
        if (!::appContext.isInitialized) return null
        val file = File(cacheDir, "${tile.z}/${tile.x}/${tile.y}.png")
        val cached = file.takeIf { it.exists() }
        if (cached != null && System.currentTimeMillis() - cached.lastModified() < FRESH_MS) return readOrNull(cached)
        if (online()) {
            fetch("https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png")?.let { bytes ->
                store(file, bytes)
                return bytes
            }
        }
        return cached?.let(::readOrNull) ?: overzoom(tile)
    }

    /**
     * Zoom lebih dalam dari yang tersimpan: perbesar potongan ubin leluhur (zoom lebih rendah) yang ada di
     * cache. Buram, tapi jalan & sungai tetap terlihat — jauh lebih berguna daripada bidang putih.
     * Hasilnya tidak disimpan supaya ubin asli tetap diunduh begitu ada internet.
     */
    private fun overzoom(tile: TileMath.Tile): ByteArray? {
        for (dz in 1..MAX_OVERZOOM) {
            val z = tile.z - dz
            if (z < 0) return null
            val parent = File(cacheDir, "$z/${tile.x shr dz}/${tile.y shr dz}.png").takeIf { it.exists() } ?: continue
            val source = BitmapFactory.decodeFile(parent.path) ?: continue
            return runCatching {
                val part = source.width shr dz
                val x = (tile.x - ((tile.x shr dz) shl dz)) * part
                val y = (tile.y - ((tile.y shr dz) shl dz)) * part
                val crop = Bitmap.createBitmap(source, x, y, part, part)
                val scaled = Bitmap.createScaledBitmap(crop, source.width, source.height, true)
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    .also { crop.recycle(); scaled.recycle() }
            }.also { source.recycle() }.getOrNull()
        }
        return null
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", MAP_BASE_URL)
        }
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { it.readBytes() }.takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.d(TAG, "Ubin gagal diambil: ${it.message}") }.getOrNull()

    @Synchronized
    private fun store(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeBytes(bytes)
            temp.renameTo(file)
        }
        if (++writesSinceTrim >= 50) {
            writesSinceTrim = 0
            trimCache()
        }
    }

    /** Buang ubin terlama bila cache melewati batas. */
    private fun trimCache() {
        val files = cacheDir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total > MAX_CACHE_BYTES) {
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE_BYTES * 9 / 10) break
                total -= file.length()
                file.delete()
            }
        }
        refreshSummary(files.size, total)
    }

    private fun refreshSummary(count: Int? = null, bytes: Long? = null) {
        val files = if (count == null) cacheDir.walkTopDown().filter { it.isFile }.toList() else null
        val n = count ?: files!!.size
        val size = bytes ?: files!!.sumOf { it.length() }
        mutableSummary.value = "$n ubin OSM tersimpan (${size / (1024 * 1024)} MB)"
    }

    /** Bila status jaringan tak bisa dibaca, anggap online: percobaan unduh gagal dengan aman (timeout). */
    private fun online(): Boolean = runCatching {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun readOrNull(file: File) = runCatching { file.readBytes() }.getOrNull()

    // ------------------------------------------------------------------------------------------
    //  Paket MBTiles
    // ------------------------------------------------------------------------------------------
    @Synchronized
    fun packTile(tile: TileMath.Tile): Pair<ByteArray, String>? {
        if (!::appContext.isInitialized) return null
        userTile(tile)?.let { return it }
        // Peta bawaan APK: dibaca langsung dari aset (tanpa salinan), tidak ada = 404 cepat.
        val info = builtIn ?: return null
        if (tile.z > info.maxZoom) return null
        return runCatching {
            appContext.assets.open("$BUILT_IN_DIR/${tile.z}/${tile.x}/${tile.y}.png").use { it.readBytes() } to "image/png"
        }.getOrNull()
    }

    private fun userTile(tile: TileMath.Tile): Pair<ByteArray, String>? {
        val db = pack ?: return null
        val info = userPack ?: return null
        val mime = TileMath.mimeOf(info.format) ?: return null
        return runCatching {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(tile.z.toString(), tile.x.toString(), TileMath.tmsRow(tile.z, tile.y).toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) to mime else null }
        }.getOrNull()
    }

    /** Salin file pilihan pengguna ke penyimpanan aplikasi, lalu validasi. Pesan hasil untuk pengguna. */
    @Synchronized
    fun importPack(copy: (File) -> Unit): String {
        val incoming = File(appContext.filesDir, "tiles/import.mbtiles")
        return try {
            incoming.parentFile?.mkdirs()
            copy(incoming)
            val info = inspect(incoming) ?: return "File bukan MBTiles raster (png/jpg/webp) yang valid".also { incoming.delete() }
            closePack()
            packFile.delete()
            incoming.renameTo(packFile)
            userPack = openPack()
            publishPack()
            "Peta offline \"${info.name}\" siap (zoom ${info.minZoom}–${info.maxZoom}, ${info.sizeBytes / (1024 * 1024)} MB)"
        } catch (e: Exception) {
            incoming.delete()
            "Gagal mengimpor peta: ${e.message}"
        }
    }

    @Synchronized
    fun removePack(): String {
        if (userPack == null) return if (builtIn != null) "Peta bawaan tidak bisa dihapus" else "Tidak ada paket peta"
        closePack()
        packFile.delete()
        userPack = null
        publishPack()
        return if (builtIn != null) "Paket impor dihapus; kembali ke peta bawaan" else "Paket peta offline dihapus"
    }

    private fun loadBuiltIn(): OfflinePackInfo? = runCatching {
        val meta = JSONObject(appContext.assets.open("$BUILT_IN_DIR/metadata.json").use { it.readBytes().decodeToString() })
        OfflinePackInfo(
            name = meta.optString("name", "Peta bawaan"),
            format = meta.optString("format", "png"),
            minZoom = meta.optInt("minzoom", 10),
            maxZoom = meta.optInt("maxzoom", 16),
            bounds = TileMath.parseBounds(meta.optString("bounds")),
            sizeBytes = 0,
            builtIn = true,
        )
    }.getOrNull()

    /**
     * Info untuk peta: satu sumber → batasnya dipakai; bawaan + impor → tanpa batas, ubin dicari di keduanya
     * (di luar keduanya dijawab 404 cepat).
     */
    private fun publishPack() {
        val user = userPack
        val base = builtIn
        mutablePack.value = when {
            user != null && base != null -> user.copy(
                name = "${user.name} + ${base.name}",
                minZoom = minOf(user.minZoom, base.minZoom),
                maxZoom = maxOf(user.maxZoom, base.maxZoom),
                bounds = null,
            )
            else -> user ?: base
        }
    }

    private fun openPack(): OfflinePackInfo? {
        val info = inspect(packFile) ?: return null
        pack = runCatching { SQLiteDatabase.openDatabase(packFile.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
        return info.takeIf { pack != null }
    }

    private fun closePack() {
        runCatching { pack?.close() }
        pack = null
    }

    private fun inspect(file: File): OfflinePackInfo? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val meta = HashMap<String, String>()
            db.rawQuery("SELECT name, value FROM metadata", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
            val format = meta["format"]?.lowercase() ?: db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { c ->
                if (c.moveToFirst()) sniffFormat(c.getBlob(0)) else null
            } ?: return@use null
            if (TileMath.mimeOf(format) == null) return@use null
            val (minZoom, maxZoom) = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) to c.getInt(1) else null
            } ?: return@use null
            OfflinePackInfo(
                name = meta["name"]?.take(40) ?: file.nameWithoutExtension,
                format = format,
                minZoom = minZoom,
                maxZoom = maxZoom,
                bounds = TileMath.parseBounds(meta["bounds"]),
                sizeBytes = file.length(),
            )
        }
    }.onFailure { Log.w(TAG, "MBTiles tidak valid: ${it.message}") }.getOrNull()

    private fun sniffFormat(bytes: ByteArray): String? = when {
        bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 12 && String(bytes, 8, 4) == "WEBP" -> "webp"
        else -> null
    }
}
