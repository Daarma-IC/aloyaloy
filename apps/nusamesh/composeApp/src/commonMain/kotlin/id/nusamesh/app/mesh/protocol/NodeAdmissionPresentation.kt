package id.nusamesh.app.mesh.protocol

fun NodeAdmissionReport.label():String=when(state){
    AdmissionState.WAITING->"Menunggu konfirmasi antrean node"
    AdmissionState.ACCEPTED->"Diterima antrean ${if(receipt?.scope==2) "lokal" else "node"} · $confirmed/$total paket yang diajukan"
    AdmissionState.BUSY->"Node sibuk"+(detail.takeIf{it.isNotBlank()}?.let{": $it"}?:"")
    AdmissionState.REJECTED->"Ditolak node"+(detail.takeIf{it.isNotBlank()}?.let{": $it"}?:"")
    AdmissionState.UNKNOWN->"Antrean node belum terkonfirmasi"
    AdmissionState.LEGACY->"Ke transport BLE; antrean node belum terkonfirmasi"
}
fun NodeAdmissionReport.details():List<String> = buildList {
    add(label())
    if(state==AdmissionState.UNKNOWN && detail.isNotBlank())add(detail)
    receipt?.let { r ->
        add("Laporan node terakhir: ${r.freeSlots} slot bebas · SF${r.sf}")
        add("Estimasi airtime antrean ${r.queuedAirMs} ms · sisa budget ${r.budgetMs} ms")
        if(r.retryMs>0)add("Saran tunggu minimal ${(r.retryMs+999)/1000} detik; belum retry otomatis")
        if(!r.radioReady)add("Radio belum siap; periksa status hub/anggota")
    }
    add("Konfirmasi antrean bukan bukti TX RF atau pesan diterima tujuan.")
}
