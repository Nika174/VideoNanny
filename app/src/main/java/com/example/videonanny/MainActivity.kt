package com.example.videonanny

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.videonanny.client.CameraClientActivity
import com.example.videonanny.server.ServerActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            startActivity(Intent(this, CameraClientActivity::class.java))
        }
        findViewById<Button>(R.id.btnScreen).setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }

        showCrashLogIfAny()
    }

    private fun showCrashLogIfAny() {
        val file = File(getExternalFilesDir(null) ?: filesDir, "crash.txt")
        if (!file.exists()) return
        val text = try { file.readText() } catch (e: Throwable) { return }
        file.delete()
        AlertDialog.Builder(this)
            .setTitle("Прошлый запуск упал")
            .setMessage(text.take(3500))
            .setPositiveButton("OK", null)
            .setNeutralButton("Скопировать") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
