package id.nusamesh.app.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

class IosKeyValueStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey("nusamesh.$key")
    override fun put(key: String, value: String) = defaults.setObject(value, forKey = "nusamesh.$key")
    override val files: FileStore = IosFileStore()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosFileStore : FileStore {
    private val manager = NSFileManager.defaultManager
    private val dir: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        "$base/chat-history".also { manager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null) }
    }

    override fun names(): List<String> = manager.contentsOfDirectoryAtPath(dir, error = null).orEmpty().filterIsInstance<String>()

    override fun read(name: String): ByteArray? = NSData.dataWithContentsOfFile("$dir/$name")?.let { data ->
        ByteArray(data.length.toInt()).also { target ->
            if (target.isNotEmpty()) target.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile("$dir/$name", atomically = true)) { "Gagal menyimpan $name" }
    }

    override fun delete(name: String) {
        manager.removeItemAtPath("$dir/$name", error = null)
    }
}
