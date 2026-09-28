package id.nusamesh.app.data

import kotlinx.browser.localStorage

class WebKeyValueStore : KeyValueStore {
    override fun get(key: String): String? = runCatching { localStorage.getItem("nusamesh.$key") }.getOrNull()
    override fun put(key: String, value: String) { runCatching { localStorage.setItem("nusamesh.$key", value) } }
}
