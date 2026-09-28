package id.nusamesh.app.data

/** Penyimpanan kecil persisten (nama panggilan, peerID). SharedPreferences / NSUserDefaults / localStorage. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String) { values[key] = value }
}
