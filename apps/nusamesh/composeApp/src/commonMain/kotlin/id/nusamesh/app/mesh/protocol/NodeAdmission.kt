package id.nusamesh.app.mesh.protocol

import id.nusamesh.app.mesh.core.NodeQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

enum class AdmissionState { WAITING, ACCEPTED, BUSY, REJECTED, UNKNOWN, LEGACY }
data class NodeAdmissionReceipt(val kind:Int,val session:Long,val request:Long,val result:Int,val reason:Int,
    val scope:Int,val radioReady:Boolean,val freeSlots:Int,val maxPacket:Int,val queuedAirMs:Long,
    val budgetMs:Int,val retryMs:Long,val sf:Int,val epoch:Long)
data class NodeAdmissionReport(val state:AdmissionState,val confirmed:Int,val total:Int,
    val receipt:NodeAdmissionReceipt?=null,val detail:String="")
data class NodeAdmissionEvent(val messageId:String?,val report:NodeAdmissionReport)

object NodeAdmission {
    const val CONTROL=0x1b
    const val STATUS=0x1c
    const val TRACK=0x10
    fun u64(b:ByteArray,o:Int)= (0..7).fold(0L){n,i->(n shl 8) or (b[o+i].toLong() and 255)}
    fun put64(b:ByteArray,o:Int,n:Long){for(i in 0..7)b[o+i]=(n ushr (56-i*8)).toByte()}
    fun command(session:Long,request:Long=0,query:Boolean=false)=ByteArray(18).also {
        it[0]=1;it[1]=if(query)2 else 1;put64(it,2,session);put64(it,10,request)
    }
    fun fingerprint(bytes:ByteArray):Long {
        val length=NodeQueue.unpaddedLength(bytes)
        var h=-0x340d631b7bdddcdbL
        for(i in 0 until length){var b=if(i==2)0 else bytes[i].toInt() and 255
            if(i==11)b=b and TRACK.inv();h=(h xor b.toLong())*0x100000001b3L}
        return h
    }
    fun receipt(b:ByteArray):NodeAdmissionReceipt? {
        if(b.size!=43||b[0]!=1.toByte())return null
        fun u8(o:Int)=b[o].toInt() and 255
        fun u16(o:Int)=(u8(o) shl 8) or u8(o+1)
        fun u32(o:Int)=(0..3).fold(0L){n,i->(n shl 8) or u8(o+i).toLong()}
        if(u8(1)>1||u8(18)>3||u8(19)>10||u8(20)>2||u8(38) !in 7..12||u16(24) !in 1..512)return null
        if((u8(18)==1 && u8(20)==0) || (u8(1)==0 && (u8(18)!=0 || u64(b,10)!=0L)))return null
        return NodeAdmissionReceipt(u8(1),u64(b,2),u64(b,10),u8(18),u8(19),u8(20),
            u8(21) and 1!=0,u16(22),u16(24),u32(26),u32(30).toInt(),u32(34),u8(38),u32(39))
    }
    fun eligible(bytes:ByteArray):Boolean = bytes.size>=24 && (bytes[1].toInt() and 255) !in setOf(0x18,0x19,0x1a,CONTROL,STATUS,0x40,0x41)
    fun reason(code:Int)=when(code){
        0->"";1->"Batas laju HP";2->"Antrean radio penuh";3->"Paket terlalu besar";4->"Format paket tidak valid"
        5->"Duplikat; hasil admission sebelumnya tidak diketahui";6->"Antrean pengiriman lokal belum menerima"
        7->"Jalur penerimaan tidak terkonfirmasi";8->"Sesi node berubah";9->"Catatan permintaan tidak tersedia"
        10->"Tabel konfirmasi node penuh";else->"Hasil tidak diketahui"
    }
}

/** P1 only: await admission; query missing responses without ever repeating DATA. */
class NodeAdmissionClient(private val session:Long,private val control:suspend(ByteArray)->Boolean,
                          private val timeoutMs:Long=2000) {
    private var supported=false
    private var ready=false
    private var closed=false
    private var epoch:Long?=null
    private val pending=mutableMapOf<Long,CompletableDeferred<NodeAdmissionReceipt?>>()
    private suspend fun safe(block:suspend()->Boolean):Boolean=try{block()}catch(e:CancellationException){throw e}catch(_:Exception){false}
    fun enable(){supported=true}
    fun receive(payload:ByteArray){
        val r=NodeAdmission.receipt(payload)?:return
        if(r.session!=session)return
        val waiting=pending[r.request]?:return
        if(r.kind==0&&r.request!=0L)return
        if(r.kind==1&&epoch!=null&&r.epoch!=epoch){ready=false;waiting.complete(r.copy(result=0,reason=8));return}
        if(r.kind==0){ready=true;epoch=r.epoch}
        waiting.complete(r)
    }
    fun close(){closed=true;ready=false;pending.values.forEach{it.complete(null)};pending.clear()}
    suspend fun send(bytes:ByteArray,write:suspend(ByteArray)->Boolean):NodeAdmissionReport {
        if(closed)return NodeAdmissionReport(AdmissionState.UNKNOWN,0,1,detail="Koneksi node terputus")
        if(!supported||!NodeAdmission.eligible(bytes))return NodeAdmissionReport(
            if(safe{write(bytes)})AdmissionState.LEGACY else AdmissionState.UNKNOWN,0,1,detail="Hanya hasil transport; node belum mengonfirmasi antrean")
        if(!ready){
            val hello=CompletableDeferred<NodeAdmissionReceipt?>();pending[0]=hello
            try {
                safe{control(NodeAdmission.command(session))}
                if(withTimeoutOrNull(timeoutMs){hello.await()}==null || !ready)
                    return NodeAdmissionReport(AdmissionState.UNKNOWN,0,1,detail="Handshake admission belum terkonfirmasi; data belum dikirim")
            }finally{pending.remove(0)}
        }
        val id=NodeAdmission.fingerprint(bytes)
        val answer=CompletableDeferred<NodeAdmissionReceipt?>();pending[id]=answer
        try {
            val tracked=bytes.copyOf();tracked[11]=(tracked[11].toInt() or NodeAdmission.TRACK).toByte()
            safe{write(tracked)} // A lost GATT acknowledgement requires a query, never a data replay.
            var receipt=withTimeoutOrNull(timeoutMs){answer.await()}
            if(receipt==null&&!closed){
                safe{control(NodeAdmission.command(session,id,query=true))}
                receipt=withTimeoutOrNull(timeoutMs){answer.await()}
            }
            val state=when(receipt?.result){1->AdmissionState.ACCEPTED;2->AdmissionState.BUSY;3->AdmissionState.REJECTED;else->AdmissionState.UNKNOWN}
            return NodeAdmissionReport(state,if(state==AdmissionState.ACCEPTED)1 else 0,1,receipt,
                receipt?.let{NodeAdmission.reason(it.reason)}?:"Node belum mengonfirmasi; data tidak dikirim ulang otomatis")
        }finally{pending.remove(id)}
    }
}
