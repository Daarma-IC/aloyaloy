# NusaSurvey

Workspace pembangunan sistem routing medan SAR sebelum integrasi ke aplikasi NusaMesh.

Pembaruan 15 Oktober 2026: routing dan navigasi sudah diintegrasikan ke `apps/nusamesh`; prioritas paket operasi Jawa Barat dan unduh wilayah Indonesia ditambahkan, sedangkan fencing/polygon tetap ditunda. [Dokumentasi integrasi](../../apps/nusamesh/docs/SAR_NAVIGATION.md) · [Paket regional dan batas kemampuan](../../apps/nusamesh/docs/REGIONAL_NAVIGATION.md). Catatan 14 Oktober di bawah merekam tahap prototype sebelum integrasi.

## Dokumen

- **[Satu dokumen gabungan untuk CD](docs/Referensi_Gabungan_Navigasi_NusaSurvey_CD.md)**: salinan lengkap 15 bahan awal dan pembaruan integrasi NusaMesh pada Bab 16, daftar isi, sumber/jurnal, implementasi, pengujian, serta BibTeX dalam satu MD.
- [Dokumentasi pengembangan lengkap](docs/Dokumentasi_Pengembangan_NusaSurvey.md): pekerjaan, arsitektur aktual, status fitur, model/parameter, reproduksi, dan pengujian.
- [Inventaris sumber dan teknologi](docs/Sumber_dan_Teknologi.md): runtime, dataset, source yang diaudit, provenance, dan opsi yang belum digunakan.
- [Landasan ilmiah dan daftar pustaka](docs/references/Landasan_Ilmiah.md): jurnal fondasi, pendukung SAR/LoRa, buku/prosiding, dan batas penerapannya; [BibTeX](docs/references/nusasurvey.bib).
- [Spesifikasi implementasi](docs/specs/Prompt_Implementasi_NusaSurvey_SAR.md): target pengembangan; routing medan menjadi prioritas inti.
- [Referensi SAR, geofence, dan mesh](docs/specs/Referensi_SAR_Geofence_LoRa_Mesh.md): referensi dan rancangan evaluasi.
- [Transkrip dan ringkasan konsultasi](docs/notes/dha_Transkrip_dan_Ringkasan.md): catatan konsultasi TA BLE–LoRa; bukan spesifikasi routing.
- [Analisis source upstream](docs/architecture/Analisis_Upstream.md): file yang dibaca, temuan, dan pemetaan modul.
- [Kajian routing lintas medan](docs/architecture/Kajian_Routing_Lintas_Medan.md): literatur, batas data, dan rancangan graph gabungan jalan–medan.
- [Dasar teori antisipasi jurang](docs/architecture/Dasar_Teori_Antisipasi_Jurang.md): geomorfometri, buffer, traversability, dan batas penerapan pada SAR.
- [Bahan riset](research/README.md): source terpilih, metadata commit, dan snapshot dokumentasi resmi.

## Struktur

```text
systems/nusasurvey/
  docs/
    Dokumentasi_Pengembangan_NusaSurvey.md
    Sumber_dan_Teknologi.md
    specs/                 Spesifikasi dan referensi kebutuhan
    notes/                 Transkrip konsultasi
    architecture/          Analisis dan keputusan desain
    references/            Daftar pustaka terpilih dan BibTeX
  research/
    source/                Source upstream terpilih + manifest checksum
    metadata/              Respons metadata commit dan indeks upstream
    snapshots/             Snapshot HTML dokumentasi resmi
    incomplete-downloads/  Unduhan terputus; bukan dependency yang siap dipakai
    import-source.ps1      Pengambilan ulang source pada commit yang dicatat
```

Prototype berada di [apps/geofence-prototype](../../apps/geofence-prototype/README.md). Implementasi sudah menambahkan solver medan lokal, importer GeoTIFF, graph gabungan jalan–medan–transisi, navigasi, hazard/rerouting, serta uji offline. [Kontrak Hybrid](../../apps/geofence-prototype/docs/HYBRID.md) dan [hasil verifikasi serta batas fitur](../../apps/geofence-prototype/docs/VALIDATION.md) dicatat terpisah. Aplikasi `apps/nusamesh` tidak diubah.
