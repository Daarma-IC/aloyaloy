@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.nusamesh.app.mesh.protocol

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.uLongfVar
import platform.zlib.uncompress

/** libz bawaan iOS menghasilkan format zlib yang sama dengan Deflater/Inflater Java. */
actual object Zlib {
    actual fun deflate(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val bound = compressBound(data.size.toULong()).toInt()
        val out = ByteArray(bound)
        return memScoped {
            val outLen = alloc<uLongfVar>()
            outLen.value = bound.toULong()
            val rc = out.usePinned { o ->
                data.usePinned { i ->
                    compress2(
                        o.addressOf(0).reinterpret(), outLen.ptr,
                        i.addressOf(0).reinterpret(), data.size.toULong(), 1,
                    )
                }
            }
            if (rc == Z_OK) out.copyOf(outLen.value.toInt()) else null
        }
    }

    actual fun inflate(data: ByteArray, originalSize: Int): ByteArray? {
        if (data.isEmpty() || originalSize <= 0) return null
        val out = ByteArray(originalSize)
        return memScoped {
            val outLen = alloc<uLongfVar>()
            outLen.value = originalSize.toULong()
            val rc = out.usePinned { o ->
                data.usePinned { i ->
                    uncompress(
                        o.addressOf(0).reinterpret(), outLen.ptr,
                        i.addressOf(0).reinterpret(), data.size.toULong(),
                    )
                }
            }
            if (rc == Z_OK && outLen.value > 0u) out.copyOf(outLen.value.toInt()) else null
        }
    }
}
