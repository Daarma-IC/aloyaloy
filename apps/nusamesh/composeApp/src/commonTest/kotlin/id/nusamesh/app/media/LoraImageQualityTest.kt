package id.nusamesh.app.media

import id.nusamesh.app.protocol.MeshMediaCodec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoraImageQualityTest {
    @Test
    fun everyQualityStaysOnLoraPath() {
        // Di atas batas ini gambar dianggap resolusi penuh dan hanya dikirim lewat Bluetooth.
        LoraImageQuality.entries.forEach {
            assertTrue(MeshMediaCodec.isLoraImage("lora_1.webp", it.maxBytes), it.label)
        }
    }

    @Test
    fun everyQualityFitsSuperResolutionInput() {
        // SuperResolution.MAX_INPUT = 256: lebih besar dari itu tombol "Perjelas (AI)" hilang.
        LoraImageQuality.entries.forEach { assertTrue(it.maxSide <= 256, it.label) }
    }

    @Test
    fun oversizedImageLeavesLoraPath() {
        assertFalse(MeshMediaCodec.isLoraImage("lora_1.webp", MeshMediaCodec.LORA_IMAGE_MAX_BYTES + 1))
    }
}
