package com.example.videonanny.server

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.example.videonanny.net.Protocol
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Server side of the link: accepts the camera stream, decodes frames,
 * plays incoming audio and can push the phone's microphone back to the camera.
 */
class NannyServer(
    private val onFrame: (ByteArray, Int) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val running = AtomicBoolean(false)
    private var videoServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null
    private var audioSocket: Socket? = null
    private var talkbackOut: OutputStream? = null

    private val talkbackOn = AtomicBoolean(false)
    private var talkbackThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        thread(name = "videoAccept") {
            try {
                val server = ServerSocket(Protocol.VIDEO_PORT)
                videoServer = server
                onStatus("Ожидание камеры…")
                while (running.get()) {
                    val socket = try { server.accept() } catch (t: Throwable) { break }
                    onStatus("Камера подключена")
                    thread(name = "videoRead") { readVideo(socket) }
                }
            } catch (t: Throwable) {
                if (running.get()) onStatus("Порт ${Protocol.VIDEO_PORT} занят: ${t.message}")
            }
        }

        thread(name = "audioAccept") {
            try {
                val server = ServerSocket(Protocol.AUDIO_PORT)
                audioServer = server
                while (running.get()) {
                    val socket = try { server.accept() } catch (t: Throwable) { break }
                    audioSocket = socket
                    talkbackOut = socket.getOutputStream()
                    thread(name = "audioPlay") { readAudio(socket) }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun readVideo(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            while (running.get()) {
                val length = input.readInt()
                if (length <= 1 || length > Protocol.MAX_FRAME_BYTES) break
                val rotation = input.readByte().toInt()
                val jpeg = ByteArray(length - 1)
                input.readFully(jpeg)
                onFrame(jpeg, rotation)
            }
        } catch (t: Throwable) {
            // client went away
        } finally {
            try { socket.close() } catch (t: Throwable) {}
            if (running.get()) onStatus("Камера отключилась")
        }
    }

    private fun readAudio(socket: Socket) {
        var track: AudioTrack? = null
        try {
            val bufSize = AudioTrack.getMinBufferSize(
                Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bufSize, Protocol.AUDIO_CHUNK * 4),
                AudioTrack.MODE_STREAM
            )
            track.play()
            val input = socket.getInputStream()
            val buf = ByteArray(Protocol.AUDIO_CHUNK)
            while (running.get()) {
                val read = input.read(buf)
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

    /** Push this phone's microphone to the camera device ("talk back"). */
    fun setTalkback(enabled: Boolean) {
        if (enabled == talkbackOn.get()) return
        talkbackOn.set(enabled)
        if (enabled) {
            talkbackThread = thread(name = "talkback") {
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
                    while (running.get() && talkbackOn.get()) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            try {
                                talkbackOut?.let {
                                    it.write(buf, 0, n)
                                    it.flush()
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
        } else {
            talkbackThread?.interrupt()
            talkbackThread = null
        }
    }

    fun stop() {
        running.set(false)
        talkbackOn.set(false)
        try { videoServer?.close() } catch (t: Throwable) {}
        try { audioServer?.close() } catch (t: Throwable) {}
        try { audioSocket?.close() } catch (t: Throwable) {}
        videoServer = null
        audioServer = null
        audioSocket = null
        talkbackOut = null
    }
}
