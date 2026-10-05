package id.nusamesh.app.domain

import id.nusamesh.app.mesh.protocol.MeshMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldChannelsTest {
    private val global = "global"
    private fun see(channel: String?, role: FieldRole, team: String? = null) = FieldChannels.conversationFor(channel, role, team, global)

    @Test
    fun visibilityMatrix() {
        // Global & channel asing (dari aplikasi lain) terlihat semua peran.
        for (role in FieldRole.entries) {
            assertEquals(global, see(null, role, "alfa"))
            assertEquals(global, see("#umum", role, "alfa"))
        }
        // Operasional SAR: tim & posko saja.
        assertNull(see("#sar", FieldRole.Warga))
        assertEquals("sar", see("#sar", FieldRole.Tim, "alfa"))
        assertEquals("sar", see("#sar", FieldRole.Posko))
        // Channel tim: anggota tim itu & posko saja.
        assertEquals("tim:alfa", see("#tim-alfa", FieldRole.Tim, "alfa"))
        assertNull(see("#tim-bravo", FieldRole.Tim, "alfa"))
        assertNull(see("#tim-alfa", FieldRole.Warga))
        assertEquals("tim:bravo", see("#tim-bravo", FieldRole.Posko))
        assertNull(see("#tim-", FieldRole.Posko))
    }

    @Test
    fun teamNamesNormalizeAndRoundTrip() {
        assertEquals("alfa-2", FieldChannels.teamSlug("Alfa 2"))
        assertNull(FieldChannels.teamSlug("  !!! "))
        val chat = FieldChannels.teamChatId("alfa-2")
        assertEquals("#tim-alfa-2", FieldChannels.wireChannel(chat))
        assertEquals(chat, see(FieldChannels.wireChannel(chat), FieldRole.Tim, "alfa-2"))
        assertEquals("Tim Alfa 2", FieldChannels.title(chat, "Global"))
        assertNull(FieldChannels.wireChannel(global))
    }

    @Test
    fun attachmentCarriesChannelInName() {
        val tagged = FieldChannels.tagAttachmentName("tim:alfa", "voice-1.c2")
        assertEquals("#tim-alfa~voice-1.c2", tagged)
        assertEquals("#tim-alfa" to "voice-1.c2", FieldChannels.splitAttachmentName(tagged))
        assertEquals(null to "foto~lama.jpg", FieldChannels.splitAttachmentName("foto~lama.jpg"))
        assertEquals("voice-1.c2", FieldChannels.tagAttachmentName(global, "voice-1.c2"))
    }

    @Test
    fun channelSurvivesWireFormat() {
        val message = MeshMessage(sender = "Ayu", content = "[PENTING] kumpul di posko", timestampMs = 1L, channel = "#tim-alfa")
        assertEquals("#tim-alfa", MeshMessage.decode(message.encode())?.channel)
    }
}
