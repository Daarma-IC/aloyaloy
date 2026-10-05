package id.nusamesh.app.security

/** Web Crypto: satu byte per panggilan ke JS supaya tidak perlu konversi array lintas batas wasm. */
@JsFun("() => { const b = new Uint8Array(1); crypto.getRandomValues(b); return b[0]; }")
private external fun randomByte(): Int

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size) { randomByte().toByte() }
