package id.nusamesh.app.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Folder dokumen aplikasi: Documents/NusaMesh di penyimpanan bersama HP, jadi file terlihat di
 * aplikasi File/Files tanpa izin penyimpanan (Android 10+, lewat MediaStore). Android 8-9 tidak
 * mengizinkan itu tanpa izin, jadi memakai folder dokumen milik aplikasi
 * (Android/data/id.nusamesh.app/files/Documents/NusaMesh).
 */
object DocumentsFolder {
    const val FOLDER = "NusaMesh"

    fun openAppendable(context: Context, fileName: String, mimeType: String): AppendableFile? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) shared(context, fileName, mimeType)
            else null
        }.getOrNull() ?: runCatching { private(context, fileName) }.getOrNull()

    private fun shared(context: Context, fileName: String, mimeType: String): AppendableFile? {
        val relative = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        val stream = resolver.openOutputStream(uri, "wa") ?: return null
        return StreamFile("$relative/$fileName", stream)
    }

    private fun private(context: Context, fileName: String): AppendableFile {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val dir = File(base, FOLDER).apply { mkdirs() }
        val file = File(dir, fileName)
        return StreamFile(file.absolutePath, FileOutputStream(file, true))
    }

    private class StreamFile(override val location: String, private val stream: OutputStream) : AppendableFile {
        private var closed = false

        @Synchronized
        override fun append(text: String): Boolean {
            if (closed) return false
            return runCatching {
                stream.write(text.encodeToByteArray())
                stream.flush()
            }.isSuccess
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            runCatching { stream.close() }
        }
    }
}
