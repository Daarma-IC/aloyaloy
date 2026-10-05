package id.nusamesh.app.mesh.protocol

/**
 * Status cepat tim lapangan: satu ketukan, ±25 byte (satu frame LoRa). Dikirim sebagai pesan chat biasa
 * yang tetap terbaca di versi lama ("#status:medis Butuh medis"); versi baru menampilkannya sebagai
 * status unit di peta.
 */
enum class QuickStatus(val code: String, val label: String, val urgent: Boolean) {
    Aman("aman", "Aman", false),
    Medis("medis", "Butuh medis", true),
    Korban("korban", "Korban ditemukan", true),
    Bantuan("bantuan", "Butuh bantuan tim", true),
    Logistik("logistik", "Butuh logistik", false),
    Mundur("mundur", "Mundur ke posko", false);

    fun encode() = "$PREFIX$code $label"

    companion object {
        const val PREFIX = "#status:"

        fun decode(content: String): QuickStatus? {
            if (!content.startsWith(PREFIX)) return null
            val code = content.removePrefix(PREFIX).substringBefore(' ')
            return entries.firstOrNull { it.code == code }
        }

        /** Teks tampilan untuk chat & pratinjau: "Status: Butuh medis" alih-alih kode mentah. */
        fun display(content: String) = decode(content)?.let { "Status: ${it.label}" } ?: content
    }
}

/**
 * Pesan chat penting (mis. perintah posko): diberi awalan yang tetap terbaca di versi lama, dan penerima
 * versi baru membalas konfirmasi terima (DELIVERY_ACK).
 */
object ImportantMessage {
    const val PREFIX = "[PENTING] "

    fun isImportant(content: String) = content.startsWith(PREFIX)

    /** Pesan chat yang dibalas konfirmasi terima: pesan penting & status mendesak. */
    fun wantsAck(content: String) = isImportant(content) || QuickStatus.decode(content)?.urgent == true
}
