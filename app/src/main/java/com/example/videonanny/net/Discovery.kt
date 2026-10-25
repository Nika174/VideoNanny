package com.example.videonanny.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Server side: shouts "I'm here" on the LAN so the camera can find it automatically. */
class DiscoveryAnnouncer(private val ctx: Context) {

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val target = InetAddress.getByName("255.255.255.255")
                val payload = "${Protocol.MAGIC}|${Protocol.VIDEO_PORT}".toByteArray()
                while (isActive) {
                    try {
                        socket?.send(DatagramPacket(payload, payload.size, target, Protocol.DISCOVERY_PORT))
                    } catch (t: Throwable) {
                        // keep trying
                    }
                    delay(1500)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    fun stop() {
        job?.cancel()
        try { socket?.close() } catch (t: Throwable) {}
        socket = null
    }
}

/** Camera side: listens for the announcer and reports the server IP. */
class DiscoveryListener(private val ctx: Context) {

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    fun start(scope: CoroutineScope, onFound: (String) -> Unit) {
        job = scope.launch(Dispatchers.IO) {
            var lock: WifiManager.MulticastLock? = null
            try {
                val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("vnanny")?.apply { setReferenceCounted(false); acquire() }

                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(Protocol.DISCOVERY_PORT))
                    soTimeout = 2000
                }
                val buf = ByteArray(256)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket?.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith(Protocol.MAGIC)) {
                            onFound(packet.address.hostAddress ?: continue)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // keep listening
                    } catch (t: Throwable) {
                        if (!isActive) break
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                try { lock?.release() } catch (t: Throwable) {}
                try { socket?.close() } catch (t: Throwable) {}
            }
        }
    }

    fun stop() {
        job?.cancel()
        try { socket?.close() } catch (t: Throwable) {}
        socket = null
    }
}
