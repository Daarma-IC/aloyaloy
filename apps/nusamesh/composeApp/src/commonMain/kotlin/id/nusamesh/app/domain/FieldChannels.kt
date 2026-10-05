package id.nusamesh.app.domain

/** Peran pengguna di operasi: menentukan channel chat mana yang terlihat. */
enum class FieldRole(val label: String) { Warga("Warga"), Tim("Tim SAR"), Posko("Posko") }

/**
 * Channel chat operasi. Di wire memakai field `channel` MeshMessage (sudah ada di format Nusa Mesh):
 * null = global, "#sar" = operasional semua tim + posko, "#tim-<nama>" = satu tim.
 *
 * Bukan enkripsi: aplikasi lain/termodifikasi tetap bisa membaca. Tujuannya memisahkan obrolan supaya
 * warga & korban tidak tenggelam di chat tim, dan tim tidak tenggelam di chat warga.
 */
object FieldChannels {
    const val SAR_CHAT_ID = "sar"
    private const val SAR_WIRE = "#sar"
    private const val TEAM_WIRE_PREFIX = "#tim-"
    private const val TEAM_CHAT_PREFIX = "tim:"

    /** "Tim Alfa 2" → "alfa-2": huruf kecil, angka, tanda hubung; maksimal 20 karakter. */
    fun teamSlug(name: String): String? = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        .split('-').filter { it.isNotEmpty() }.joinToString("-")
        .take(20).trim('-').ifEmpty { null }

    fun teamChatId(slug: String) = TEAM_CHAT_PREFIX + slug
    fun teamSlugOf(conversationId: String) = conversationId.removePrefix(TEAM_CHAT_PREFIX).takeIf { conversationId.startsWith(TEAM_CHAT_PREFIX) }

    /** conversationId → nilai field channel di wire (null = global). */
    fun wireChannel(conversationId: String): String? = when {
        conversationId == SAR_CHAT_ID -> SAR_WIRE
        conversationId.startsWith(TEAM_CHAT_PREFIX) -> TEAM_WIRE_PREFIX + conversationId.removePrefix(TEAM_CHAT_PREFIX)
        else -> null
    }

    /**
     * channel dari wire → conversationId yang boleh dilihat peran ini; null = sembunyikan (bukan untuk kita).
     * Channel asing (bukan #sar / #tim-) diperlakukan global supaya pesan dari aplikasi lain tidak hilang.
     */
    fun conversationFor(channel: String?, role: FieldRole, mySlug: String?, globalId: String): String? = when {
        channel == SAR_WIRE -> SAR_CHAT_ID.takeIf { role != FieldRole.Warga }
        channel != null && channel.startsWith(TEAM_WIRE_PREFIX) -> {
            val slug = teamSlug(channel.removePrefix(TEAM_WIRE_PREFIX))
            when {
                slug == null -> null
                role == FieldRole.Posko || (role == FieldRole.Tim && slug == mySlug) -> teamChatId(slug)
                else -> null
            }
        }
        else -> globalId
    }

    fun title(conversationId: String, globalTitle: String): String = when {
        conversationId == SAR_CHAT_ID -> "Operasional SAR"
        conversationId.startsWith(TEAM_CHAT_PREFIX) -> "Tim " + conversationId.removePrefix(TEAM_CHAT_PREFIX)
            .split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        else -> globalTitle
    }

    /** Nama lampiran membawa channel ("#tim-alfa~voice.c2") karena paket file tidak punya field channel. */
    fun tagAttachmentName(conversationId: String, name: String) = wireChannel(conversationId)?.let { "$it~$name" } ?: name

    fun splitAttachmentName(name: String): Pair<String?, String> =
        if (name.startsWith("#") && '~' in name) name.substringBefore('~') to name.substringAfter('~') else null to name
}
