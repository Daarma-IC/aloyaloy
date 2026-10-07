package id.nusamesh.app

import id.nusamesh.app.data.KeyValueStore
import id.nusamesh.app.domain.AppPage
import id.nusamesh.app.emergency.EmergencyActions
import id.nusamesh.app.location.DeviceLocation
import id.nusamesh.app.mesh.engine.BleLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bagian aplikasi yang harus hidup selama PROSES, bukan selama layar: mesh, controller, aliran GPS ke
 * controller, foreground service, dan notifikasi SOS masuk. Di Android objek ini dipegang di tingkat
 * proses (lihat NusaMeshProcess), jadi SOS korban dan rekam jejak tetap jalan walau Activity ditutup
 * (di-swipe dari Recent) selama foreground service aktif.
 */
class AppRuntime(
    link: BleLink,
    store: KeyValueStore,
    location: StateFlow<DeviceLocation?>,
    private val emergency: EmergencyActions,
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
    batteryLevel: () -> Int? = { null },
    roadGraph: () -> ByteArray? = { null },
) {
    private val container = AppContainer(link, store, initialPage, initialConversationId, batteryLevel, roadGraph)
    val controller: AppController = container.appController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** true bila SOS atau rekam jejak sedang berjalan: mesh tidak boleh dimatikan saat layar ditutup. */
    val keepAliveInBackground: Boolean
        get() = controller.state.value.let { it.sosActive || it.recordingTrackId != null }

    init {
        scope.launch { location.collect { it?.let(controller::updateOwnLocation) } }
        scope.launch {
            controller.state.map { it.sosActive }.distinctUntilChanged().collect(emergency::setBroadcastActive)
        }
        scope.launch {
            controller.state.map { it.recordingTrackId != null }.distinctUntilChanged().collect(emergency::setTrackRecordingActive)
        }
        // Notifikasi SOS masuk juga harus muncul saat layar mati / Activity sudah ditutup.
        scope.launch {
            var known = emptySet<String>()
            controller.state
                .map { state -> state.trackedUsers.filter { it.emergency && !it.own }.associate { it.peerId to it.name } }
                .distinctUntilChanged()
                .collect { active ->
                    (active.keys - known).forEach { id -> emergency.showIncoming(id, active.getValue(id)) }
                    (known - active.keys).forEach(emergency::clearIncoming)
                    known = active.keys
                }
        }
    }
}
