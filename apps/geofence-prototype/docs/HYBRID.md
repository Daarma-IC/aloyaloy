# Routing gabungan jalan dan medan

Provider `Hybrid` adalah default navigasi prototype. Implementasi berada di `src/routing/hybrid.js`, dieksekusi melalui Web Worker lokal. Input mengikuti provider lama: `pack`, `from`, `to`, `profileId`, `hazards`, `hazardVersion`, `targetPositionTimestamp`. Field opsional `terrainPenaltySecondsPerM` bernilai default 3 dan harus nonnegatif.

## Graph dan constraint

- Node sel memakai grid terrain package; jaringan jalan memakai node ID, geometri, akses kaki, surface, serta arah `oneway:foot` OSM.
- Untuk setiap sel, ruas terdekat dalam 0,8 resolusi grid dicari memakai indeks spasial. Proyeksi yang lolos validasi memecah edge asli. Node jalan tidak digeser ke pusat grid. Transisi dapat terjadi pada lebih dari satu ruas dekat, bukan hanya satu kandidat nearest.
- Endpoint di luar jalan dihubungkan ke sel dan ruas dekat setelah validasi penuh; hasil mulai dan berakhir pada koordinat input. Endpoint yang tepat di jalan masuk lewat titik proyeksi ruas. Tidak ada snap dengan garis akses yang diasumsikan valid.
- Sel yang dilintasi segmen diperiksa menggunakan supercover: semua sel di sepanjang segmen, kedua sisi batas, dan empat sel di sudut diagonal. Poligon diperiksa secara geometris, sehingga obstacle lebih kecil dari resolusi raster tetap menghalangi edge.
- Elevasi bilinear disampel pada perpotongan batas dan pusat sel. Waktu dihitung secara berarah untuk setiap bagian; grade berlebihan, NoData, kelas unknown, atau mask blocked menolak edge.
- Air menolak edge medan dan transisi. Ruas OSM dengan tag bridge/tunnel dapat melintasi air, tetapi tetap tunduk pada mask blocked, NoData, dan laporan hazard. Untuk mencegah junction palsu antara permukaan dan struktur pada koordinat XY sama, transisi bridge/tunnel hanya diizinkan pada node yang juga berbagi ruas permukaan. Tujuan tepat pada ruas struktur masih dapat diakses lewat ruas tersebut.
- `pack.obstacles` opsional berisi `{id, kind, polygon, source?, osmWayId?}`. Semua polygon merupakan hambatan fisik; bangunan dapat dilewati hanya oleh ruas tunnel terpetakan. Paket Kaliurang menambahkan footprint way bangunan tertutup dari XML OSM. WorldCover kelas terbangun tidak seluruhnya diblokir, karena kelas tersebut juga mencakup ruas dan ruang terbuka.

Dijkstra mencari minimum biaya pada graph yang terbentuk. `weight = seconds + terrainPenaltySecondsPerM * distanceM` untuk medan/transisi, dan `weight = seconds` untuk ruas OSM. `seconds` adalah model berjalan Langmuir dengan friction kelas lahan atau faktor surface jalan. Penalti preferensi tidak masuk ETA. Optimalitas berlaku untuk graph diskret dan bobot tersebut, bukan semua lintasan kontinu atau keselamatan lapangan.

## Hasil dan navigasi

Output sukses: `status=available`, `engine=Hybrid`, `geometry`, `segments`, `length`, `length3d`, `durationEstimate`, `ascent`, `descent`, `elevationProfile`, `lengthsByKind`, `routingWeight`, versi paket/model/hazard, profil tim, serta peringatan sumber. Segmen menyimpan `kind=osm|terrain|transition`; segmen OSM juga menyimpan `osmWayId` dan `edgeIndex`. Durasi/jarak kumulatif digunakan panduan waypoint dan sisa ETA. Hasil gagal memiliki geometry kosong serta status `missingData`, `noTraversablePath`, atau `unavailable`.

Peta membedakan jalan biru, medan ungu putus-putus, dan transisi jingga. Awal hanya dua dot; korban harus dipilih dan diarahkan. Rute diminta bertahan setelah pindah panel/reload; navigasi aktif dijeda saat pemulihan. Hambatan baru membuat rute stale; pengguna menghitung ulang dari posisi sekarang. Pencarian penuh dijalankan kembali, belum D* Lite.

Demo Kaliurang memakai korban di luar jaringan OSM. Hasil default sekitar 2.487 m: 2.207 m ruas OSM, 120 m medan, 160 m transisi; ETA model sekitar 87 menit. Angka dapat berubah bila paket/profil/bobot berubah. Data elevasi/tutupan lahan nyata; posisi orang disimulasikan.

## Batas implementasi

Belum memakai STRIDE/LiDAR, pembelajaran ETA, Pareto risiko, atau replanning inkremental. Model friction dan preferensi merupakan asumsi terbuka, bukan hasil pengukuran tim SAR. DEM Copernicus adalah DSM 30 m; grid 35 m tidak menangkap detail langkah, tebing kecil, atau ketinggian deck jembatan/tunnel. Footprint bangunan hanya way tertutup; multipolygon, pagar, dan area akses privat belum diimpor sebagai poligon. Tidak adanya obstacle pada data bukan verifikasi keterlintasan. Sel data hilang tetap ditolak.

Paket saat ini AOI kecil, maksimal 100.000 sel; ukuran graph/RAM dan performa perangkat Android belum dibuktikan. Basemap offline hanya tile yang tercache. Server OSRM, GPS nyata, dan BLE–LoRa tetap pekerjaan terpisah. Kajian sumber dan arah lanjutan ada di [dokumen arsitektur](../../../systems/nusasurvey/docs/architecture/Kajian_Routing_Lintas_Medan.md).
