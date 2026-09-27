package id.nusamesh.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import id.nusamesh.app.ble.MeshTransport
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
    transport: MeshTransport,
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
    mediaActions: ChatMediaActions = UnavailableChatMediaActions,
    bluetoothPermission: BluetoothPermissionActions = ImmediateBluetoothPermission,
) {
    val container = remember(transport, initialPage, initialConversationId) {
        AppContainer(transport, initialPage, initialConversationId)
    }
    val controller = container.appController
    val state by controller.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); controller.clearNotice() }
    }

    NusaTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (state.page != AppPage.Chats || state.activeConversationId == null) {
                    AppBottomBar(state.page, controller::navigate)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                when (state.page) {
                    AppPage.Home -> HomeScreen(
                        state = state,
                        padding = padding,
                        onConnectionClick = {
                            if (state.connection.phase == id.nusamesh.app.domain.ConnectionPhase.Connected) {
                                controller.toggleConnection()
                            } else {
                                bluetoothPermission.runWithPermission(
                                    onGranted = controller::toggleConnection,
                                    onDenied = controller::showNotice,
                                )
                            }
                        },
                        onRebootClick = controller::requestReboot,
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
                    )
                    AppPage.Map -> MapScreen(state, padding)
                }
            }
        }
    }
}
