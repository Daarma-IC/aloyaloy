package id.nusamesh.app.media

@JsFun("(name, mime, text) => { const url = URL.createObjectURL(new Blob([text], {type: mime + ';charset=utf-8'})); const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 30000); }")
private external fun downloadDocument(name: String, mime: String, text: String)

object WebDocumentActions : DocumentActions {
    override fun saveDocument(fileName: String, mimeType: String, content: String, onResult: (String) -> Unit) {
        runCatching { downloadDocument(fileName, mimeType, content) }
            .onSuccess { onResult("Unduhan $fileName dimulai") }
            .onFailure { onResult("Unduhan gagal. Coba lagi melalui browser.") }
    }

    override fun openDocument(mimeTypes: List<String>, onOpened: (String) -> Unit, onError: (String) -> Unit) =
        UnavailableDocumentActions.openDocument(mimeTypes, onOpened, onError)
}
