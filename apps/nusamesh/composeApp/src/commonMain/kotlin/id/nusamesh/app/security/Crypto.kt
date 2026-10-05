package id.nusamesh.app.security

/**
 * Primitif kriptografi standar dalam Kotlin murni (jalan sama di Android, iOS, web): SHA-256 (FIPS 180-4),
 * HMAC-SHA256 (RFC 2104) dan AEAD ChaCha20-Poly1305 (RFC 8439). Diuji dengan test vector RFC/NIST dan
 * dicocokkan silang dengan pustaka `cryptography` Python (lihat CryptoTest).
 */
object Sha256 {
    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun hash(data: ByteArray): ByteArray {
        val h = intArrayOf(0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(), 0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt())
        val bitLength = data.size.toLong() * 8
        val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in padded.indices step 64) {
            for (t in 0 until 16) w[t] = be32(padded, block + t * 4)
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
                val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return ByteArray(32).also { out -> for (i in 0 until 8) putBe32(out, i * 4, h[i]) }
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val block = (if (key.size > 64) hash(key) else key).copyOf(64)
        val inner = ByteArray(64) { (block[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(64) { (block[it].toInt() xor 0x5c).toByte() }
        return hash(outer + hash(inner + message))
    }

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

object ChaCha20Poly1305 {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_SIZE && nonce.size == NONCE_SIZE)
        val ciphertext = chacha20(key, 1, nonce, plaintext)
        return ciphertext + tag(key, nonce, aad, ciphertext)
    }

    /** null bila tag tidak cocok (kunci salah atau pesan diubah). */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (key.size != KEY_SIZE || nonce.size != NONCE_SIZE || sealed.size < TAG_SIZE) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_SIZE)
        val expected = tag(key, nonce, aad, ciphertext)
        if (!constantTimeEquals(expected, sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size))) return null
        return chacha20(key, 1, nonce, ciphertext)
    }

    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val polyKey = chachaBlock(key, 0, nonce).copyOf(32)
        val data = pad16(aad) + pad16(ciphertext) + le64(aad.size.toLong()) + le64(ciphertext.size.toLong())
        return Poly1305.mac(polyKey, data)
    }

    private fun pad16(b: ByteArray) = if (b.size % 16 == 0) b else b.copyOf(b.size + 16 - b.size % 16)
    private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }

    fun chacha20(key: ByteArray, counter: Int, nonce: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var block = 0
        while (block * 64 < input.size) {
            val stream = chachaBlock(key, counter + block, nonce)
            val start = block * 64
            for (i in start until minOf(start + 64, input.size)) out[i] = (input[i].toInt() xor stream[i - start].toInt()).toByte()
            block++
        }
        return out
    }

    fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce, i * 4)
        val x = s.copyOf()
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
            x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
            x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
            x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        return ByteArray(64).also { out ->
            for (i in 0 until 16) {
                val v = x[i] + s[i]
                out[i * 4] = v.toByte(); out[i * 4 + 1] = (v ushr 8).toByte(); out[i * 4 + 2] = (v ushr 16).toByte(); out[i * 4 + 3] = (v ushr 24).toByte()
            }
        }
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}

/** Poly1305 (RFC 8439 §2.5), limb 26-bit ala poly1305-donna; aritmetika Long supaya tidak meluap. */
object Poly1305 {
    private const val MASK = 0x3ffffffL

    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 32)
        val t0 = le32(key, 0); val t1 = le32(key, 4); val t2 = le32(key, 8); val t3 = le32(key, 12)
        val r0 = t0 and 0x3ffffffL
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03L
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ffL
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fffL
        val r4 = (t3 ushr 8) and 0x00fffffL
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L

        var offset = 0
        while (offset < message.size) {
            val remaining = message.size - offset
            val block = ByteArray(16)
            val hibit: Long
            if (remaining >= 16) {
                message.copyInto(block, 0, offset, offset + 16)
                hibit = 1L shl 24
            } else {
                // Blok terakhir tidak penuh: tambahkan byte 0x01, sisanya nol, tanpa bit 2^128.
                message.copyInto(block, 0, offset, message.size)
                block[remaining] = 1
                hibit = 0L
            }
            offset += 16
            h0 += le32(block, 0) and MASK
            h1 += (le32(block, 3) ushr 2) and MASK
            h2 += (le32(block, 6) ushr 4) and MASK
            h3 += (le32(block, 9) ushr 6) and MASK
            h4 += (le32(block, 12) ushr 8) or hibit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and MASK
            d1 += c; c = d1 ushr 26; h1 = d1 and MASK
            d2 += c; c = d2 ushr 26; h2 = d2 and MASK
            d3 += c; c = d3 ushr 26; h3 = d3 and MASK
            d4 += c; c = d4 ushr 26; h4 = d4 and MASK
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and MASK
            h1 += c
        }

        var c = h1 ushr 26; h1 = h1 and MASK
        h2 += c; c = h2 ushr 26; h2 = h2 and MASK
        h3 += c; c = h3 ushr 26; h3 = h3 and MASK
        h4 += c; c = h4 ushr 26; h4 = h4 and MASK
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and MASK
        h1 += c

        // h mod (2^130 - 5): pakai g = h + 5 - 2^130 bila tidak negatif.
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and MASK
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and MASK
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and MASK
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and MASK
        val g4 = h4 + c - (1L shl 26)
        if (g4 >= 0) { h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4 }

        val f0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var f = f0 + le32(key, 16)
        val o0 = f and 0xffffffffL
        f = f1 + le32(key, 20) + (f ushr 32)
        val o1 = f and 0xffffffffL
        f = f2 + le32(key, 24) + (f ushr 32)
        val o2 = f and 0xffffffffL
        f = f3 + le32(key, 28) + (f ushr 32)
        val o3 = f and 0xffffffffL
        return ByteArray(16).also { out ->
            listOf(o0, o1, o2, o3).forEachIndexed { i, v -> for (b in 0 until 4) out[i * 4 + b] = (v ushr (8 * b)).toByte() }
        }
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
}

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** Byte acak kriptografis dari sistem operasi (bukan kotlin.random, yang bisa ditebak). */
expect fun secureRandomBytes(size: Int): ByteArray
