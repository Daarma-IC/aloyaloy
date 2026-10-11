package id.nusamesh.app

import id.nusamesh.app.data.KeyValueStore
import id.nusamesh.app.data.MeshRepository
import id.nusamesh.app.domain.AppPage
import id.nusamesh.app.mesh.engine.BleLink

/** Dependency injection manual: semua object aplikasi dibuat di satu tempat. */
class AppContainer(
    link: BleLink,
    store: KeyValueStore,
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
    batteryLevel: () -> Int? = { null },
    roadGraph: () -> ByteArray? = { null },
) {
    private val meshRepository = MeshRepository(link, store)
    val appController = AppController(meshRepository, store, initialPage, initialConversationId, batteryLevel, roadGraph)
}
