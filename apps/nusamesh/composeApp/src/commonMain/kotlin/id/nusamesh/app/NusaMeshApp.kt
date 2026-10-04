package id.nusamesh.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import id.nusamesh.app.ui.BottomBarSpace
import id.nusamesh.app.ui.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import id.nusamesh.app.data.InMemoryKeyValueStore
import id.nusamesh.app.data.KeyValueStore
import id.nusamesh.app.mesh.engine.BleLink
import id.nusamesh.app.ui.NodeSheet
import id.nusamesh.app.ble.BluetoothPermissionActions
import id.nusamesh.app.ble.ImmediateBluetoothPermission
import id.nusamesh.app.domain.AppPage
import id.nusamesh.app.media.ChatMediaActions
import id.nusamesh.app.media.UnavailableChatMediaActions
import id.nusamesh.app.ui.AppBottomBar
import id.nusamesh.app.ui.ChatScreen
import id.nusamesh.app.ui.HomeScreen
import id.nusamesh.app.ui.MapScreen
import id.nusamesh.app.ui.NusaTheme
import id.nusamesh.app.location.ImmediateLocationAccess
import id.nusamesh.app.location.LocationAccessActions
import id.nusamesh.app.location.LocationAccessState
import id.nusamesh.app.emergency.EmergencyActions
import id.nusamesh.app.emergency.NoopEmergencyActions

@Composable
fun NusaMeshApp(
    link: BleLink,
    store: KeyValueStore = remember { InMemoryKeyValueStore() },
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
    mediaActions: ChatMediaActions = UnavailableChatMediaActions,
    bluetoothPermission: BluetoothPermissionActions = ImmediateBluetoothPermission,
    locationAccess: LocationAccessActions = ImmediateLocationAccess,
    emergencyActions: EmergencyActions = NoopEmergencyActions,
) {
    val locationState by locationAccess.state.collectAsState()
    val currentLocation by locationAccess.location.collectAsState()
    val currentHeading by locationAccess.heading.collectAsState()
    LaunchedEffect(locationAccess) {
        locationAccess.refresh()
        if (locationAccess.state.value == LocationAccessState.PermissionRequired) locationAccess.requestPermission()
    }
    if (locationState != LocationAccessState.Ready) {
        LocationRequiredScreen(locationState, locationAccess)
        return
    }
    val container = remember(link, initialPage, initialConversationId) {
        AppContainer(link, store, initialPage, initialConversationId)
    }
    val controller = container.appController
    val state by controller.state.collectAsState()
    val mobilityLog by controller.mobilityLog.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var knownEmergencyIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(currentLocation) { currentLocation?.let(controller::updateOwnLocation) }
    LaunchedEffect(currentHeading) { controller.updateHeading(currentHeading) }
    LaunchedEffect(state.sosActive) { emergencyActions.setBroadcastActive(state.sosActive) }
    LaunchedEffect(state.trackedUsers) {
        val active = state.trackedUsers.filter { it.emergency && !it.own }.associateBy { it.peerId }
        (active.keys - knownEmergencyIds).forEach { id -> emergencyActions.showIncoming(id, active.getValue(id).name) }
        (knownEmergencyIds - active.keys).forEach(emergencyActions::clearIncoming)
        knownEmergencyIds = active.keys
    }

    LaunchedEffect(controller) {
        if (controller.resumeMeshOnLaunch) {
            bluetoothPermission.runWithPermission(onGranted = controller::startMesh, onDenied = controller::showNotice)
        }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); controller.clearNotice() }
    }

    NusaTheme {
        val showBar = state.page != AppPage.Chats || state.activeConversationId == null
        val hazeState = rememberHazeState()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Canvas,
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (showBar) BottomBarSpace else 0.dp)) },
        ) { insets ->
            // Bar kaca melayang di atas konten: konten boleh bergulir di belakangnya (lalu diburamkan).
            val padding = PaddingValues(
                top = insets.calculateTopPadding(),
                bottom = insets.calculateBottomPadding() + if (showBar) BottomBarSpace else 0.dp,
            )
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) { when (state.page) {
                    AppPage.Home -> HomeScreen(
                        state = state,
                        padding = padding,
                        onMeshToggle = {
                            if (state.mesh.active) controller.stopMesh()
                            else bluetoothPermission.runWithPermission(
                                onGranted = controller::startMesh,
                                onDenied = controller::showNotice,
                            )
                        },
                        onOpenNodes = controller::openNodeSheet,
                        onRename = controller::setNickname,
                        onDismissNebeng = controller::dismissNebengNotice,
                        onSosToggle = controller::toggleSos,
                    )
                    AppPage.Chats -> ChatScreen(
                        state = state,
                        padding = padding,
                        mediaActions = mediaActions,
                        onCreateGlobal = controller::createGlobalChat,
                        onOpenChat = controller::openChat,
                        onBack = controller::closeChat,
                        onSend = controller::sendMessage,
                        onSendAttachment = controller::sendAttachment,
                        onNotice = controller::showNotice,
                        onDismissNebeng = controller::dismissNebengNotice,
                    )
                    AppPage.Map -> MapScreen(state, padding, controller::selectNavigationTarget)
                } }
                if (showBar) {
                    AppBottomBar(state.page, hazeState, controller::navigate, Modifier.align(Alignment.BottomCenter))
                }
                if (state.nodeSheetOpen) {
                    var config by remember { mutableStateOf(controller.mobilityConfig) }
                    NodeSheet(
                        snapshot = state.mesh.engine,
                        mobilityConfig = config,
                        mobilityLog = mobilityLog,
                        onLock = controller::lockNode,
                        onConfigChange = { config = it; controller.mobilityConfig = it },
                        onClearLog = controller::clearMobilityLog,
                        onDismiss = controller::closeNodeSheet,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationRequiredScreen(state: LocationAccessState, actions: LocationAccessActions) {
    NusaTheme {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Lokasi wajib diaktifkan", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    when (state) {
                        LocationAccessState.Checking -> "Memeriksa layanan lokasi…"
                        LocationAccessState.PermissionRequired -> "Meshta memerlukan izin lokasi presisi agar posisi pengguna dapat dibagikan melalui jaringan penyelamatan."
                        LocationAccessState.ServiceDisabled -> "Izin sudah diberikan, tetapi GPS/layanan lokasi masih mati. Aktifkan lokasi untuk melanjutkan."
                        LocationAccessState.Ready -> "Lokasi aktif."
                    },
                    textAlign = TextAlign.Center,
                )
                if (state != LocationAccessState.Checking) {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        if (state == LocationAccessState.PermissionRequired) actions.requestPermission()
                        else actions.openLocationSettings()
                    }) { Text(if (state == LocationAccessState.PermissionRequired) "Izinkan lokasi" else "Aktifkan GPS") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = actions::refresh) { Text("Periksa lagi") }
                }
            }
        }
    }
}
