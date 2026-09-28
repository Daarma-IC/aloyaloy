package id.nusamesh.app.data

import platform.Foundation.NSUserDefaults

class IosKeyValueStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey("nusamesh.$key")
    override fun put(key: String, value: String) = defaults.setObject(value, forKey = "nusamesh.$key")
}
