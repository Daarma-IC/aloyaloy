package id.nusamesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import android.location.LocationListener
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import id.nusamesh.app.ble.BluetoothPermissionActions
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.data.AndroidKeyValueStore
import id.nusamesh.app.media.ChatMediaActions
import id.nusamesh.app.media.AndroidCodec2
import id.nusamesh.app.media.LoraImageCodec
import id.nusamesh.app.location.LocationAccessActions
import id.nusamesh.app.location.LocationAccessState
import id.nusamesh.app.location.DeviceLocation
import id.nusamesh.app.mesh.engine.AndroidBleLink
import id.nusamesh.app.protocol.MeshMediaCodec
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity(), ChatMediaActions, BluetoothPermissionActions, LocationAccessActions {
    private lateinit var link: AndroidBleLink
    private var imageResult: ((ChatAttachment) -> Unit)? = null
    private var imageLoraProfile = false
    private var fileResult: ((ChatAttachment) -> Unit)? = null
    private var mediaError: ((String) -> Unit)? = null
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var recordingOutput: ByteArrayOutputStream? = null
    @Volatile private var isRecordingVoice = false
    private var player: MediaPlayer? = null
    private var codec2Player: AudioTrack? = null
    private var codec2PlaybackToken = 0L
    private var playbackFile: File? = null
    private var recordingStartedAt = 0L
    private var pendingRecordStart: (() -> Unit)? = null
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var bluetoothDenied: ((String) -> Unit)? = null
    private val mutableLocationState = MutableStateFlow(LocationAccessState.Checking)
    override val state: StateFlow<LocationAccessState> = mutableLocationState
    private val mutableLocation = MutableStateFlow<DeviceLocation?>(null)
    override val location: StateFlow<DeviceLocation?> = mutableLocation
    private var locationUpdatesStarted = false
    private var locationPermissionRequested = false
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(value: Location) {
            mutableLocation.value = DeviceLocation(
                value.latitude, value.longitude, value.accuracy, value.time.coerceAtLeast(System.currentTimeMillis() - 60_000L),
            )
        }
        override fun onProviderEnabled(provider: String) = refresh()
        override fun onProviderDisabled(provider: String) = refresh()
    }

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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationPermissionRequested = true
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = AndroidBleLink(applicationContext)
        val store = AndroidKeyValueStore(applicationContext)
        setContent {
            NusaMeshApp(link, store, mediaActions = this, bluetoothPermission = this, locationAccess = this)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun requestPermission() {
        if (locationPermissionRequested &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
            return
        }
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    override fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    override fun refresh() {
        val permitted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            mutableLocationState.value = LocationAccessState.PermissionRequired
            return
        }
        val enabled = getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        mutableLocationState.value = if (enabled) LocationAccessState.Ready else LocationAccessState.ServiceDisabled
        if (enabled) startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationUpdatesStarted) return
        val manager = getSystemService(LocationManager::class.java) ?: return
        locationUpdatesStarted = true
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (manager.isProviderEnabled(provider)) {
                manager.getLastKnownLocation(provider)?.let(locationListener::onLocationChanged)
                manager.requestLocationUpdates(provider, 15_000L, 10f, locationListener)
            }
        }
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
        getSystemService(LocationManager::class.java)?.removeUpdates(locationListener)
        isRecordingVoice = false
        runCatching { recorder?.stop() }
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
        val duration = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt().coerceAtLeast(1)
        isRecordingVoice = false
        runCatching { activeRecorder.stop() }
        runCatching { recordingThread?.join(1_500) }
        runCatching { activeRecorder.release() }
        recorder = null
        recordingThread = null
        val pcmBytes = synchronized(this) { recordingOutput?.toByteArray() ?: ByteArray(0) }
        recordingOutput = null
        if (pcmBytes.size < AndroidCodec2.SAMPLE_RATE / 2) {
            onError("Voice note terlalu pendek")
            return
        }
        Thread {
            runCatching {
                val pcm = ShortArray(pcmBytes.size / 2) { index ->
                    val low = pcmBytes[index * 2].toInt() and 0xff
                    val high = pcmBytes[index * 2 + 1].toInt()
                    ((high shl 8) or low).toShort()
                }
                AndroidCodec2.encode(pcm)
            }.onSuccess { bytes ->
                runOnUiThread {
                    if (bytes.size > MeshMediaCodec.MAX_MEDIA_BYTES) {
                        onError("Voice note terlalu besar untuk jaringan LoRa")
                    } else {
                        onRecorded(
                            ChatAttachment(
                                "voice-${System.currentTimeMillis()}.${AndroidCodec2.EXTENSION}",
                                AndroidCodec2.MIME,
                                bytes,
                                ChatMessageKind.Voice,
                                duration,
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                runOnUiThread { onError(error.message ?: "Voice note gagal dikompresi") }
            }
        }.apply { name = "Meshta-codec2-encoder"; start() }
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
        if (mimeType.equals(AndroidCodec2.MIME, ignoreCase = true)) {
            playCodec2Voice(bytes, onStarted, onFinished, onError)
            return
        }
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
        codec2PlaybackToken += 1
        runCatching { codec2Player?.stop() }
        runCatching { codec2Player?.release() }
        codec2Player = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playbackFile?.delete()
        playbackFile = null
        playbackFinished?.let { playbackFinished = null; it() }
    }

    private fun beginRecording() {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(AndroidCodec2.SAMPLE_RATE, channel, encoding)
        if (minBuffer <= 0) {
            mediaError?.invoke("Perangkat tidak mendukung rekaman suara 8 kHz")
            return
        }
        val bufferSize = maxOf(minBuffer / 2, 1_024)
        val activeRecorder = AudioRecord.Builder()
            .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(AndroidCodec2.SAMPLE_RATE)
                    .setChannelMask(channel)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .build()
        runCatching {
            check(activeRecorder.state == AudioRecord.STATE_INITIALIZED) { "Perekam suara gagal diinisialisasi" }
            val output = ByteArrayOutputStream()
            recordingOutput = output
            isRecordingVoice = true
            activeRecorder.startRecording()
            recordingThread = Thread {
                val buffer = ShortArray(bufferSize)
                while (isRecordingVoice) {
                    val count = activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    synchronized(this) {
                        repeat(count) { index ->
                            val sample = buffer[index].toInt()
                            output.write(sample and 0xff)
                            output.write((sample ushr 8) and 0xff)
                        }
                    }
                }
            }.apply { name = "Meshta-voice-recorder"; start() }
        }.onSuccess {
            recorder = activeRecorder
            recordingStartedAt = System.currentTimeMillis()
        }.onFailure { error ->
            isRecordingVoice = false
            activeRecorder.release()
            recordingOutput = null
            mediaError?.invoke(error.message ?: "Perekam suara gagal dimulai")
        }
    }

    private fun playCodec2Voice(
        bytes: ByteArray,
        onStarted: (durationMs: Long) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val token = ++codec2PlaybackToken
        playbackFinished = onFinished
        Thread {
            runCatching {
                val pcm = AndroidCodec2.decode(bytes)
                require(pcm.isNotEmpty()) { "Voice note Codec2 kosong" }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AndroidCodec2.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (token != codec2PlaybackToken) {
                    track.release()
                    return@runCatching
                }
                codec2Player = track
                val durationMs = pcm.size * 1_000L / AndroidCodec2.SAMPLE_RATE
                runOnUiThread {
                    if (token == codec2PlaybackToken) {
                        requestAudioFocus()
                        track.play()
                        onStarted(durationMs.coerceAtLeast(1))
                    }
                }
                while (token == codec2PlaybackToken && track.playbackHeadPosition < pcm.size) {
                    Thread.sleep(25)
                }
                runOnUiThread { if (token == codec2PlaybackToken) releasePlayer() }
            }.onFailure { error ->
                runOnUiThread {
                    if (token == codec2PlaybackToken) {
                        releasePlayer()
                        onError(error.message ?: "Voice note Codec2 gagal diputar")
                    }
                }
            }
        }.apply { name = "Meshta-codec2-player"; start() }
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


