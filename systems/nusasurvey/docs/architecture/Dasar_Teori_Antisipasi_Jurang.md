# Dasar teori antisipasi jurang pada routing NusaSurvey

Tanggal penelusuran: 14 Oktober 2026. Status: landasan dan usulan desain; deteksi tebing sekitar rute serta buffer ketidakpastian belum diimplementasikan. Prototype sudah mempunyai biaya arah perjalanan, batas grade, hambatan poligon, dan pencarian ulang graph Hybrid.

## Ruang lingkup klaim

Kombinasi analisis bentuk medan, perluasan area hambatan, dan pencarian lintasan memiliki dasar yang dapat dirujuk. Tidak ada sumber di bawah yang membuktikan prototype NusaSurvey aman untuk semua jurang. Penentuan ambang penurunan, ukuran lingkungan, dan lebar buffer merupakan adaptasi yang memerlukan data dan pengujian lokal.

## 1. Geomorfometri: arah perjalanan berbeda dari kemiringan permukaan

Untuk permukaan elevasi z(x,y) dalam koordinat metrik, kemiringan permukaan dapat dinyatakan:

```text
slope_surface = atan(sqrt((dz/dx)^2 + (dz/dy)^2))
grade_travel  = (z_tujuan - z_awal) / jarak_horizontal
```

Grade perjalanan dapat mendekati nol ketika seseorang mengikuti kontur, walaupun permukaan di sampingnya sangat curam. Maka pemeriksaan grade setiap edge tidak menggantikan pemeriksaan lereng/penurunan di sekitar edge.

