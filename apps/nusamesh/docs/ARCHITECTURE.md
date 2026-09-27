# Arsitektur aplikasi

Aliran dependensi dibuat satu arah:

```text
Compose UI
   ↓ event / state
AppController
   ↓
MeshRepository ── NusaProtocol
   ↓
MeshTransport (common interface)
   ├── AndroidMeshTransport (BluetoothGatt)
   └── IosMeshTransport (CoreBluetooth)
```

`AppContainer` adalah dependency injection manual. Object dibuat sekali dan
constructor memperlihatkan dependensinya dengan jelas. Kalau proyek membesar,
kelas ini dapat diganti Koin tanpa mengubah UI atau protokol.

`gradle/libs.versions.toml` adalah pusat versi dan alias dependency. Build file
memakai bentuk seperti `alias(libs.plugins.kotlinMultiplatform)` supaya versi
tidak tersebar di banyak file.

State UI bersifat immutable (`AppUiState`) dan dipublikasikan lewat
`StateFlow`. Callback Bluetooth mengubah state transport, repository mengubah
byte mentah menjadi `NusaPacket`, lalu controller menyiapkan data untuk UI.

