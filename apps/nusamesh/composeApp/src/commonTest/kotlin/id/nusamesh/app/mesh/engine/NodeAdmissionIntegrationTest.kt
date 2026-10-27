package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeAdmissionIntegrationTest {
    private val base=1700000000000L
    private class Node(private val radio:FakeRadio,private val now:()->Long):FakeRadio.Endpoint {
        override val address="N"
        override val advertisedPeerId="4e55534e00000001"
        override val advertisedName="NusaNode-1"
        override val scanning=false
        var dataCount=0;var result=1;var dropReply=false
        var last:ByteArray?=null
        init {radio.add(this)}
        override fun discovered(other:FakeRadio.Endpoint,rssi:Int){}
        override fun disconnected(other:String){}
        private fun send(to:String,payload:ByteArray,type:Int,recipient:ByteArray?=null) {
            radio.send(address,to,WireProtocol.encode(WirePacket(type=type,senderId=peerIdBytes(advertisedPeerId),
                recipientId=recipient,timestamp=now(),payload=payload,ttl=1)))
        }
        override fun connected(other:String,asCentral:Boolean){
            send(other,byteArrayOf(1,-70,16,1,3,7,0,1),0x18)
        }
        private fun receipt(session:Long,id:Long,kind:Int)=ByteArray(43).also { b ->
            b[0]=1;b[1]=kind.toByte();NodeAdmission.put64(b,2,session);NodeAdmission.put64(b,10,id)
            b[18]=if(kind==0)0 else result.toByte();b[19]=if(kind!=0&&result==2)1 else 0
            b[20]=if(kind!=0&&result==1)1 else 0;b[21]=1;b[23]=20;b[24]=2;b[38]=7;b[42]=5
        }
        var session=0L
        override fun received(from:String,data:ByteArray){
            val p=WireProtocol.decode(data)?:return
            if(p.type==0x1b){
                session=NodeAdmission.u64(p.payload,2)
                val payload=if(p.payload[1]==1.toByte())receipt(session,0,0) else last?:receipt(session,NodeAdmission.u64(p.payload,10),1)
                send(from,payload,0x1c,p.senderId);return
            }
            if(data[11].toInt() and 0x10==0)return
            if(p.type==0x22)dataCount++
            val r=receipt(session,NodeAdmission.fingerprint(data),1);last=r
            if(!dropReply)send(from,r,0x1c,p.senderId)
        }
    }
    @Test fun nodeReceiptDrivesCompletionAndLostReceiptUsesQuery()=runTest {
        val radio=FakeRadio();val node=Node(radio){base+testScheduler.currentTime}
        val engine=MeshEngine(FakeLink(radio,"A"),"0a0a0a0a0a0a0a0a",backgroundScope,
            {base+testScheduler.currentTime},Random(2))
        val events=mutableListOf<NodeAdmissionEvent>()
        backgroundScope.launch {engine.admissions.collect{events+=it}}
        engine.start();radio.setRssi("A","N",-55)
        backgroundScope.launch {while(isActive){radio.scanTick();delay(1000)}}
        advanceTimeBy(5000);runCurrent()
        var completed:Boolean?=null
        node.dropReply=true
        engine.sendFile(FilePacket("voice.c2","audio/x-codec2",ByteArray(80)),onComplete={completed=it})
        advanceTimeBy(1000);runCurrent();assertNull(completed)
        advanceTimeBy(10000);runCurrent()
        assertEquals(true,completed)
        assertTrue(events.any{it.messageId!=null&&it.report.state==AdmissionState.ACCEPTED})
        val before=node.dataCount
        node.result=2;node.dropReply=false;completed=null
        engine.sendFile(FilePacket("voice2.c2","audio/x-codec2",ByteArray(90)),onComplete={completed=it})
        advanceTimeBy(15000);runCurrent()
        assertEquals(false,completed)
        assertTrue(events.any{it.report.state==AdmissionState.BUSY})
        assertEquals(before+1,node.dataCount)
    }
}
