package id.nusamesh.app.data

import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.domain.DeliveryPath
import id.nusamesh.app.domain.DeliveryState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Riwayat chat di [FileStore]: satu file data per pesan ("m-…") dan satu file per lampiran ("a-…"), jadi
 * perubahan status pesan tidak menulis ulang seluruh riwayat.
 *
 * Tidak thread-safe: [load] lalu [sync] harus dipanggil berurutan dari satu jalur.
 */
class ChatHistory(private val files: FileStore) {
    private val seen = HashMap<String, ChatMessage>()
    private val savedMeta = HashMap<String, String>()
    private val savedData = HashMap<String, ByteArray>()
    private val sequence = HashMap<String, Long>()
    private var nextSequence = 0L

    /** Baca semua pesan tersimpan, urut seperti saat diterima. File rusak dilewati. */
    fun load(): List<ChatMessage> {
        val names = files.names()
        val entries = names.filter { it.startsWith(META) }.mapNotNull { name ->
            val text = files.read(name)?.decodeToString() ?: return@mapNotNull null
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@mapNotNull null
            val (order, message) = decode(json) ?: return@mapNotNull null
            Triple(order, message, text)
        }.sortedBy { it.first }
        val known = entries.mapTo(HashSet()) { dataName(it.second.id) }
        names.filter { it.startsWith(DATA) && it !in known }.forEach(files::delete)
        return entries.map { (order, stored, text) ->
            val data = files.read(dataName(stored.id))
            sequence[stored.id] = order
            savedMeta[stored.id] = text
            if (data != null) savedData[stored.id] = data
            nextSequence = maxOf(nextSequence, order + 1)
            restored(stored.copy(attachmentData = data)).also { seen[it.id] = it }
        }
    }

    /** Samakan penyimpanan dengan [messages]: tulis yang baru/berubah, hapus yang sudah tidak ada. */
    fun sync(messages: List<ChatMessage>) {
        val ids = HashSet<String>(messages.size)
        messages.forEach { message ->
            ids += message.id
            if (seen[message.id] !== message) {
                write(message)
                seen[message.id] = message
            }
        }
        (seen.keys - ids).toList().forEach(::remove)
    }

    private fun write(message: ChatMessage) {
        val data = message.attachmentData
        if (data != null && savedData[message.id] !== data) {
            files.write(dataName(message.id), data)
            savedData[message.id] = data
        }
        val order = sequence.getOrPut(message.id) { nextSequence++ }
        val meta = encode(order, message)
        if (savedMeta[message.id] != meta) {
            files.write(metaName(message.id), meta.encodeToByteArray())
            savedMeta[message.id] = meta
        }
    }

    private fun remove(id: String) {
        files.delete(metaName(id))
        files.delete(dataName(id))
        seen.remove(id)
        savedMeta.remove(id)
        savedData.remove(id)
        sequence.remove(id)
    }

    /** Transfer yang terputus aplikasi ditutup tidak akan selesai: jangan tampil "mengirim" selamanya. */
    private fun restored(message: ChatMessage): ChatMessage {
        if (message.delivery != DeliveryState.Sending) return message
        val incompleteVoice = !message.outgoing && message.kind == ChatMessageKind.Voice && message.attachmentData == null
        return message.copy(delivery = DeliveryState.Failed, body = if (incompleteVoice) "Suara tidak lengkap" else message.body)
    }

    private fun encode(order: Long, m: ChatMessage): String = buildJsonObject {
        put("seq", order)
        put("id", m.id)
        put("conversation", m.conversationId)
        put("sender", m.senderName)
        put("body", m.body)
        put("time", m.time)
        put("outgoing", m.outgoing)
        put("kind", m.kind.name)
        put("delivery", m.delivery.name)
        put("name", m.attachmentName)
        put("bytes", m.attachmentBytes)
        put("mime", m.attachmentMimeType)
        put("duration", m.durationSeconds)
        put("path", m.path.name)
        put("via", m.viaName)
        put("ackedAt", m.acknowledgedAtMs)
        put("ackedBy", buildJsonArray { m.ackedBy.forEach { add(JsonPrimitive(it)) } })
        put("verified", m.verified)
    }.toString()

    private fun decode(json: JsonObject): Pair<Long, ChatMessage>? = runCatching {
        fun text(key: String) = json[key]?.jsonPrimitive?.contentOrNull
        val message = ChatMessage(
            id = text("id")!!,
            conversationId = text("conversation")!!,
            senderName = text("sender")!!,
            body = text("body")!!,
            time = text("time")!!,
            outgoing = json["outgoing"]!!.jsonPrimitive.booleanOrNull!!,
            kind = ChatMessageKind.valueOf(text("kind")!!),
            delivery = DeliveryState.valueOf(text("delivery")!!),
            attachmentName = text("name"),
            attachmentBytes = json["bytes"]?.jsonPrimitive?.intOrNull,
            attachmentMimeType = text("mime"),
            durationSeconds = json["duration"]?.jsonPrimitive?.intOrNull,
            path = DeliveryPath.valueOf(text("path")!!),
            viaName = text("via"),
            acknowledgedAtMs = json["ackedAt"]?.jsonPrimitive?.longOrNull,
            ackedBy = (json["ackedBy"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            verified = json["verified"]?.jsonPrimitive?.booleanOrNull == true,
        )
        json["seq"]!!.jsonPrimitive.longOrNull!! to message
    }.getOrNull()

    private fun metaName(id: String) = META + hex(id)
    private fun dataName(id: String) = DATA + hex(id)
    private fun hex(id: String) = id.encodeToByteArray().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val META = "m-"
        const val DATA = "a-"
    }
}
