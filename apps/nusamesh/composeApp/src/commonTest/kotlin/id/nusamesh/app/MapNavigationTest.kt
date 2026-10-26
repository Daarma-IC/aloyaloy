package id.nusamesh.app

import id.nusamesh.app.data.InMemoryKeyValueStore
import id.nusamesh.app.data.MeshRepository
import id.nusamesh.app.data.currentEpochMillis
import id.nusamesh.app.location.DeviceLocation
import id.nusamesh.app.mesh.engine.FakeLink
import id.nusamesh.app.mesh.engine.FakeRadio
import id.nusamesh.app.mesh.protocol.GeoPoint
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MapNavigationTest {
    private fun TestScope.controller(): AppController {
        val store = InMemoryKeyValueStore()
        val repository = MeshRepository(FakeLink(FakeRadio(), "phone"), store, backgroundScope)
        return AppController(repository, store, appScope = backgroundScope)
    }

    @Test
    fun popupPointStartsRoutingAndReplacesDrawingDestination() = runTest {
        val app = controller()
        app.updateOwnLocation(DeviceLocation(-6.75, 107.6, 5f, currentEpochMillis()))
        app.startRouteDraft()
        app.onMapTap(GeoPoint(-6.76, 107.61))
        app.receiveSarNavigation("""{"action":"select","kind":"point","latitude":-6.77,"longitude":107.62}""")
        val request = assertNotNull(app.state.value.sarNavigation.request)
        assertEquals(GeoPoint(-6.75, 107.6), request.from)
        assertEquals(GeoPoint(-6.77, 107.62), request.to)
        assertEquals("CALCULATING", app.state.value.sarNavigation.status)
        assertNull(app.state.value.routeDraft)
        app.receiveSarNavigation("""{"action":"select","kind":"point","latitude":-6.78,"longitude":107.63}""")
        assertEquals(GeoPoint(-6.78, 107.63), app.state.value.sarNavigation.request?.to)
        app.receiveSarNavigation("""{"id":"${request.id}","result":{"status":"missingData","message":"Old result"}}""")
        assertEquals("CALCULATING", app.state.value.sarNavigation.status)
        assertEquals(GeoPoint(-6.78, 107.63), app.state.value.sarNavigation.request?.to)
    }

    @Test
    fun invalidPopupCoordinatesLeavePickedPointAlone() = runTest {
        val app = controller()
        val picked = GeoPoint(-6.77, 107.62)
        app.onMapTap(picked)
        for (latitude in listOf("91", "null", "\"invalid\"")) {
            app.receiveSarNavigation("""{"action":"select","kind":"point","latitude":$latitude,"longitude":107.62}""")
            assertEquals(picked, app.state.value.pickedPoint)
            assertNull(app.state.value.sarNavigation.request)
        }
    }

    @Test
    fun popupPointWithoutOwnGpsExplainsMissingStart() = runTest {
        val app = controller()
        app.receiveSarNavigation("""{"action":"select","kind":"point","latitude":-6.77,"longitude":107.62}""")
        assertEquals("UNAVAILABLE", app.state.value.sarNavigation.status)
        assertEquals("Menunggu GPS untuk awal navigasi", app.state.value.sarNavigation.message)
        assertNull(app.state.value.sarNavigation.request)
    }
}
