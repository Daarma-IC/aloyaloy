package id.nusamesh.app.data

import id.nusamesh.app.AppController
import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.domain.DeliveryState
import id.nusamesh.app.mesh.engine.FakeLink
import id.nusamesh.app.mesh.engine.FakeRadio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryTest {
    private class CountingFiles(val inner: InMemoryFileStore = InMemoryFileStore()) : FileStore by inner {
        var writes = 0
        override fun write(name: String, bytes: ByteArray) { writes++; inner.write(name, bytes) }
    }

    private fun text(id: String, delivery: DeliveryState = DeliveryState.Received) = ChatMessage(
        id = id, conversationId = AppController.GLOBAL_CHAT_ID, senderName = "Ayu", body = "halo $id", time = "10:00",
        outgoing = false, delivery = delivery,
    )

    private fun image(id: String, data: ByteArray) = text(id).copy(
        kind = ChatMessageKind.Image, attachmentName = "lora_1.webp", attachmentMimeType = "image/webp",
        attachmentBytes = data.size, attachmentData = data,
    )

    @Test fun messagesAndAttachmentsSurviveReopenInOrder() {
        val files = InMemoryFileStore()
        val data = ByteArray(32) { it.toByte() }
        val first = ChatHistory(files)
        first.load()
        first.sync(listOf(text("b"), image("a", data), text("c")))

        val loaded = ChatHistory(files).load()
        assertEquals(listOf("b", "a", "c"), loaded.map { it.id })
        assertContentEquals(data, loaded[1].attachmentData)
        assertEquals(ChatMessageKind.Image, loaded[1].kind)
        assertEquals("halo c", loaded[2].body)
    }

    @Test fun orderContinuesAfterReopen() {
        val files = InMemoryFileStore()
        ChatHistory(files).apply { load(); sync(listOf(text("z"))) }
        val reopened = ChatHistory(files)
        val saved = reopened.load()
        reopened.sync(saved + text("a"))
        assertEquals(listOf("z", "a"), ChatHistory(files).load().map { it.id })
    }

    @Test fun interruptedTransferIsNotRestoredAsSending() {
        val files = InMemoryFileStore()
        val partialVoice = text("v", DeliveryState.Sending).copy(kind = ChatMessageKind.Voice)
        ChatHistory(files).apply { load(); sync(listOf(text("t", DeliveryState.Sending), partialVoice)) }
        val loaded = ChatHistory(files).load().associateBy { it.id }
        assertEquals(DeliveryState.Failed, loaded.getValue("t").delivery)
        assertEquals("halo t", loaded.getValue("t").body)
        assertEquals(DeliveryState.Failed, loaded.getValue("v").delivery)
        assertEquals("Suara tidak lengkap", loaded.getValue("v").body)
    }

    @Test fun transferProgressDoesNotRewriteFiles() {
        val files = CountingFiles()
        val history = ChatHistory(files)
        history.load()
        val message = image("a", ByteArray(8))
        history.sync(listOf(message))
        val afterFirst = files.writes
        history.sync(listOf(message.copy(transferProgress = .5f)))
        assertEquals(afterFirst, files.writes)
        history.sync(listOf(message.copy(delivery = DeliveryState.Sent)))
        assertEquals(afterFirst + 1, files.writes)
    }

    @Test fun removedMessagesLoseTheirFiles() {
        val files = InMemoryFileStore()
        val history = ChatHistory(files)
        history.load()
        history.sync(listOf(image("a", ByteArray(8)), text("b")))
        history.sync(emptyList())
        assertTrue(files.names().isEmpty())
    }

    @Test fun corruptFileIsSkippedAndOrphanAttachmentRemoved() {
        val files = InMemoryFileStore()
        ChatHistory(files).apply { load(); sync(listOf(text("a"))) }
        files.write("m-rusak", "{bukan json".encodeToByteArray())
        files.write("a-yatim", ByteArray(4))
        assertEquals(listOf("a"), ChatHistory(files).load().map { it.id })
        assertNull(files.read("a-yatim"))
    }

    private class StoreWithFiles(override val files: FileStore, base: KeyValueStore = InMemoryKeyValueStore()) : KeyValueStore by base

    private fun TestScope.controller(files: FileStore): AppController {
        val store = StoreWithFiles(files).apply { put("peer_id", "0a0a0a0a0a0a0a0a") }
        val repository = MeshRepository(FakeLink(FakeRadio(), "Ayu"), store, backgroundScope)
        return AppController(repository, store, appScope = backgroundScope, historyDispatcher = StandardTestDispatcher(testScheduler))
    }

    @Test fun reopenedAppShowsHistoryUntilUserClearsIt() = runTest {
        val files = InMemoryFileStore()
        ChatHistory(files).apply { load(); sync(listOf(text("a"), image("b", ByteArray(8)))) }

        val app = controller(files)
        runCurrent()
        assertEquals(listOf("a", "b"), app.state.value.messages.map { it.id })
        val preview = app.state.value.chats.first { it.peerId == AppController.GLOBAL_CHAT_ID }
        assertEquals("Ayu: halo b", preview.message)

        app.clearGlobalHistory()
        runCurrent()
        assertTrue(app.state.value.messages.isEmpty())
        assertTrue(files.names().isEmpty())
    }

    @Test fun openingTheAppDoesNotEraseHistory() = runTest {
        val files = InMemoryFileStore()
        ChatHistory(files).apply { load(); sync(listOf(text("a"))) }
        controller(files)
        runCurrent()
        assertEquals(listOf("a"), ChatHistory(files).load().map { it.id })
    }
}
