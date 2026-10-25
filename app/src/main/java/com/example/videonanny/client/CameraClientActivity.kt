package com.example.videonanny.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videonanny.R
import com.example.videonanny.net.DiscoveryListener
import com.example.videonanny.net.StreamStatus
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Camera device: points at the child, streams video + mic to the viewer. */
class CameraClientActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var etIp: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnFind: MaterialButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var discovery: DiscoveryListener? = null
    private val prefs by lazy { getSharedPreferences("vnanny", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        previewView = findViewById(R.id.preview)
        etIp = findViewById(R.id.etIp)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnFind = findViewById(R.id.btnFind)

        etIp.setText(prefs.getString("ip", ""))

        btnFind.setOnClickListener { findServer() }

        btnStart.setOnClickListener {
            val ip = etIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Введите IP или нажмите «Найти»", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putString("ip", ip).apply()
            if (ensurePermissions()) {
                stopPreview()
                StreamService.start(this, ip)
                Toast.makeText(this, "Трансляция запускается", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            StreamService.stop(this)
            StreamStatus.reset()
            tvStatus.text = "Остановлено"
            startPreview()
        }

        pollStatus()
        ensurePermissions(silent = true)
    }

    override fun onResume() {
        super.onResume()
        if (!StreamStatus.running) startPreview()
    }

    private fun startPreview() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPreview() {
        try { cameraProvider?.unbindAll() } catch (t: Throwable) {}
    }

    private fun findServer() {
        tvStatus.text = "Ищу сервер в Wi-Fi…"
        discovery?.stop()
        discovery = DiscoveryListener(this).also { d ->
            d.start(lifecycleScope) { ip ->
                runOnUiThread {
                    etIp.setText(ip)
                    tvStatus.text = "Найден сервер: $ip"
                    Toast.makeText(this, "Сервер найден: $ip", Toast.LENGTH_SHORT).show()
                    d.stop()
                }
            }
        }
        lifecycleScope.launch {
            delay(15000)
            discovery?.stop()
            if (tvStatus.text.toString().startsWith("Ищу")) {
                tvStatus.text = "Сервер не найден. Откройте режим «Экран» на втором телефоне."
            }
        }
    }

    private fun pollStatus() {
        lifecycleScope.launch {
            while (true) {
                delay(500)
                val text = if (StreamStatus.running) {
                    String.format(
                        Locale.US,
                        "%s\n%d кадр/с · %.1f МБ%s",
                        StreamStatus.state,
                        StreamStatus.fps,
                        StreamStatus.sentMb,
                        if (StreamStatus.serverIp.isNotEmpty()) "\nсервер: ${StreamStatus.serverIp}" else ""
                    )
                } else {
                    StreamStatus.state
                }
                tvStatus.text = text
                btnStart.isEnabled = !StreamStatus.running
                btnStop.isEnabled = StreamStatus.running
            }
        }
    }

    private fun ensurePermissions(silent: Boolean = false): Boolean {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        if (needed.isEmpty()) return true
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), 601)
        if (!silent) {
            Toast.makeText(this, "Разрешите камеру и микрофон", Toast.LENGTH_LONG).show()
        }
        return false
    }

    override fun onDestroy() {
        discovery?.stop()
        super.onDestroy()
    }
}
