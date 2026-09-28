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

@Composable
fun NusaMeshApp(
    link: BleLink,
    store: KeyValueStore = remember { InMemoryKeyValueStore() },
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
    mediaActions: ChatMediaActions = UnavailableChatMediaActions,
    bluetoothPermission: BluetoothPermissionActions = ImmediateBluetoothPermission,
) {
    val container = remember(link, initialPage, initialConversationId) {
        AppContainer(link, store, initialPage, initialConversationId)
    }
    val controller = container.appController
    val state by controller.state.collectAsState()
    val mobilityLog by controller.mobilityLog.collectAsState()
    val snackbar = remember { SnackbarHostState() }

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
                    AppPage.Map -> MapScreen(state, padding)
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
