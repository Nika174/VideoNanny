package com.example.videonanny.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.videonanny.MainActivity
import com.example.videonanny.R
import com.example.videonanny.net.Protocol
import com.example.videonanny.net.StreamStatus
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service on the camera device: captures video + mic and ships them
 * over TCP; plays back the talk-back audio coming from the viewer device.
 * Runs with the screen off.
 */
class StreamService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.example.videonanny.START"
        const val EXTRA_IP = "ip"
        const val NOTIF_ID = 42

        fun start(ctx: Context, ip: String) {
            val i = Intent(ctx, StreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_IP, ip)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StreamService::class.java))
        }
    }

    private val running = AtomicBoolean(false)
    private val audioLock = Any()

    private var serverIp: String = ""
    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var videoOut: DataOutputStream? = null
    private var cameraExecutor = Executors.newSingleThreadExecutor()
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var lastFrameAt = 0L
    @Volatile private var framesInSecond = 0
    @Volatile private var lastFpsAt = 0L

    @Volatile private var bytesSent = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = buildNotification("Трансляция идёт", "Камера работает")
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                serverIp = intent.getStringExtra(EXTRA_IP) ?: serverIp
                if (serverIp.isNotEmpty()) startStreaming()
            }
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (running.getAndSet(true)) return
        StreamStatus.reset()
        StreamStatus.running = true
        StreamStatus.serverIp = serverIp
        acquireWakeLock()
        setupAudioMode()

        cameraExecutor = Executors.newSingleThreadExecutor()
        thread(name = "connect") {
            try {
                StreamStatus.state = "Подключение к $serverIp…"
                val vSocket = Socket().apply {
                    connect(InetSocketAddress(serverIp, Protocol.VIDEO_PORT), 6000)
                    tcpNoDelay = true
                    sendBufferSize = 512 * 1024
                }
                videoSocket = vSocket
                videoOut = DataOutputStream(vSocket.getOutputStream().buffered())
                StreamStatus.state = "Подключено, камера запускается"

                startAudio()
                startCamera()
            } catch (t: Throwable) {
                StreamStatus.state = "Не удалось подключиться: ${t.message}"
                stopSelf()
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image -> analyze(image) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                StreamStatus.state = "Трансляция идёт"
                updateNotification("Трансляция идёт", "Смотрите на втором устройстве")
            } catch (t: Throwable) {
                StreamStatus.state = "Ошибка камеры: ${t.message}"
                t.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 100) {          // max ~10 fps
            image.close()
            return
        }
        lastFrameAt = now

        val out = videoOut
        if (out == null) { image.close(); return }

        try {
            val nv21 = yuv420ToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val baos = ByteArrayOutputStream(64 * 1024)
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 55, baos)
            val jpeg = baos.toByteArray()
            val rotation = image.imageInfo.rotationDegrees

            synchronized(audioLock) {
                out.writeInt(jpeg.size + 1)
                out.writeByte(rotation)
                out.write(jpeg)
                out.flush()
            }
            bytesSent += jpeg.size + 5L
            StreamStatus.sentMb = bytesSent / (1024.0 * 1024.0)

            framesInSecond++
            if (now - lastFpsAt >= 1000) {
                lastFpsAt = now
                StreamStatus.fps = framesInSecond
                framesInSecond = 0
            }
        } catch (t: Throwable) {
            StreamStatus.state = "Соединение потеряно: ${t.message}"
            disconnect()
        } finally {
            image.close()
        }
    }

    private fun startAudio() {
        try {
            val aSocket = Socket().apply {
                connect(InetSocketAddress(serverIp, Protocol.AUDIO_PORT), 6000)
                tcpNoDelay = true
            }
            audioSocket = aSocket
            val audioOut = aSocket.getOutputStream()
            val audioIn = aSocket.getInputStream()

            micThread = thread(name = "mic") {
                var rec: AudioRecord? = null
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        Protocol.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    rec = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        Protocol.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, Protocol.AUDIO_CHUNK * 4)
                    )
                    rec.startRecording()
                    val buf = ByteArray(Protocol.AUDIO_CHUNK)
                    while (running.get()) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            try {
                                synchronized(audioLock) {
                                    audioOut.write(buf, 0, n)
                                    audioOut.flush()
                                }
                            } catch (t: Throwable) {
                                break
                            }
                        }
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                } finally {
                    try { rec?.stop() } catch (t: Throwable) {}
                    try { rec?.release() } catch (t: Throwable) {}
                }
            }

            playThread = thread(name = "speaker") {
                var track: AudioTrack? = null
                try {
                    val bufSize = AudioTrack.getMinBufferSize(
                        Protocol.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    track = AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        Protocol.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(bufSize, Protocol.AUDIO_CHUNK * 4),
                        AudioTrack.MODE_STREAM
                    )
                    track.play()
                    val buf = ByteArray(Protocol.AUDIO_CHUNK)
                    while (running.get()) {
                        val read = audioIn.read(buf)
                        if (read <= 0) break
                        track.write(buf, 0, read)
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                } finally {
                    try { track?.stop() } catch (t: Throwable) {}
                    try { track?.release() } catch (t: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun disconnect() {
        running.set(false)
        StreamStatus.running = false
        try { videoSocket?.close() } catch (t: Throwable) {}
        try { audioSocket?.close() } catch (t: Throwable) {}
        videoSocket = null
        audioSocket = null
        videoOut = null
    }

    private fun setupAudioMode() {
        try {
            val am = getSystemService(AudioManager::class.java)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vnanny:camera").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                "vnanny_stream",
                "Трансляция",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "vnanny_stream")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(title, text))
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    override fun onDestroy() {
        running.set(false)
        StreamStatus.running = false
        try { cameraExecutor.shutdown() } catch (t: Throwable) {}
        disconnect()
        try { wakeLock?.release() } catch (t: Throwable) {}
        try {
            val am = getSystemService(AudioManager::class.java)
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (t: Throwable) {}
        super.onDestroy()
    }

    /** YUV_420_888 -> NV21 (needed by YuvImage for JPEG encoding). */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.get(nv21, 0, ySize)
            pos = ySize
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until height) {
                val start = r * yRowStride
                if (start >= yBuf.limit()) break
                yBuf.position(start)
                val len = minOf(yRowStride, yBuf.remaining())
                yBuf.get(row, 0, len)
                System.arraycopy(row, 0, nv21, r * width, minOf(width, len))
            }
            pos = ySize
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)
        for (r in 0 until chromaHeight) {
            if (r * uRowStride >= uBuf.limit()) break
            uBuf.position(r * uRowStride)
            uBuf.get(uRow, 0, minOf(uRowStride, uBuf.remaining()))
            vBuf.position(r * vRowStride)
            vBuf.get(vRow, 0, minOf(vRowStride, vBuf.remaining()))
            var c = 0
            while (c < chromaWidth && pos < nv21.size - 1) {
                nv21[pos++] = vRow[c * vPixelStride]
                nv21[pos++] = uRow[c * uPixelStride]
                c++
            }
        }
        return nv21
    }
}
