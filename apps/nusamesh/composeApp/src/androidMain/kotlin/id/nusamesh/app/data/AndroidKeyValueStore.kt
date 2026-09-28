package id.nusamesh.app.data

import android.content.Context

class AndroidKeyValueStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("nusamesh", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
}
