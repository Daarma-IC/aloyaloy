package id.nusamesh.app.ui

import androidx.compose.runtime.Composable

/** Tidak ada tombol Back sistem di platform ini (iOS memakai gestur navigasi aplikasi sendiri). */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
