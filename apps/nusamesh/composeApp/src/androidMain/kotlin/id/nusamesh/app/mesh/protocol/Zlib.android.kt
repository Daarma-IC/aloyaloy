package id.nusamesh.app.mesh.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

actual object Zlib {
    actual fun deflate(data: ByteArray): ByteArray? = runCatching {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n == 0 && deflater.needsInput()) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }.getOrNull()

    actual fun inflate(data: ByteArray, originalSize: Int): ByteArray? = runCatching {
        if (originalSize <= 0) return null
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val result = ByteArray(originalSize)
            val n = inflater.inflate(result)
            when {
                n <= 0 -> null
                n == originalSize -> result
                else -> result.copyOfRange(0, n)
            }
        } finally {
            inflater.end()
        }
    }.getOrNull()
}
