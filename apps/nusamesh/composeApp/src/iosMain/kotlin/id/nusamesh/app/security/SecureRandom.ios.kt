@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.nusamesh.app.security

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes gagal ($status)" }
    return out
}
