package id.nusamesh.app.ui

import id.nusamesh.app.mesh.engine.TransferEstimate
import kotlin.test.*

class TransferTimingTest {
    private fun estimate(ms:Long?)=TransferEstimate(ms,2,10,true,false)
    @Test fun forecastCountsDownAndKeepsItsEstimateLabel() {
        assertEquals("Perkiraan sisa kirim: ~1 mnt 2 dtk",transferRemainingLabel(estimate(65000),1000,4000))
        assertEquals("Perkiraan sisa kirim: ~1 dtk",transferRemainingLabel(estimate(500),1000,1000))
    }
    @Test fun unknownStaleOrExpiredForecastNeverClaimsDelivery() {
        assertEquals("Menghitung sisa waktu…",transferRemainingLabel(estimate(null),1000,1000))
        assertEquals("Memperbarui perkiraan waktu…",transferRemainingLabel(estimate(60000),1000,7000))
        assertEquals("Menunggu hasil kirim…",transferRemainingLabel(estimate(1000),1000,3000))
    }
}
