package id.nusamesh.app

import id.nusamesh.app.ble.MeshTransport
import id.nusamesh.app.data.MeshRepository
import id.nusamesh.app.domain.AppPage

/** Dependency injection manual: semua object aplikasi dibuat di satu tempat. */
class AppContainer(
    transport: MeshTransport,
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
) {
    private val meshRepository = MeshRepository(transport)
    val appController = AppController(meshRepository, initialPage, initialConversationId)
}
