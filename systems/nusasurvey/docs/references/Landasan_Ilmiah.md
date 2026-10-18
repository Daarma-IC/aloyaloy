# Landasan ilmiah dan daftar pustaka terpilih

Penelusuran/pembaruan: 14 Oktober 2026. Prioritas jurnal 2023–2026; buku dan algoritma klasik tetap digunakan untuk teori dasar. Ringkasan menyatakan relevansi dan batas penerapan. Metadata terpilih tersedia pada [nusasurvey.bib](nusasurvey.bib). Dokumen teknis/dataset bukan jurnal dan tidak boleh diperlakukan sebagai bukti eksperimen manusia.

## 1. Jurnal fondasi utama

### J1 — STRIDE: model perjalanan manusia, 2024

Campbell, M. J., Cutler, S. L., & Dennison, P. E. (2024). *A singular, broadly-applicable model for estimating on- and off-path walking travel rates using airborne lidar data*. Scientific Reports, 14, 21393. DOI: **10.1038/s41598-024-71359-6**. [Artikel](https://www.nature.com/articles/s41598-024-71359-6).

**Landasan:** kecepatan berjalan dipengaruhi slope, kepadatan vegetasi, dan kekasaran permukaan. **Pemetaan:** pengembangan model waktu medan/ETA manusia. **Batas:** memerlukan masukan LiDAR; kelas WorldCover tidak setara dengan kepadatan vegetasi atau roughness meteran. Tidak membuktikan rute bebas jurang. **Status NusaSurvey:** dipelajari sebagai arah model; STRIDE belum diimplementasikan. Artikel lengkap berhasil dibaca pada penelusuran sebelumnya; sebagian akses ulang mengalami redirect/error.

### J2 — STEP: risiko medan dan ketidakpastian, jurnal 2024

Dixit, A., Fan, D. D., Otsu, K., Dey, S., Agha-Mohammadi, A.-A., & Burdick, J. W. (2024). *STEP: Stochastic Traversability Evaluation and Planning for Risk-Aware Off-road Navigation; Results from the DARPA Subterranean Challenge*. Field Robotics, 4, 182–210. DOI: **10.55417/fr.2024006**. [Metadata penulis](https://arxiv.org/abs/2303.01614), [naskah lengkap](https://arxiv.org/html/2303.01614v1).

**Landasan:** traversability, risiko geometris, negative obstacles, ketidakpastian pengamatan/posisi, dan CVaR. **Pemetaan:** layer bahaya serta perencanaan konservatif. **Batas:** sistem robot memakai sensor/perception dan dinamika yang berbeda dari manusia; celah pengamatan tidak langsung dianggap sebagai jurang tanpa pemeriksaan coverage. **Status:** landasan rancangan, belum ada CVaR/sensor negative-obstacle pada prototype. Versi arXiv mula-mula 2023; tahun sitasi jurnal yang terverifikasi adalah 2024.

### J3 — Gerak petugas SAR empiris, 2026

Hashimoto, A., Aung, E., Koester, R., & Abaid, N. (2026). *Speed, slope, and synchrony: Empirical insights into SAR searcher behavior*. PLOS One, 21(6), e0339541. Terbit 15 Juni 2026. DOI: **10.1371/journal.pone.0339541**. [Artikel lengkap](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0339541).

**Landasan:** analisis GPS petugas pada 61 tracks dari 13 kasus yang diseleksi dari 64 insiden; jenis pencarian dan koordinasi memengaruhi model gerak. **Pemetaan:** kalibrasi profil tim, ETA, dan evaluasi penyisiran. **Batas:** kecepatan dalam studi bukan konstanta SAR Indonesia. Studi tidak menemukan perbedaan statistik median uphill/downhill pada dataset tersebut; ini alasan membandingkan asumsi model dengan data, bukan menganggap formula arah prototype sudah benar universal. **Status:** kandidat utama validasi manusia; koefisien studi belum dimasukkan ke runtime.

## 2. Jurnal pendukung komunikasi dan tracking

### J4 — Tracking SAR Indonesia melalui LoRa, 2024

Andrianto, A. Ari (2024). *Implementation of Internet of Things (IoT) in Tracking the Position of Search and Rescue Teams in Sukoharjo District Using LoRa*. SMATIKA Jurnal, 14(1), 86–95. DOI halaman penerbit: **10.32664/smatika.v14i01.1222**. [Artikel](https://jurnal.ubhinus.ac.id/SMATIKA/article/view/1222).

**Landasan:** prototyping posisi tim SAR melalui IoT/LoRa. **Pemetaan:** pertukaran lokasi dan tampilan posko. **Batas:** arsitektur pada catatan studi memakai gateway/server; tidak menjadi bukti routing medan, mesh multihop offline, maupun deteksi jurang. **Status:** referensi kebutuhan transport; bukan protokol yang sudah di-port ke NusaNode. Catatan awal menemukan perbedaan DOI kepala PDF dan halaman artikel; BibTeX mengikuti halaman penerbit.

### J5 — Propagasi LoRa manusia–UAV di hutan, 2023

Bianco, G. M., & Marrocco, G. (2023). *Body-UAV Near-Ground LoRa Links through a Mediterranean Forest*. IEEE Transactions on Antennas and Propagation. DOI: **10.1109/TAP.2023.3260580**. [Metadata dan naskah penulis](https://arxiv.org/abs/2303.12754).

**Landasan:** pengaruh tubuh, gerakan, dan vegetasi pada link LoRa; pengukuran PDR/path loss. **Pemetaan:** rencana pengujian radio dan penempatan node. **Batas:** eksperimen 868 MHz dan body–UAV bukan mesh darat NusaNode 923 MHz. Tidak menyalin angka jangkauan sebagai kemampuan perangkat sendiri. **Status:** referensi pengujian komunikasi, belum eksperimen hardware NusaSurvey.

### J6 — Dataset SAR LoRa avalanche, 2024

Girolami, M., dkk. (2024). *An Experimental Dataset for Search and Rescue Operations in Avalanche Scenarios Based on LoRa Technology*. IEEE Access, 12, 171015–171035. DOI yang dicatat pada kajian awal: **10.1109/ACCESS.2024.3497654**. [Dataset penulis v2](https://zenodo.org/records/13932869).

**Landasan:** disiplin eksperimen RSSI/SNR, posisi referensi, kondisi media, dan pencatatan data SAR. **Pemetaan:** template pencatatan ground truth radio. **Batas:** lingkungan salju berbeda dari Indonesia; uraian eksperimen bersumber dokumentasi dataset, PDF jurnal penuh belum berhasil diperiksa. Daftar penulis lengkap/metadata final perlu dilengkapi dari penerbit sebelum sitasi formal; tidak dimasukkan ke BibTeX terpilih.

## 3. Buku, prosiding algoritma, dan preprint

| ID | Sumber | Peran dan batas | Status |
|---|---|---|---|
| T1 | LaValle, S. M. (2006), *Planning Algorithms*, Cambridge University Press; [Bab 4.3.2](https://msl.cs.uiuc.edu/planning/node161.html) | Configuration space dan Minkowski sum untuk perluasan hambatan. Buku teori, bukan jurnal eksperimen jurang manusia. Margin lokal perlu ditentukan sendiri. | Landasan buffer; buffer tebing belum dibuat |
| T2 | Koenig, S., & Likhachev, M. (2002), *D* Lite*, AAAI, 476–483; [halaman institusi](https://publications.ri.cmu.edu/d-lite) | Pencarian ulang inkremental saat graph berubah; tidak menentukan apakah data medan aman. | Rencana optimasi; sekarang Dijkstra penuh |
| T3 | Tremblay, J.-F., Alhosh, J., Petit, L., Lotfi, F., Landauro, L., & Meger, D. (2024), *Topological mapping for traversability-aware long-range navigation in off-road terrain*; [arXiv:2410.01925](https://arxiv.org/abs/2410.01925) | Node topologi dengan informasi keterlintasan; acuan hierarki lokal/global pada robot. Status yang diperiksa preprint, bukan jurnal SAR. | Acuan arsitektur; model vision belum dipakai |

Kandidat prosiding geofence LoRa 2026, LoRa–UAV meta-RL, rescue boat, dan publikasi tambahan lain tetap dicatat pada [referensi SAR awal](../specs/Referensi_SAR_Geofence_LoRa_Mesh.md). Status abstrak/full text serta batas bukti tidak ditingkatkan hanya karena judul relevan.

Model Transformer ETA pegunungan 2025 juga dicatat pada [kajian lintas medan](../architecture/Kajian_Routing_Lintas_Medan.md). Studi menggunakan kendaraan, sehingga bukan pilihan baseline kecepatan jalan kaki SAR.

## 4. Sumber teknis yang mendukung implementasi, bukan jurnal

| Sumber | Konsep yang dipakai | Hubungan dengan kode |
|---|---|---|
| [GRASS r.walk](https://grass.osgeo.org/grass-stable/manuals/r.walk.html) | Biaya waktu berarah Aitken/Langmuir, friction, Dijkstra dan NoData | Implementasi lokal pada `engine.js`; koefisien lahan/profil tambahan masih asumsi |
| [GRASS r.slope.aspect](https://grass.osgeo.org/grass-stable/manuals/r.slope.aspect.html) | Gradient, slope permukaan, aspect, curvature; pendekatan Horn | Landasan analisis bahaya sekitar rute, belum layer runtime |
| [OSRM source/API](https://github.com/Project-OSRM/osrm-backend) | Profil foot, snapping, kontrak route dan status gagal | Referensi provider/graph terpetakan, bukan solver DEM universal |
| [Rasterio](https://rasterio.readthedocs.io/en/stable/topics/reproject.html) / [GDAL](https://gdal.org/) | Reprojection, alignment, resampling/rasterize | Pipeline persiapan wilayah |
| [Copernicus DEM](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM), [WorldCover](https://esa-worldcover.org/en/data-access), [DEMNAS](https://www.big.go.id/en/content/product/demnas) | Spesifikasi produk data, resolusi/datum/kategori | Dua pertama dipakai demo; DEMNAS opsi lanjutan |
| [KML altitude modes](https://developers.google.com/kml/documentation/altitudemode), [Google Earth profile](https://support.google.com/earth/answer/148134?hl=en) | Format pembandingan geometri/elevasi | Ekspor clampToGround dan evaluasi manual |

## 5. Cara memakai untuk laporan TA

- **Landasan teori:** graph/least-cost path, waktu manusia, geomorfometri, geofence, buffer, dan ketidakpastian. Gunakan J1–J3 untuk gerak/risiko, T1–T2 untuk teori geometri/algoritma, serta dokumentasi teknis untuk implementasi.
- **Penelitian terkait:** pisahkan routing manusia, traversability robot, tracking LoRa, dan geofence. Jangan menyebut hasil salah satu kategori sebagai pembuktian kategori lainnya.
- **Metode:** nyatakan sumber/versi data, profil, friction, batas grade, penalti, dan constraint; diagram arsitektur ada di dokumentasi pengembangan.
- **Pengujian:** pisahkan pengujian software, akurasi data elevasi, kalibrasi ETA, deteksi tebing, dan komunikasi hardware. Run simulator bukan uji operasi SAR.
- **Kontribusi yang dapat diuji:** integrasi graph jalan–medan–transisi, constraint versi hambatan, navigasi offline wilayah terbatas, dan ekspor evaluasi. Antisipasi jurang merupakan perluasan yang masih harus dibuat/dibuktikan. Belum mengklaim konsep pertama di dunia atau probabilitas keselamatan tertentu.
