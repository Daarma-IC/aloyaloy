package id.nusamesh.app.emergency

interface EmergencyActions {
    fun setBroadcastActive(active: Boolean)
    /** Jaga GPS & mesh tetap jalan saat layar mati selama jejak direkam. */
    fun setTrackRecordingActive(active: Boolean) = Unit
    fun showIncoming(peerId: String, name: String)
    fun clearIncoming(peerId: String)
}

object NoopEmergencyActions : EmergencyActions {
    override fun setBroadcastActive(active: Boolean) = Unit
    override fun showIncoming(peerId: String, name: String) = Unit
    override fun clearIncoming(peerId: String) = Unit
}
