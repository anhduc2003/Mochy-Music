package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI

class SoundCloudCompletionTest {
    private val module = SoundCloudModule()

    @Test fun `comment page uses raw row count for offset and honors explicit end`() {
        val full = """{"collection":[{"id":1,"body":"one"},{"id":2,"body":""}],"next_href":"https://example.test/untrusted?offset=999"}"""
        val page = module.commentPageIn(full, 2, 0)
        assertEquals(listOf("1"), page.comments.map { it.id })
        assertEquals(2, page.nextOffset)
        assertNull(module.commentPageIn("""{"collection":[{"id":1,"body":"one"}],"next_href":null}""", 1, 2).nextOffset)
        assertNull(module.commentPageIn("""{"collection":[]}""", 1, 2).nextOffset)
    }

    @Test fun `malformed comment response is an error not empty success`() {
        assertTrue(runCatching { module.commentPageIn("<html>gateway error</html>", 50, 0) }.exceptionOrNull() is SoundCloudModule.SoundCloudResponseException)
        assertTrue(runCatching { module.commentPageIn("{}", 50, 0) }.exceptionOrNull() is SoundCloudModule.SoundCloudResponseException)
    }

    @Test fun `media request preserves signed query and encodes authorization`() {
        val url = module.mediaRequestUrl("https://api-v2.soundcloud.com/media/7?existing=yes#unused", "client", "a+b /c")
        assertTrue(url.contains("?existing=yes&client_id=client&track_authorization=a%2Bb+%2Fc"))
        assertFalse(url.contains('#'))
    }

    @Test fun `same quality fallback is retained and protocol marks extensionless HLS`() {
        val host = PlaybackHost()
        module.attach(host)
        val stream = module.streamFor("7")
        assertNotNull(stream)
        assertEquals(StreamFormat.Hls, stream!!.format)
        assertEquals(2, host.mediaCalls.size)
        assertTrue(host.mediaCalls.all { it.contains("?existing=yes&client_id=") })
        assertTrue(host.mediaCalls[0].contains("/media/first"))
        assertTrue(host.mediaCalls[1].contains("/media/second"))
    }

    @Test fun `catalog malformed payload remains retryable error`() {
        module.attach(PlaybackHost(malformedSearch = true))
        assertTrue(runCatching { module.search("song") }.exceptionOrNull() is SoundCloudModule.SoundCloudResponseException)
    }

    private class PlaybackHost(val malformedSearch: Boolean = false) : ModuleHost {
        val mediaCalls = mutableListOf<String>()
        private val data = mutableMapOf("client_id" to "0123456789abcdefghijklmnopqrstuv", "client_id_at" to System.currentTimeMillis().toString())
        override val storage = object : ModuleStorage {
            override fun get(key: String) = data[key]
            override fun set(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        }
        override val folder = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        override fun log(message: String) = Unit
        override fun http(request: HttpRequest): HttpResponse {
            val path = URI(request.url).path
            val body = when (path) {
                "/tracks/7" -> """{"id":7,"title":"Song","streamable":true,"track_authorization":"auth","media":{"transcodings":[{"preset":"abr_sq","url":"https://api-v2.soundcloud.com/media/first?existing=yes","format":{"protocol":"hls"}},{"preset":"abr_sq","url":"https://api-v2.soundcloud.com/media/second?existing=yes","format":{"protocol":"hls"}}]}}"""
                "/media/first" -> {
                    mediaCalls += request.url
                    return HttpResponse(403, "{}".toByteArray())
                }
                "/media/second" -> {
                    mediaCalls += request.url
                    """{"url":"https://cdn.example.test/manifest?token=1"}"""
                }
                "/search/tracks" -> if (malformedSearch) "{}" else """{"collection":[]}"""
                else -> error("Unexpected path $path")
            }
            return HttpResponse(200, body.toByteArray())
        }
    }
}
