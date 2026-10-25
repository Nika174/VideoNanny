package com.example.videonanny.net

/** Tiny shared state so activities can show what the service is doing. */
object StreamStatus {
    @Volatile var state: String = "Не подключено"
    @Volatile var serverIp: String = ""
    @Volatile var fps: Int = 0
    @Volatile var sentMb: Double = 0.0
    @Volatile var running: Boolean = false

    fun reset() {
        state = "Не подключено"
        fps = 0
        sentMb = 0.0
        running = false
    }
}
