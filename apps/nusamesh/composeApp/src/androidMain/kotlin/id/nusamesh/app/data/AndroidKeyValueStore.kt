package id.nusamesh.app.data

import android.content.Context
import java.io.File

class AndroidKeyValueStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("nusamesh", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override val files: FileStore = AndroidFileStore(File(context.noBackupFilesDir, "chat-history"))
}

/** Berkas di noBackupFilesDir: chat orang lain di mesh tidak ikut terunggah ke cadangan Google. */
private class AndroidFileStore(private val dir: File) : FileStore {
    override fun names(): List<String> = dir.list()?.filterNot { it.endsWith(TEMP) }.orEmpty()

    override fun read(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        val temp = File(dir, name + TEMP)
        temp.writeBytes(bytes)
        check(temp.renameTo(File(dir, name))) { "Gagal menyimpan $name" }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    private companion object {
        const val TEMP = ".tmp"
    }
}
