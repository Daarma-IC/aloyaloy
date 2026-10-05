package id.nusamesh.app.media

/** Simpan / buka dokumen teks lewat pemilih file sistem (GPX ekspor-impor). */
interface DocumentActions {
    /** [onResult] menerima pesan untuk pengguna (berhasil disimpan / gagal). */
    fun saveDocument(fileName: String, mimeType: String, content: String, onResult: (String) -> Unit)
    fun openDocument(mimeTypes: List<String>, onOpened: (String) -> Unit, onError: (String) -> Unit)
}

object UnavailableDocumentActions : DocumentActions {
    override fun saveDocument(fileName: String, mimeType: String, content: String, onResult: (String) -> Unit) =
        onResult("Ekspor file tersedia di aplikasi Android")

    override fun openDocument(mimeTypes: List<String>, onOpened: (String) -> Unit, onError: (String) -> Unit) =
        onError("Impor file tersedia di aplikasi Android")
}
