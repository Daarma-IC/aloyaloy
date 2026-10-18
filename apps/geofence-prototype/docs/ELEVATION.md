# Profil elevasi dan pembandingan Google Earth

Setelah memilih korban dan menekan **Arahkan ke korban**, navigasi menampilkan grafik elevasi tepat di bawah ringkasan jenis rute. Sebelum rute tersedia, grafik dan ekspor tidak ditampilkan. Rute lama yang stale/calculating tetap memiliki profil dengan penanda bahwa hasilnya perlu diperbarui.

Sumbu X adalah jarak horizontal kumulatif sepanjang geometri rute, bukan jarak garis lurus petugas–korban. Sumbu Y adalah elevasi DEM dalam meter dengan datum paket, EGM2008 untuk Kaliurang. Warna mengikuti segmen: biru OSM, ungu medan, jingga transisi. Tidak ada smoothing kurva atau perubahan geometri jalan.

Gerakkan pointer di grafik atau gunakan slider untuk memilih sampel. Readout menunjukkan elevasi satu desimal, jarak, koordinat latitude/longitude, jenis segmen, dan grade bertanda dalam persen dari sampel sebelumnya. Penanda hijau pada peta menunjukkan koordinat yang sama. Grade sampel adalah turunan data DEM, bukan hasil pengukuran kemiringan aktual.

## Sumber dan sampling

`buildElevationProfile` di `src/terrain/elevationProfile.js` menyampel polyline menggunakan interpolasi bilinear yang sama dengan context terrain Hybrid. Semua vertex rute dipertahankan. Setiap segmen dibagi dengan interval maksimal setengah resolusi grid: 17,5 m untuk grid Kaliurang 35 m. Jarak sampel aktual bisa lebih pendek karena panjang segmen berbeda dan vertex OSM dipertahankan. Sampling lebih rapat tidak meningkatkan resolusi sumber DEM.

Sampel elevasi hilang tetap null, garis terputus pada NoData. Versi paket yang berbeda dari hasil rute ditolak. Grafik menyertakan sumber, datum, resolusi grid, estimasi resolusi sumber, serta interval sampling. CSV mengekspor nilai hingga tiga desimal untuk perbandingan numerik; jumlah desimal bukan pernyataan akurasi pengukuran.

Copernicus GLO-30 merupakan DSM yang mencakup vegetasi dan bangunan, berbeda dari DTM tanah terbuka. Data historis dan grid puluhan meter tidak cukup untuk memverifikasi setiap jurang atau perubahan lapangan. [Dokumentasi sumber](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).

## Ekspor dan langkah pembandingan

1. Tekan **Unduh rute KML** untuk rute yang sedang ditampilkan.
2. Buka file di Google Earth Pro. Pilih path **Rute korban - profil tanah Google Earth**, lalu gunakan **Show Elevation Profile**. [Panduan resmi Google Earth](https://support.google.com/earth/answer/148134?hl=en).
3. Tekan **Unduh elevasi CSV**. Bandingkan titik dengan koordinat yang sama, bukan hanya bentuk kurva atau persentase panjang rute. Google Earth dapat menyampel jarak pada interval yang berbeda.
4. KML juga mempunyai folder titik **Sampel DEM NusaSurvey**, default tersembunyi. Aktifkan untuk menginspeksi titik dan nilai DEM melalui ExtendedData.

Path KML menggunakan koordinat longitude, latitude, 0 dan `altitudeMode=clampToGround`. Dengan demikian tanah Google Earth menentukan elevasi path, bukan angka DEM NusaSurvey yang dipaksakan sebagai altitude absolute. [Dokumentasi altitude modes KML](https://developers.google.com/kml/documentation/altitudemode).

CSV menyimpan indeks sampel, jarak meter, latitude, longitude, elevasi DEM, grade persen, jenis segmen, ID way OSM, resolusi grid, interval sampling maksimum, datum vertikal, sumber DEM/resolusi sumber, versi paket, waktu hasil rute, dan status rute. Format menggunakan UTF-8 BOM, newline CRLF, serta quoting CSV. Ekspor lokal dapat dilakukan offline setelah aplikasi/paket disiapkan.

Google Earth adalah pembanding dataset lain, bukan ground truth lapangan. Perbedaan sumber, DSM/DTM, datum, resolusi, dan metode sampling harus diperhatikan ketika menilai selisih elevasi. Pengukuran lapangan tetap diperlukan untuk membuktikan akurasi dan keterlintasan.

## Verifikasi

- Uji bidang elevasi analitik: interpolasi sesuai persamaan bidang, endpoint/vertex tidak bergeser, jarak kumulatif benar, interval tidak melampaui batas.
- Uji NoData, route tanpa panjang, dan penolakan versi paket berbeda.
- Uji ekspor CSV/KML: pemisahan nilai DEM dari altitude Google Earth, urutan longitude/latitude, serta escaping XML.
- Uji browser: grafik hanya setelah Arahkan, slider memilih endpoint korban dan penanda peta, angka awal/akhir cocok dengan solver, download CSV/KML benar dan XML valid, layout 390 px tidak overflow.
