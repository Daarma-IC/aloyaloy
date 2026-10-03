package id.nusamesh.app.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSObject
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@OptIn(ExperimentalForeignApi::class)
class IosLocationAccess : NSObject(), CLLocationManagerDelegateProtocol, LocationAccessActions {
    private val manager = CLLocationManager()
    private val mutableState = MutableStateFlow(LocationAccessState.Checking)
    private val mutableLocation = MutableStateFlow<DeviceLocation?>(null)
    override val state: StateFlow<LocationAccessState> = mutableState
    override val location: StateFlow<DeviceLocation?> = mutableLocation

    init {
        manager.delegate = this
        manager.desiredAccuracy = 10.0
        manager.distanceFilter = 10.0
        refresh()
    }

    override fun requestPermission() = manager.requestWhenInUseAuthorization()

    override fun openLocationSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
            UIApplication.sharedApplication.openURL(it)
        }
    }

    override fun refresh() {
        val authorized = manager.authorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse ||
            manager.authorizationStatus == kCLAuthorizationStatusAuthorizedAlways
        mutableState.value = when {
            !authorized -> LocationAccessState.PermissionRequired
            !CLLocationManager.locationServicesEnabled() -> LocationAccessState.ServiceDisabled
            else -> LocationAccessState.Ready
        }
        if (mutableState.value == LocationAccessState.Ready) manager.startUpdatingLocation()
    }

    override fun locationManager(manager: CLLocationManager, didChangeAuthorizationStatus: CLAuthorizationStatus) = refresh()

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val value = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        val (latitude, longitude) = value.coordinate.useContents { latitude to longitude }
        mutableLocation.value = DeviceLocation(
            latitude,
            longitude,
            value.horizontalAccuracy.toFloat(),
            (value.timestamp.timeIntervalSince1970 * 1_000.0).toLong(),
        )
    }
}
