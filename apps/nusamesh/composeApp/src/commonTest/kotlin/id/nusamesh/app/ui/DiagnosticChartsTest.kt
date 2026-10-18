package id.nusamesh.app.ui

import id.nusamesh.app.mesh.engine.*
import kotlin.test.*

class DiagnosticChartsTest {
    @Test fun mixedUnitScalesKeepNegativeSignalAndPositiveTrafficVisible() {
        val signal=qosScale(listOf(ChartPoint(0,-110.0),ChartPoint(1000,-80.0)),false)
        val traffic=qosScale(listOf(ChartPoint(0,0.0),ChartPoint(1000,4000.0)),true)
        assertTrue(signal.fraction(-110.0) < signal.fraction(-80.0))
        assertTrue(signal.fraction(-110.0) in 0f..1f)
        assertTrue(traffic.fraction(4000.0) > .99f)
        assertEquals(0f,traffic.fraction(0.0))
    }

    @Test fun flatAndMissingMeasurementsNeverDivideByZero() {
        listOf(emptyList(),listOf(ChartPoint(0,0.0)),listOf(ChartPoint(0,-90.0))).forEach { points ->
            val scale=qosScale(points,false)
            assertTrue(scale.max>scale.min)
            points.forEach { assertTrue(scale.fraction(it.value).isFinite()) }
        }
    }
    private fun event(seq:Long,time:Long,name:String,device:String="a",bytes:Int?=null,duration:Long?=null,rssi:Double?=null,snr:Double?=null,session:String="current") =
        DiagnosticEvent(seq,session,time,if(name=="RSSI_READ")DiagnosticLayer.BLE else DiagnosticLayer.LORA,name,
            deviceId=device,bytes=bytes,durationMs=duration,rssiDbm=rssi,snrDb=snr)

    @Test fun bucketsSeparateWireRatesOutcomesAndDevices() {
        val s=DiagnosticSnapshot("current",1_000,elapsedMs=30_000,events=listOf(
            event(1,2_000,"TX_ACCEPTED",bytes=100),event(2,12_000,"TX_ACCEPTED",bytes=100),
            event(3,12_000,"RX_BYTES",device="b",bytes=200),event(4,12_000,"TX_FAILED")))
        val chart=transmissionCharts(s)
        assertEquals(listOf(10.0,10.0,0.0,0.0),chart.txRate.map{it.value})
        assertEquals(20.0,chart.rxRate[1].value)
        assertEquals(1.0,chart.failed[1].value)
        assertTrue(transmissionCharts(s,"a").rxRate.isEmpty())
        assertTrue(transmissionCharts(s,"b").accepted.isEmpty())
    }

    @Test fun oldSessionsAndUnmatchedSignalAreNotPlottedAsCurrentMeasurements() {
        val s=DiagnosticSnapshot("current",1_000,elapsedMs=30_000,events=listOf(
            event(1,2_000,"TX_ACCEPTED",bytes=999,session="old"),
            event(2,3_000,"RX_META_MISMATCH",rssi=-40.0,snr=20.0),
            event(3,4_000,"NODE_HEALTH",rssi=-60.0,snr=12.0),
            event(4,5_000,"RX_META_MATCHED",rssi=-110.0,snr=-2.0),
            event(5,6_000,"RSSI_READ",rssi=-70.0),event(6,7_000,"ACK_MATCHED",duration=750)))
        val chart=transmissionCharts(s)
        assertTrue(chart.txRate.isEmpty())
        assertEquals(listOf(-110.0),chart.loraRssi.map{it.value})
        assertEquals(listOf(-2.0),chart.loraSnr.map{it.value})
        assertEquals(listOf(-70.0),chart.bleRssi.map{it.value})
        assertEquals(listOf(750.0),chart.ackRtt.map{it.value})
    }

    @Test fun trimmedOrClearedHistoryDoesNotCreateZeroTrafficBeforeAvailableData() {
        val s=DiagnosticSnapshot("current",0,elapsedMs=60_000,trimmedEvents=1,events=listOf(
            event(1,50_000,"TX_ACCEPTED",bytes=100),event(2,55_000,"LOG_CLEARED"),event(3,59_000,"RX_BYTES",bytes=100)))
        val chart=transmissionCharts(s)
        assertEquals(55_000L,chart.startMs)
        assertTrue(chart.txRate.isEmpty())
        assertTrue(chart.truncated)
        assertEquals(20.0,chart.rxRate.first().value)
    }
}
