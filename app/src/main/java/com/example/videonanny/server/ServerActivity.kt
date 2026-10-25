package com.example.videonanny.server

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videonanny.R
import com.example.videonanny.net.DiscoveryAnnouncer
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.min

/** Viewer device: receives the camera stream, plays its audio, can talk back. */
class ServerActivity : AppCompatActivity() {

    private lateinit var surface: SurfaceView
    private lateinit var tvState: TextView
    private lateinit var tvIp: TextView
    private lateinit var btnTalk: MaterialButton

    private lateinit var server: NannyServer
    private var announcer: DiscoveryAnnouncer? = null

    private val renderThread = HandlerThread("render")
    private lateinit var renderHandler: Handler

    @Volatile private var pendingFrame: Pair<ByteArray, Int>? = null
    @Volatile private var rendering = false

    private var frames = 0
    private var fps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        surface = findViewById(R.id.surface)
        tvState = findViewById(R.id.tvState)
        tvIp = findViewById(R.id.tvIp)
        btnTalk = findViewById(R.id.btnTalk)

        renderThread.start()
        renderHandler = Handler(renderThread.looper)

        lifecycleScope.launch {
            val ip = localIp()
            tvIp.text = "Адрес этого устройства: $ip\nПорты ${8080}/${8081}"
        }

        announcer = DiscoveryAnnouncer(this).also { it.start(lifecycleScope) }

        server = NannyServer(
            onFrame = { jpeg, rotation -> submitFrame(jpeg, rotation) },
            onStatus = { text -> runOnUiThread { tvState.text = text } }
        )
        server.start()

        btnTalk.setOnClickListener {
            if (!hasMic()) {
                askMic()
                return@setOnClickListener
            }
            val next = !talkEnabled
            talkEnabled = next
            server.setTalkback(next)
            btnTalk.text = if (next) "Микрофон: ВКЛ" else "Микрофон: выкл"
            btnTalk.setBackgroundColor(
                ContextCompat.getColor(this, if (next) R.color.primary else R.color.surface_2)
            )
            btnTalk.setTextColor(
                ContextCompat.getColor(this, if (next) R.color.on_primary else R.color.text_primary)
            )
        }

        try {
            val am = getSystemService(AudioManager::class.java)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } catch (t: Throwable) {
        }

        startFpsLoop()
        if (!hasMic()) askMic()
    }

    private var talkEnabled = false

    private fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun askMic() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), 501
        )
    }

    private fun submitFrame(jpeg: ByteArray, rotation: Int) {
        pendingFrame = jpeg to rotation
        if (!rendering) {
            rendering = true
            renderHandler.post(renderLoop)
        }
    }

    private val renderLoop = object : Runnable {
        override fun run() {
            val frame = pendingFrame
            if (frame == null) {
                rendering = false
                return
            }
            pendingFrame = null
            try {
                drawFrame(frame.first, frame.second)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            renderHandler.post(this)
        }
    }

    private fun drawFrame(jpeg: ByteArray, rotation: Int) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val holder = surface.holder
        if (holder.surface == null || !holder.surface.isValid) return

        val canvas: Canvas = try {
            holder.lockCanvas() ?: return
        } catch (t: Throwable) {
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            val rotatedDims = if (rotation % 180 == 0) {
                bmp.width to bmp.height
            } else {
                bmp.height to bmp.width
            }
            val scale = min(
                canvas.width.toFloat() / rotatedDims.first,
                canvas.height.toFloat() / rotatedDims.second
            )
            canvas.save()
            canvas.translate(canvas.width / 2f, canvas.height / 2f)
            canvas.rotate(rotation.toFloat())
            canvas.scale(scale, scale)
            canvas.translate(-bmp.width / 2f, -bmp.height / 2f)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            canvas.restore()
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (t: Throwable) {}
        }

        frames++
        findViewById<View>(R.id.waiting).visibility = View.GONE
    }

    private fun startFpsLoop() {
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                fps = frames
                frames = 0
                if (tvState.text.startsWith("Камера подключена") || fps > 0) {
                    tvState.text = "Камера подключена · $fps кадр/с"
                }
            }
        }
    }

    private suspend fun localIp(): String = withContext(Dispatchers.IO) {
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress ?: "нет сети"
        } catch (t: Throwable) {
            "нет сети"
        }
    }

    override fun onDestroy() {
        server.stop()
        announcer?.stop()
        try { renderThread.quitSafely() } catch (t: Throwable) {}
        try {
            val am = getSystemService(AudioManager::class.java)
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (t: Throwable) {}
        super.onDestroy()
    }
}
