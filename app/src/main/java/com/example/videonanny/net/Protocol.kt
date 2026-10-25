package com.example.videonanny.net

/** Wire format shared by both sides. */
object Protocol {
    const val VIDEO_PORT = 8080
    const val AUDIO_PORT = 8081
    const val DISCOVERY_PORT = 8899
    const val MAGIC = "VNANNY1"

    const val SAMPLE_RATE = 16000
    const val AUDIO_CHUNK = 3200          // 100 ms of 16-bit mono
    const val MAX_FRAME_BYTES = 2_000_000
}
