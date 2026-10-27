package id.nusamesh.app.mesh.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeAdmissionTest {
    private fun status(session:Long,id:Long=0,result:Int=0,reason:Int=0,kind:Int=1,epoch:Long=5)=ByteArray(43).also { b ->
        b[0]=1;b[1]=kind.toByte();NodeAdmission.put64(b,2,session);NodeAdmission.put64(b,10,id)
        b[18]=result.toByte();b[19]=reason.toByte();b[20]=if(result==1)1 else 0;b[21]=1;b[23]=20;b[24]=2;b[38]=7
        for(i in 0..3)b[39+i]=(epoch ushr (24-i*8)).toByte()
    }
    private fun packet()=WireProtocol.encode(WirePacket(type=0x22,senderId=ByteArray(8),timestamp=1234,
        payload=ByteArray(80),ttl=3,voicePriority=true))
    @Test fun advertisedNodeConfirmsAdmissionWithMatchingRequestAndSession()=runTest {
        lateinit var client:NodeAdmissionClient
        client=NodeAdmissionClient(7,{client.receive(status(7,kind=0));true});client.enable()
        val bytes=packet();var writes=0
        val report=client.send(bytes){ tracked ->
            writes++;assertTrue(tracked[11].toInt() and 0x10!=0)
            assertEquals(NodeAdmission.fingerprint(bytes),NodeAdmission.fingerprint(tracked))
            client.receive(status(999,NodeAdmission.fingerprint(bytes),1)) // stale session ignored
            client.receive(status(7,NodeAdmission.fingerprint(bytes),1));true
        }
        assertEquals(AdmissionState.ACCEPTED,report.state);assertEquals(1,writes)
    }
    @Test fun lostResponseQueriesStatusButNeverRepeatsData()=runTest {
        lateinit var client:NodeAdmissionClient;var controls=0;var writes=0
        client=NodeAdmissionClient(7,{ p ->controls++
            if(p[1]==1.toByte())client.receive(status(7,kind=0))
            else client.receive(status(7,NodeAdmission.u64(p,10),1));true
        },50);client.enable()
        val report=client.send(packet()){writes++;true}
        assertEquals(AdmissionState.ACCEPTED,report.state);assertEquals(1,writes);assertEquals(2,controls)
    }
    @Test fun busyAndRejectedAreNotTransportSuccess()=runTest {
        for(code in listOf(2,3)){
            lateinit var client:NodeAdmissionClient
            client=NodeAdmissionClient(7,{client.receive(status(7,kind=0));true},50);client.enable()
            val r=client.send(packet()){p->client.receive(status(7,NodeAdmission.fingerprint(p),code,if(code==2)1 else 3));true}
            assertEquals(if(code==2)AdmissionState.BUSY else AdmissionState.REJECTED,r.state)
            assertEquals(0,r.confirmed)
        }
    }
    @Test fun oldNodeDoesNotReceiveCapabilityProbeOrTrackedFlags()=runTest {
        var probes=0;val original=packet()
        val client=NodeAdmissionClient(7,{probes++;true})
        val r=client.send(original){assertContentEquals(original,it);true}
        assertEquals(AdmissionState.LEGACY,r.state);assertEquals(0,probes)
        assertTrue(r.label().contains("belum terkonfirmasi"))
    }
    @Test fun handshakeTimeoutDoesNotSendDataAndDisconnectDoesNotFakeSuccess()=runTest {
        val silent=NodeAdmissionClient(7,{true},20);silent.enable();var writes=0
        assertEquals(AdmissionState.UNKNOWN,silent.send(packet()){writes++;true}.state);assertEquals(0,writes)
        lateinit var client:NodeAdmissionClient
        client=NodeAdmissionClient(8,{client.receive(status(8,kind=0));true},20);client.enable()
        val job=async { client.send(packet()){client.close();true} }
        assertEquals(AdmissionState.UNKNOWN,job.await().state)
    }
    @Test fun epochChangeCannotConfirmOldDataAndMalformedStatusesAreIgnored()=runTest {
        lateinit var client:NodeAdmissionClient
        client=NodeAdmissionClient(7,{client.receive(status(7,kind=0,epoch=5));true},50);client.enable()
        val r=client.send(packet()){p->client.receive(status(7,NodeAdmission.fingerprint(p),1,epoch=6));true}
        assertEquals(AdmissionState.UNKNOWN,r.state);assertEquals(8,r.receipt?.reason)
        assertNull(NodeAdmission.receipt(ByteArray(42)))
        assertNull(NodeAdmission.receipt(status(7).also{it[38]=99}))
        assertFalse(NodeAdmissionReport(AdmissionState.ACCEPTED,1,3).details().last().contains("sudah diterima tujuan"))
    }
}
