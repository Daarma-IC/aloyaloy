package id.nusamesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import id.nusamesh.app.ble.BluetoothPermissionActions
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.data.AndroidKeyValueStore
import id.nusamesh.app.media.ChatMediaActions
import id.nusamesh.app.media.LoraImageCodec
import id.nusamesh.app.mesh.engine.AndroidBleLink
import id.nusamesh.app.protocol.MeshMediaCodec
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : ComponentActivity(), ChatMediaActions, BluetoothPermissionActions {
    private lateinit var link: AndroidBleLink
    private var imageResult: ((ChatAttachment) -> Unit)? = null
    private var imageLoraProfile = false
    private var fileResult: ((ChatAttachment) -> Unit)? = null
    private var mediaError: ((String) -> Unit)? = null
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var playbackFile: File? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var pendingRecordStart: (() -> Unit)? = null
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var bluetoothDenied: ((String) -> Unit)? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadImage(it) } ?: mediaError?.invoke("Pemilihan gambar dibatalkan")
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadFile(it) } ?: mediaError?.invoke("Pemilihan file dibatalkan")
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingRecordStart?.invoke() else mediaError?.invoke("Izin mikrofon diperlukan untuk voice note")
        pendingRecordStart = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        if (granted) pendingBluetoothAction?.invoke()
        else bluetoothDenied?.invoke("Izin perangkat sekitar diperlukan untuk menghubungkan Bluetooth")
        pendingBluetoothAction = null
        bluetoothDenied = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = AndroidBleLink(applicationContext)
        val store = AndroidKeyValueStore(applicationContext)
        setContent { NusaMeshApp(link, store, mediaActions = this, bluetoothPermission = this) }
    }

    override fun runWithPermission(onGranted: () -> Unit, onDenied: (String) -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            onGranted()
        } else {
            pendingBluetoothAction = onGranted
            bluetoothDenied = onDenied
            permissionLauncher.launch(permissions)
        }
    }

    override fun onDestroy() {
        runCatching { recorder?.release() }
        releasePlayer()
        if (isFinishing && ::link.isInitialized) link.stop()
        super.onDestroy()
    }

    override fun pickImage(loraProfile: Boolean, onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
        imageLoraProfile = loraProfile
        imageResult = onPicked
        mediaError = onError
        imagePicker.launch("image/*")
    }

    override fun pickFile(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
        fileResult = onPicked
        mediaError = onError
        filePicker.launch(arrayOf("application/pdf", "text/plain", "application/zip"))
    }

    override fun startVoiceNote(onError: (String) -> Unit) {
        mediaError = onError
        val start = { beginRecording() }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            pendingRecordStart = start
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun stopVoiceNote(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
        val activeRecorder = recorder ?: return onError("Belum ada rekaman aktif")
        val file = recordingFile ?: return onError("File rekaman tidak tersedia")
        val duration = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt().coerceAtLeast(1)
        val stopped = runCatching { activeRecorder.stop() }
        activeRecorder.release()
        recorder = null
        recordingFile = null
        if (stopped.isFailure) {
            file.delete()
            onError("Voice note terlalu pendek")
            return
        }
        val bytes = file.readBytes()
        file.delete()
        if (bytes.size > MeshMediaCodec.MAX_MEDIA_BYTES) {
            onError("Voice note terlalu besar. Rekam maksimal sekitar 40 detik")
            return
        }
        onRecorded(ChatAttachment("voice-${System.currentTimeMillis()}.m4a", "audio/mp4", bytes, ChatMessageKind.Voice, duration))
    }

    private var playbackFinished: (() -> Unit)? = null

    override fun playVoiceNote(
        bytes: ByteArray,
        mimeType: String,
        onStarted: (durationMs: Long) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (bytes.isEmpty()) return onError("Data voice note kosong")
        releasePlayer()
        mediaError = onError
        val extension = when {
            mimeType.contains("ogg") -> "ogg"
            mimeType.contains("webm") -> "webm"
            mimeType.contains("amr") -> "amr"
            else -> "m4a"
        }
        val file = File(cacheDir, "voice-play-${System.currentTimeMillis()}.$extension")
        runCatching {
            file.writeBytes(bytes)
            val activePlayer = MediaPlayer()
            player = activePlayer
            playbackFile = file
            playbackFinished = onFinished
            activePlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            activePlayer.setOnPreparedListener { prepared ->
                requestAudioFocus()
                prepared.start()
                onStarted(prepared.duration.toLong().coerceAtLeast(1))
            }
            activePlayer.setOnCompletionListener { releasePlayer() }
            activePlayer.setOnErrorListener { _, what, extra ->
                releasePlayer()
                onError("Voice note gagal diputar (kode $what/$extra)")
                true
            }
            activePlayer.setDataSource(file.absolutePath)
            activePlayer.prepareAsync()
        }.onFailure { error ->
            releasePlayer()
            file.delete()
            onError(error.message ?: "Voice note gagal diputar")
        }
    }

    override fun stopVoicePlayback() = releasePlayer()

    private fun requestAudioFocus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audio.requestAudioFocus(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).build(),
            )
        }
        // Volume media 0 → tidak terdengar sama sekali; beri tahu pengguna.
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            mediaError?.invoke("Volume media 0 — naikkan volume untuk mendengar voice note")
        }
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playbackFile?.delete()
        playbackFile = null
        playbackFinished?.let { playbackFinished = null; it() }
    }

    private fun beginRecording() {
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val activeRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        runCatching {
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            activeRecorder.setAudioChannels(1)
            activeRecorder.setAudioSamplingRate(16_000)
            activeRecorder.setAudioEncodingBitRate(16_000)
            activeRecorder.setMaxDuration(40_000)
            activeRecorder.setOutputFile(file.absolutePath)
            activeRecorder.prepare()
            activeRecorder.start()
        }.onSuccess {
            recorder = activeRecorder
            recordingFile = file
            recordingStartedAt = System.currentTimeMillis()
        }.onFailure { error ->
            activeRecorder.release()
            file.delete()
            mediaError?.invoke(error.message ?: "Perekam suara gagal dimulai")
        }
    }

    private fun loadImage(uri: Uri) {
        if (imageLoraProfile) return loadLoraImage(uri)
        runCatching {
            val bitmap = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error("Gambar tidak dapat dibaca")
            val scale = minOf(1f, 960f / maxOf(bitmap.width, bitmap.height))
            val resized = if (scale < 1f) Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true,
            ) else bitmap
            var quality = 62
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { output ->
                    resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    output.toByteArray()
                }
                quality -= 8
            } while (bytes.size > MeshMediaCodec.MAX_MEDIA_BYTES && quality >= 22)
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
            require(bytes.size <= MeshMediaCodec.MAX_MEDIA_BYTES) { "Gambar terlalu besar untuk jaringan LoRa" }
            ChatAttachment("image-${System.currentTimeMillis()}.jpg", "image/jpeg", bytes, ChatMessageKind.Image)
        }.onSuccess { imageResult?.invoke(it) }
            .onFailure { mediaError?.invoke(it.message ?: "Gagal memproses gambar") }
    }

    /** Profil LoRa: 128 px WebP ≤ 1,2 KB supaya gambar ikut melintas Nusa Node. */
    private fun loadLoraImage(uri: Uri) {
        runCatching {
            val source = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Gambar tidak dapat dibaca")
            val bytes = LoraImageCodec.encode(source) ?: error("Gambar tidak dapat diproses")
            ChatAttachment("${LoraImageCodec.FILE_PREFIX}${System.currentTimeMillis()}.webp", LoraImageCodec.MIME, bytes, ChatMessageKind.Image)
        }.onSuccess { imageResult?.invoke(it) }
            .onFailure { mediaError?.invoke(it.message ?: "Gagal memproses gambar") }
    }

    private fun loadFile(uri: Uri) {
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("File tidak dapat dibaca")
            require(bytes.size <= MeshMediaCodec.MAX_MEDIA_BYTES) { "File maksimal 96 KB untuk jaringan LoRa" }
            ChatAttachment(displayName(uri), contentResolver.getType(uri) ?: "application/octet-stream", bytes, ChatMessageKind.File)
        }.onSuccess { fileResult?.invoke(it) }
            .onFailure { mediaError?.invoke(it.message ?: "Gagal membaca file") }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return "lampiran-${System.currentTimeMillis()}"
    }
}


