package id.nusamesh.app.data

/** Penyimpanan kecil persisten (nama panggilan, peerID). SharedPreferences / NSUserDefaults / localStorage. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)

    /** Penyimpanan berkas untuk data besar (riwayat chat); null = platform tidak menyimpan riwayat. */
    val files: FileStore? get() = null
}

/** Berkas privat aplikasi, datar tanpa subfolder. [write] harus atomik: berkas lama utuh bila gagal. */
interface FileStore {
    fun names(): List<String>
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String) { values[key] = value }
}

class InMemoryFileStore : FileStore {
    private val entries = HashMap<String, ByteArray>()
    override fun names() = entries.keys.toList()
    override fun read(name: String) = entries[name]
    override fun write(name: String, bytes: ByteArray) { entries[name] = bytes }
    override fun delete(name: String) { entries.remove(name) }
}
