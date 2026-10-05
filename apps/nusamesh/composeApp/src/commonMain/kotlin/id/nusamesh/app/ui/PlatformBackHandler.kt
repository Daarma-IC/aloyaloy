package id.nusamesh.app.ui

import androidx.compose.runtime.Composable

/**
 * Tombol/gestur Back sistem. Handler yang dikomposisi belakangan (lebih "di atas") menang, jadi overlay
 * menutup dirinya dulu sebelum navigasi halaman.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
