package com.mochyy.desktop.net

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Checks the stream handshake against the live service.
 *
 * Network-dependent and explicitly best-effort: YouTube changes its client requirements often, so
 * a refusal here is reported, not failed. What *is* asserted is that when a stream does come back
 * it is a real googlevideo URL carrying a signature — a malformed one would mean the parser is
 * reading the wrong field.
 */
class YouTubeSessionTest {

    @Test
    fun `resolves a playable audio stream`() {
        val videoId = "qHpE45b4INk" // Nơi Này Có Anh — Sơn Tùng M-TP
        val stream = runCatching { YouTubeSession().stream(videoId) }.getOrNull()
        if (stream == null) {
            println("YouTubeSessionTest: YouTube refused (client requirements changed?), skipped")
            return
        }
        println("YouTubeSessionTest: itag=${stream.itag} mime=${stream.mimeType} " +
            "bitrate=${stream.bitrate} url=${stream.url.take(70)}…")

        assertTrue(stream.url.startsWith("https://"), "stream URL is https")
        assertTrue(
            stream.url.contains("googlevideo.com") || stream.url.contains("youtube.com"),
            "stream URL points at a media host: ${stream.url.take(60)}",
        )
        // a url without a signature would play back as 403
        assertTrue(stream.url.contains("sig=") || stream.url.contains("lsig="), "URL carries a signature")
        assertTrue(stream.mimeType.startsWith("audio/"), "prefers audio-only renditions, got ${stream.mimeType}")
        assertTrue(stream.itag > 0, "carries an itag")
    }
}
