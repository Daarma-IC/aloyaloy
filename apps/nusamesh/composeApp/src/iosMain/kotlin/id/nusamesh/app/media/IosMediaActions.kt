@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.nusamesh.app.media

import id.nusamesh.app.data.currentEpochMillis
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.protocol.MeshMediaCodec
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * Media iOS: pilih gambar (PHPicker), pilih file (document picker), rekam voice note AAC 16 kHz (sama
 * dengan Android, jadi bisa saling putar), dan putar voice note.
 */
class IosMediaActions : ChatMediaActions {
    /** View controller Compose; dipakai untuk menampilkan picker. */
    var presenter: UIViewController? = null

    private var pickerDelegate: NSObject? = null
    private var recorder: AVAudioRecorder? = null
    private var recordingUrl: NSURL? = null
    private var recordingStartedAt = 0L
    private var player: AVAudioPlayer? = null
    private var playerDelegate: PlayerDelegate? = null
    private var playbackFinished: (() -> Unit)? = null

    // ------------------------------------------------------------------------------------------
    //  Gambar
    // ------------------------------------------------------------------------------------------
    override fun pickImage(loraProfile: Boolean, onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
        val host = presenter ?: return onError("Pemilih gambar belum siap")
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config)
        val delegate = ImagePickerDelegate { result ->
            host.dismissViewControllerAnimated(true, completion = null)
            pickerDelegate = null
            if (result == null) return@ImagePickerDelegate onError("Pemilihan gambar dibatalkan")
            result.itemProvider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, _ ->
                val attachment = data?.let { encodeImage(it, loraProfile) }
                dispatch_async(dispatch_get_main_queue()) {
                    if (attachment != null) onPicked(attachment) else onError("Gagal memproses gambar")
                }
            }
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        host.presentViewController(picker, animated = true, completion = null)
    }

    private fun encodeImage(data: NSData, loraProfile: Boolean): ChatAttachment? {
        val image = UIImage.imageWithData(data) ?: return null
        val now = currentEpochMillis()
        return if (loraProfile) {
            // Profil LoRa: sisi terpanjang 128 px, JPEG ≤ 1200 B (iOS tidak punya encoder WebP bawaan).
            var side = LORA_MAX_SIDE
            while (true) {
                val small = resize(image, side)
                val bytes = bestJpeg(small, LORA_MAX_BYTES)
                if (bytes != null || side <= LORA_MIN_SIDE) {
                    val out = bytes ?: UIImageJPEGRepresentation(small, 0.05)?.toByteArray() ?: return null
                    return ChatAttachment("lora_$now.jpg", "image/jpeg", out, ChatMessageKind.Image)
                }
                side = (side * 0.85).toInt().coerceAtLeast(LORA_MIN_SIDE)
            }
            @Suppress("UNREACHABLE_CODE") null
        } else {
            val resized = resize(image, 960)
            val bytes = bestJpeg(resized, MeshMediaCodec.MAX_MEDIA_BYTES) ?: return null
            ChatAttachment("image-$now.jpg", "image/jpeg", bytes, ChatMessageKind.Image)
        }
    }

    /** Kualitas JPEG tertinggi yang masih ≤ [maxBytes] (pencarian biner). */
    private fun bestJpeg(image: UIImage, maxBytes: Int): ByteArray? {
        var lo = 5
        var hi = 90
        var best: ByteArray? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val bytes = UIImageJPEGRepresentation(image, mid / 100.0)?.toByteArray() ?: return best
            if (bytes.size <= maxBytes) { best = bytes; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    private fun resize(image: UIImage, maxSide: Int): UIImage {
        val (w, h) = image.size.useContents { width to height }
        val scale = minOf(1.0, maxSide / maxOf(w, h))
        if (scale >= 1.0) return image
        val tw = maxOf(1.0, (w * scale).toLong().toDouble())
        val th = maxOf(1.0, (h * scale).toLong().toDouble())
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { this.scale = 1.0; opaque = true }
        return UIGraphicsImageRenderer(size = CGSizeMake(tw, th), format = format).imageWithActions { _ ->
            image.drawInRect(CGRectMake(0.0, 0.0, tw, th))
        }
    }

    // ------------------------------------------------------------------------------------------
    //  File
    // ------------------------------------------------------------------------------------------
    override fun pickFile(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
        val host = presenter ?: return onError("Pemilih file belum siap")
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOfNotNull(UTTypePDF, UTTypePlainText, UTTypeZIP),
            asCopy = true,
        )
        val delegate = DocumentPickerDelegate { url ->
            pickerDelegate = null
            if (url == null) return@DocumentPickerDelegate onError("Pemilihan file dibatalkan")
            val bytes = NSData.dataWithContentsOfURL(url)?.toByteArray()
                ?: return@DocumentPickerDelegate onError("File tidak dapat dibaca")
            if (bytes.size > MeshMediaCodec.MAX_MEDIA_BYTES) return@DocumentPickerDelegate onError("File maksimal 96 KB untuk jaringan mesh")
            val name = url.lastPathComponent ?: "lampiran-${currentEpochMillis()}"
            val mime = when (url.pathExtension?.lowercase()) {
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                else -> "text/plain"
            }
            onPicked(ChatAttachment(name, mime, bytes, ChatMessageKind.File))
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        host.presentViewController(picker, animated = true, completion = null)
    }

    // ------------------------------------------------------------------------------------------
    //  Voice note
    // ------------------------------------------------------------------------------------------
    override fun startVoiceNote(onError: (String) -> Unit) {
        val session = AVAudioSession.sharedInstance()
        session.requestRecordPermission { granted ->
            dispatch_async(dispatch_get_main_queue()) {
                if (!granted) onError("Izin mikrofon diperlukan untuk voice note") else beginRecording(onError)
            }
        }
    }

    private fun beginRecording(onError: (String) -> Unit) {
        stopVoicePlayback()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker, error = null)
        session.setActive(true, error = null)
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "voice-${currentEpochMillis()}.m4a")
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatMPEG4AAC),
            AVSampleRateKey to NSNumber(double = 16_000.0),
            AVNumberOfChannelsKey to NSNumber(int = 1),
            AVEncoderBitRateKey to NSNumber(int = 16_000),
        )
        val rec = AVAudioRecorder(uRL = url, settings = settings, error = null)
        if (!rec.record()) return onError("Perekam suara gagal dimulai")
        recorder = rec
        recordingUrl = url
        recordingStartedAt = currentEpochMillis()
    }

    override fun stopVoiceNote(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
        val rec = recorder ?: return onError("Belum ada rekaman aktif")
        val url = recordingUrl ?: return onError("File rekaman tidak tersedia")
        rec.stop()
        recorder = null
        recordingUrl = null
        val duration = ((currentEpochMillis() - recordingStartedAt) / 1000L).toInt()
        val bytes = NSData.dataWithContentsOfURL(url)?.toByteArray()
        NSFileManager.defaultManager.removeItemAtURL(url, error = null)
        if (duration < 1 || bytes == null || bytes.isEmpty()) return onError("Voice note terlalu pendek")
        onRecorded(ChatAttachment("voice-${currentEpochMillis()}.m4a", "audio/mp4", bytes, ChatMessageKind.Voice, duration))
    }

    override fun playVoiceNote(
        bytes: ByteArray,
        mimeType: String,
        onStarted: (durationMs: Long) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stopVoicePlayback()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker, error = null)
        session.setActive(true, error = null)
        val p = AVAudioPlayer(data = bytes.toNSData(), error = null)
        val delegate = PlayerDelegate { stopVoicePlayback() }
        p.delegate = delegate
        if (!p.prepareToPlay() || !p.play()) return onError("Voice note gagal diputar")
        player = p
        playerDelegate = delegate
        playbackFinished = onFinished
        onStarted((p.duration * 1000).toLong().coerceAtLeast(1))
    }

    override fun stopVoicePlayback() {
        player?.stop()
        player = null
        playerDelegate = null
        playbackFinished?.let { playbackFinished = null; it() }
    }

    private companion object {
        const val LORA_MAX_SIDE = 128
        const val LORA_MIN_SIDE = 64
        const val LORA_MAX_BYTES = 1200
    }
}

private class ImagePickerDelegate(private val onResult: (PHPickerResult?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        onResult(didFinishPicking.firstOrNull() as? PHPickerResult)
    }
}

private class DocumentPickerDelegate(private val onResult: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onResult(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onResult(null)
}

private class PlayerDelegate(private val onDone: () -> Unit) : NSObject(), AVAudioPlayerDelegateProtocol {
    override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) = onDone()
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { target ->
    if (target.isNotEmpty()) target.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
}
