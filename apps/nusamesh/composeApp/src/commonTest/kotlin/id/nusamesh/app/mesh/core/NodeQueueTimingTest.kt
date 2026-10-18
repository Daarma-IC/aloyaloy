package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.peerIdBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeQueueTimingTest {
    private val me=peerIdBytes("1111111111111111")
    private fun packet(type:Int=0x22,size:Int=25)=ByteArray(size).also {
        it[0]=2;it[1]=type.toByte();me.copyInto(it,16)
    }

    @Test fun forecastMatchesActualRateLimitedFinishWithoutChangingSchedulerCredit() = runTest {
        val q=NodeQueue(me,{testScheduler.currentTime})
        val forecasts=mutableListOf<Pair<Long,Long>>()
        var doneAt:Long?=null
        q.offerBatch(List(3){packet()}, {true}, onComplete={assertTrue(it);doneAt=testScheduler.currentTime},
            onEstimate={forecasts+=testScheduler.currentTime to it})
        assertEquals(3500L,forecasts.last().second)
        q.start(backgroundScope);runCurrent()
        advanceTimeBy(1000);runCurrent()
        assertEquals(2500L,forecasts.last().second)
        advanceTimeBy(3000);runCurrent()
        // Repeated fractional token refills can round up by one millisecond.
        assertTrue(kotlin.math.abs(assertNotNull(doneAt)-forecasts.first().second)<=1)
    }

    @Test fun forecastRecalculatesWhenSfChangesDuringWaiting() = runTest {
        var sf=7
        val q=NodeQueue(me,{testScheduler.currentTime},{sf})
        var remaining=0L
        q.offerBatch(List(8){packet(size=243)}, {true}, onEstimate={remaining=it})
        q.start(backgroundScope);runCurrent()
        val before=remaining
        sf=12
        advanceTimeBy(500);runCurrent()
        assertTrue(remaining>before*5,"SF12 forecast should reflect slower radio pacing: $before -> $remaining")
    }

    @Test fun higherPriorityTrafficAheadOfVoiceIncreasesItsForecast() = runTest {
        val q=NodeQueue(me,{testScheduler.currentTime})
        var remaining=0L
        q.offerBatch(List(6){packet()}, {true}, onEstimate={remaining=it})
        q.start(backgroundScope);runCurrent()
        val before=remaining
        repeat(3) { q.offer(packet(type=0x04)){true} }
        assertTrue(remaining>before)
    }

    @Test fun closeAlsoFailsTheFragmentCurrentlyWaitingForBleCallback() = runTest {
        val q=NodeQueue(me,{testScheduler.currentTime})
        val completed=mutableListOf<Boolean>()
        q.offerBatch(listOf(packet()), {delay(10000);true}, onComplete={completed+=it})
        q.start(backgroundScope);runCurrent()
        q.close();runCurrent()
        advanceTimeBy(11000);runCurrent()
        assertEquals(listOf(false),completed)
    }
}
