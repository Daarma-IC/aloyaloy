package id.nusamesh.app.media

/** Menyimpan/membuka dokumen lewat pemilih file sistem (Android Storage Access Framework). */
interface DocumentActions {
    /** [onResult] menerima pesan untuk pengguna (berhasil disimpan / gagal). */
    fun saveDocument(fileName: String, mimeType: String, content: String, onResult: (String) -> Unit)
    fun openDocument(mimeTypes: List<String>, onOpened: (String) -> Unit, onError: (String) -> Unit)

    /**
     * Membuat file teks baru di folder dokumen aplikasi (Android: Documents/NusaMesh) untuk ditulis
     * bertahap, mis. rekaman log. null = platform tidak mendukung atau file gagal dibuat.
     */
    fun openAppendable(fileName: String, mimeType: String): AppendableFile? = null
}

/** File yang ditulis sedikit demi sedikit. Panggil [close] setelah selesai. */
interface AppendableFile {
    /** Lokasi yang ditampilkan ke pengguna, mis. "Documents/NusaMesh/log.csv". */
    val location: String

    /** false bila penulisan gagal (penyimpanan penuh, file dihapus, dll.). */
    fun append(text: String): Boolean

    fun close()
}

object UnavailableDocumentActions : DocumentActions {
    override fun saveDocument(fileName: String, mimeType: String, content: String, onResult: (String) -> Unit) =
        onResult("Ekspor file tersedia di aplikasi Android")

    override fun openDocument(mimeTypes: List<String>, onOpened: (String) -> Unit, onError: (String) -> Unit) =
        onError("Impor file tersedia di aplikasi Android")
}