[GRASS r.slope.aspect](https://grass.osgeo.org/grass-stable/manuals/r.slope.aspect.html) menghitung slope, aspect, curvature, dan turunan elevasi. Dokumentasinya menyebut lingkungan 3×3 dan formula Horn untuk turunan pertama. Ini referensi metode/implementasi GIS, bukan jurnal validasi deteksi jurang manusia. Curvature atau slope tinggi sendiri tidak membuktikan keberadaan jurang.

Adaptasi yang diusulkan: ukur penurunan ke beberapa arah dan skala jarak, kemudian gabungkan dengan slope, fitur tebing terpetakan, dan laporan lapangan untuk membentuk layer dugaan bahaya. Skala harus sesuai resolusi efektif data. Klasifikasi berbasis ambang perlu divalidasi terhadap tebing nyata dan lokasi bukan tebing, termasuk artefak DSM dari pohon/bangunan.

## 2. Configuration space: obstacle inflation dan buffer

LaValle, *Planning Algorithms*, Bab 4.3, memformalkan ruang konfigurasi yang bebas hambatan melalui geometri tubuh dan obstacle. Pada kasus translasi, konstruksi menggunakan Minkowski sum dengan refleksi bentuk tubuh. [Bab 4.3.2 buku penulis](https://msl.cs.uiuc.edu/planning/node161.html).

Untuk wilayah bahaya H dan cakram margin B(r), bentuk buffer sederhana:

```text
H_buffer = H ⊕ B(r)
lintasan harus tidak memotong H_buffer
```

Ini memberi dasar geometris untuk menjaga clearance. Untuk NusaSurvey, r bukan angka aman universal. Ia adalah parameter kebijakan dan ketidakpastian: ukuran/gerak tim, batas area bahaya, kualitas peta, serta posisi pengguna. Jika ketidakpastian diperlakukan sebagai himpunan berbatas, himpunan tersebut dapat dimasukkan ke konstruksi perluasan. Jika memakai distribusi probabilitas, perlu tingkat cakupan dan kalibrasi; nilai accuracy dari GPS tidak otomatis merupakan batas kesalahan pasti.

Model buffer lingkaran simetris adalah penyederhanaan konservatif. Ia dapat menutup akses yang sebenarnya bisa dilewati; akses sempit atau struktur bertingkat memerlukan pemeriksaan tambahan. Tidak boleh membuka pengecualian hanya karena suatu garis diberi tag jalan pada OSM.

## 3. Traversability dan risk-aware planning

Dixit, Fan, Otsu, Dey, Agha-Mohammadi, dan Burdick. *STEP: Stochastic Traversability Evaluation and Planning for Risk-Aware Off-road Navigation; Results from the DARPA Subterranean Challenge*. Field Robotics 4 (2024), 182–210, DOI 10.55417/fr.2024006. Metadata jurnal terverifikasi di [halaman penulis arXiv](https://arxiv.org/abs/2303.01614); [naskah lengkap](https://arxiv.org/html/2303.01614v1).

STEP memodelkan risiko geometris dan ketidakpastian, termasuk slope, step, slippage, dan negative obstacles. Deteksi negative obstacles memakai celah pengamatan LiDAR beserta pemeriksaan cakupan/occlusion, bukan sekadar menganggap seluruh data kosong sebagai jurang. Framework menggunakan CVaR untuk mempertimbangkan ekor kerugian. Pengujian dilakukan pada robot roda dan berkaki di lingkungan subterranean; koefisiennya tidak dapat langsung dipakai untuk manusia SAR.

Adaptasi awal NusaSurvey sebaiknya berupa constraint dan clearance yang dapat diaudit. CVaR menjadi tahap berikutnya jika distribusi kesalahan/kerugian dapat diestimasi. Skor kategori atau confidence rekaan tidak cukup untuk mengklaim implementasi CVaR maupun probabilitas keselamatan.

## 4. Biaya perjalanan manusia dan least-cost path

Campbell, Cutler, dan Dennison. *A singular, broadly-applicable model for estimating on- and off-path walking travel rates using airborne lidar data*. Scientific Reports 14, 21393 (2024), DOI 10.1038/s41598-024-71359-6. [Artikel](https://www.nature.com/articles/s41598-024-71359-6).

STRIDE memakai slope, kepadatan vegetasi, dan kekasaran permukaan untuk memodelkan kecepatan jalan kaki di jalur maupun luar jalur. Relevansinya adalah biaya perjalanan manusia; ini bukan classifier jurang atau bukti buffer tertentu aman. Masukan LiDAR dan kalibrasi lokal tidak dapat digantikan begitu saja oleh kelas tutupan lahan.

Sebagai baseline implementasi, [GRASS r.walk](https://grass.osgeo.org/grass-stable/manuals/r.walk.html) menggabungkan waktu arah naik/turun dengan friction, lalu menghitung minimum biaya melalui Dijkstra. Prototype memiliki implementasi lokal model waktu serupa; binary GRASS belum dijalankan sebagai pembanding numerik.

## 5. Formulasi awal untuk NusaSurvey

Formulasi berikut merupakan sintesis desain, bukan rumus keselamatan baku dari satu paper:

```text
c(e) = ∞                               jika e memotong bahaya/buffer terlarang
c(e) = t(e) + λr·R(e) + λu·U(e)         untuk edge yang masih diizinkan
P*   = argmin_P Σ c(e)
ETA  = Σ t(e), bukan Σ c(e)
```

R(e) adalah paparan risiko yang definisinya harus dinyatakan; U(e) adalah penalti ketidakpastian. Bobot mengonversi komponen ke unit biaya yang konsisten, wajib nonnegatif untuk baseline Dijkstra. Bahaya yang ditetapkan terlarang tidak boleh dilintasi hanya karena alternatifnya lebih jauh. Jika tidak ada lintasan dalam graph yang memenuhi constraint, keluarkan noTraversablePath.

## 6. Bukti yang harus dikumpulkan

- Fixture jalur datar di samping penurunan curam: baseline grade dapat lolos, layer bahaya dan buffer harus menghindarkan lintasan.
- Uji offset posisi serta lokasi tepi bahaya dan variasi resolusi DEM.
- Uji gap kecil, NoData, artefak vegetasi/bangunan, jalur sempit, dan jembatan agar penolakan tidak hanya mengandalkan bentuk raster.
- Ground truth tebing melalui survei/laporan terverifikasi: ukur tebing terlewat (false negative), false positive, dan clearance terhadap bahaya yang dipetakan. Clearance peta tidak otomatis sama dengan clearance lapangan.
- Bandingkan rute, waktu, keberhasilan menemukan alternatif, serta runtime/RAM terhadap baseline; validasi ETA manusia terpisah dari validasi deteksi bahaya.

Klaim awal yang dapat diuji: sistem menghindari zona dugaan/terverifikasi bahaya dan buffer sesuai data serta kebijakan yang dinyatakan. Klaim bahwa semua jurang terdeteksi memerlukan bukti yang jauh lebih kuat.
