package id.nusamesh.app.emergency

interface EmergencyActions {
    fun setBroadcastActive(active: Boolean)
    fun showIncoming(peerId: String, name: String)
    fun clearIncoming(peerId: String)
}

object NoopEmergencyActions : EmergencyActions {
    override fun setBroadcastActive(active: Boolean) = Unit
    override fun showIncoming(peerId: String, name: String) = Unit
    override fun clearIncoming(peerId: String) = Unit
}
