package id.nusamesh.app.mesh.protocol

/** Triase START yang umum dipakai tim SAR/medis lapangan. */
enum class Triage(val label: String) {
    Merah("Merah · gawat darurat"),
    Kuning("Kuning · luka sedang"),
    Hijau("Hijau · luka ringan"),
    Hitam("Hitam · meninggal"),
}

enum class VictimNeed(val label: String) { Medis("medis"), Tandu("tandu"), Evakuasi("evakuasi"), Logistik("logistik") }

/**
 * Detail korban pada titik "Korban". Disisipkan di depan keterangan titik sebagai teks yang tetap
 * terbaca di versi lama: "[3 org; MERAH; perlu tandu, evakuasi] kaki patah".
 */
data class VictimInfo(
    val count: Int = 1,
    val triage: Triage? = null,
    val needs: Set<VictimNeed> = emptySet(),
    val evacuated: Boolean = false,
) {
    fun summary(): String = listOfNotNull(
        "$count org",
        triage?.name?.uppercase(),
        needs.takeIf { it.isNotEmpty() }?.let { set -> "perlu " + VictimNeed.entries.filter { it in set }.joinToString(", ") { it.label } },
        "dievakuasi".takeIf { evacuated },
    ).joinToString("; ")

    companion object {
        const val MAX_COUNT = 999
        private val bracket = Regex("""^\[([^\]]*)]\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

        fun compose(victim: VictimInfo?, label: String): String =
            victim?.let { "[${it.summary()}] $label".trim() } ?: label

        /** Pisahkan detail korban dari keterangan; bila tidak ada detail yang dikenali, label dikembalikan utuh. */
        fun parse(raw: String): Pair<VictimInfo?, String> {
            val match = bracket.find(raw) ?: return null to raw
            var count: Int? = null
            var triage: Triage? = null
            val needs = mutableSetOf<VictimNeed>()
            var evacuated = false
            for (token in match.groupValues[1].split(';').map { it.trim() }) {
                when {
                    token.endsWith(" org") -> count = token.removeSuffix(" org").trim().toIntOrNull()
                    token.startsWith("perlu ") -> token.removePrefix("perlu ").split(',').forEach { need ->
                        VictimNeed.entries.firstOrNull { it.label == need.trim() }?.let(needs::add)
                    }
                    token == "dievakuasi" -> evacuated = true
                    else -> Triage.entries.firstOrNull { it.name.uppercase() == token }?.let { triage = it }
                }
            }
            val people = count?.takeIf { it in 1..MAX_COUNT } ?: return null to raw
            return VictimInfo(people, triage, needs, evacuated) to match.groupValues[2]
        }
    }
}
