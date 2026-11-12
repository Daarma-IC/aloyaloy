package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.core.nusaNodeNumber

/**
 * Pemetaan perangkat -> Nusa Node untuk log: ID node (NODE_ID firmware) dan nama node, dicari dari
 * peerID node ("4e55534e000000NN") maupun MAC BLE node. Dipakai kolom node_id/node_name di CSV,
 * supaya setiap baris log bisa dikelompokkan per node (mis. clustering di notebook).
 */
object NodeDirectory {
    data class Entry(val number: Int?, val name: String?)

    @kotlin.concurrent.Volatile
    private var byKey: Map<String, Entry> = emptyMap()

    /** [nodes] = (peerID node, nama, semua MAC BLE yang pernah dipakai node itu). */
    fun update(nodes: List<Triple<String, String?, Collection<String>>>) {
        val map = HashMap<String, Entry>()
        nodes.forEach { (peerId, name, macs) ->
            val entry = Entry(nusaNodeNumber(peerId), name)
            map[peerId.lowercase()] = entry
            macs.forEach { map[it.uppercase()] = entry }
        }
        byKey = byKey + map
    }

    /** null bila [deviceId] bukan node dan bukan MAC node yang dikenal. */
    fun resolve(deviceId: String?): Entry? {
        if (deviceId.isNullOrBlank()) return null
        byKey[deviceId.lowercase()]?.let { return it }
        byKey[deviceId.uppercase()]?.let { return it }
        return nusaNodeNumber(deviceId.lowercase())?.let { Entry(it, null) }
    }

    fun clear() {
        byKey = emptyMap()
    }
}
