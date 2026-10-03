package id.nusamesh.app.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LocationAccessState { Checking, PermissionRequired, ServiceDisabled, Ready }

data class DeviceLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val timestampMs: Long)

interface LocationAccessActions {
    val state: StateFlow<LocationAccessState>
    val location: StateFlow<DeviceLocation?>
    fun requestPermission()
    fun openLocationSettings()
    fun refresh()
}

object ImmediateLocationAccess : LocationAccessActions {
    override val state = MutableStateFlow(LocationAccessState.Ready)
    override val location = MutableStateFlow<DeviceLocation?>(null)
    override fun requestPermission() = Unit
    override fun openLocationSettings() = Unit
    override fun refresh() = Unit
}
