package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.StreamFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the SoundCloud module that can be checked without a network: reading the player's
 * key out of its javascript, reading a search answer, and choosing which transcoding to open.
 */
class SoundCloudModuleTest {

    private val module = SoundCloudModule()

    @Test
    fun `reads the client id out of a player bundle`() {
        val bundle = """var a={api:{client_id:"mCvc6pfl077AipUMKehAWm2ICrAJ1xiU",locale:"en"}};"""

        assertEquals("mCvc6pfl077AipUMKehAWm2ICrAJ1xiU", module.clientIdIn(bundle))
    }

    @Test
    fun `reads a client id written with an equals sign`() {
        val bundle = "window.__sc={client_id='0123456789abcdefghijklmnopqrstuv'};"

        assertEquals("0123456789abcdefghijklmnopqrstuv", module.clientIdIn(bundle))
    }

    @Test
    fun `charts omit unsupported region parameter and cap page size`() {
        val query = module.chartsQuery(10_000)
        assertNull(query["region"])
        assertEquals("200", query["limit"])
        assertEquals("trending", query["kind"])
    }

    @Test
    fun `transient retry delay is bounded and respects retry after`() {
        assertEquals(400L, module.apiRetryDelayMs(0))
        assertEquals(4_000L, module.apiRetryDelayMs(1, "30"))
        assertEquals(250L, module.apiRetryDelayMs(2, "0"))
    }

    @Test
    fun `normalizes escaped and protocol relative player bundle urls`() {
        assertEquals(
            listOf(
                "https://a-v2.sndcdn.com/assets/main.js?v=2",
                "https://a-v2.sndcdn.com/assets/next.js",
            ),
            module.bundleUrlsIn("""src=\/\/a-v2.sndcdn.com/assets/main.js?v=2 src=//a-v2.sndcdn.com/assets/next.js"""),
        )
    }

    @Test
    fun `a bundle with no key gives nothing`() {
        assertNull(module.clientIdIn("var a={locale:'en'};"))
    }

    @Test
    fun `reads every upload in a search answer`() {
        val tracks = module.tracksIn(searchAnswer)

        assertEquals(2, tracks.size)
        val first = tracks.first()
        assertEquals("1106469217", first.catalog.id)
        assertEquals("Tuyển Tập Ca Khúc Hay Nhất", first.catalog.title)
        assertEquals("Sơn Nguyễn", first.catalog.artist)
        // full_duration is the whole upload; duration is what the web player shows, which can clip a preview
        assertEquals(4_126_354L, first.catalog.durationMs)
        assertTrue(first.streamable)
        assertEquals(3, first.transcodings.size)
        assertEquals("auth-token", first.authorization)
    }

    @Test
    fun `Vietnam discovery is keyword search not fake country chart`() {
        assertEquals("nhạc Việt", SoundCloudModule.ChartRegion.VN.discoveryQuery)
        assertNull(module.chartsQuery(20)["region"])
    }

    @Test
    fun `public comments preserve author timestamp and skip malformed entries`() {
        val parsed = module.commentsIn("""{"collection":[{"id":1,"body":"Hay quá","timestamp":4500,"user":{"username":"Listener"}},{"id":2,"body":""},{"body":"missing id"}]}""")
        assertEquals(1, parsed.size)
        assertEquals("Hay quá", parsed.single().body)
        assertEquals("Listener", parsed.single().author)
        assertEquals(4500L, parsed.single().timestampMs)
    }

    @Test
    fun `track exposes public counts without fabricating missing values`() {
        val track = module.tracksIn("""{"collection":[{"id":7,"title":"Track","likes_count":120,"comment_count":4,"permalink_url":"https://soundcloud.com/artist/song"}]}""").single()
        assertEquals(120L, track.likesCount)
        assertEquals(4L, track.commentCount)
        assertEquals("https://soundcloud.com/artist/song", track.permalinkUrl)
        assertNull(module.tracksIn("""{"collection":[{"id":8,"title":"No stats"}]}""").single().likesCount)
    }

    @Test
    fun `reads a playlist the way the browse screen lists it`() {
        val playlist = module.playlistOf(
            Json.parseToJsonElement(playlistAnswer).jsonObject,
        )

        assertEquals("352480527", playlist?.id)
        assertEquals("Lofi Chill", playlist?.title)
        assertEquals("DiviSoul", playlist?.artist)
        assertEquals(120, playlist?.trackCount)
        // the screen shows the 500px cover, not the 100px one the api hands out
        assertEquals("https://i1.sndcdn.com/artworks-69w3TzoluUm8kQIy-D5uwGw-t500x500.jpg", playlist?.artworkUrl)
    }

    @Test
    fun `a playlist with no title is not listed`() {
        assertNull(module.playlistOf(Json.parseToJsonElement("""{"id":7}""").jsonObject))
    }

    @Test
    fun `reads the tracks out of a charts answer`() {
        val tracks = module.chartTracks(chartsAnswer)

        assertEquals(1, tracks.size)
        assertEquals("2211456898", tracks.first().catalog.id)
        assertEquals("Midnight Drive", tracks.first().catalog.title)
        assertEquals("Neon Kite", tracks.first().catalog.artist)
    }

    @Test
    fun `a charts answer without a collection lists nothing`() {
        assertTrue(module.chartTracks("{}").isEmpty())
    }

    @Test
    fun `an upload the uploader stopped streaming is still listed`() {
        val second = module.tracksIn(searchAnswer).last()

        assertEquals(false, second.streamable)
    }

    @Test
    fun `best quality opens the adaptive transcoding first`() {
        val ordered = module.ordered(tracks().first().transcodings, SoundCloudModule.OptionBest)

        assertEquals(listOf("abr_sq", "aac_96k", "mp3_1_0"), ordered.map { it.preset })
    }

    @Test
    fun `data saver opens the 96k transcoding first`() {
        val ordered = module.ordered(tracks().first().transcodings, SoundCloudModule.OptionDataSaver)

        assertEquals(listOf("aac_96k", "abr_sq", "mp3_1_0"), ordered.map { it.preset })
    }

    @Test
    fun `a playlist link is hls and a file link is not`() {
        assertEquals(StreamFormat.Hls, module.formatOf("https://playback.media-streaming.soundcloud.cloud/a/aac_96k/x.m3u8?token=1"))
        assertEquals(StreamFormat.Progressive, module.formatOf("https://cf-media.sndcdn.com/a.mp3?token=1"))
    }

    @Test
    fun `the quality label and codec follow the transcoding`() {
        assertEquals("AAC ~128", module.qualityLabel("abr_sq"))
        assertEquals("AAC 96", module.qualityLabel("aac_96k"))
        assertEquals("AAC", module.codecOf("abr_sq"))
        assertEquals("AAC", module.codecOf("aac_96k"))
        assertEquals("MP3", module.codecOf("mp3_1_0"))
    }

    private fun tracks() = module.tracksIn(searchAnswer)

    /** the shape api-v2 answers a track search with, cut down to the fields the module reads */
    private val searchAnswer = """
        {"collection":[
          {"id":1106469217,"title":"Tuyển Tập Ca Khúc Hay Nhất","streamable":true,
           "track_authorization":"auth-token","duration":4126371,"full_duration":4126354,
           "user":{"username":"Sơn Nguyễn"},
           "media":{"transcodings":[
             {"preset":"aac_96k","url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:1106469217/x/stream/hls"},
             {"preset":"abr_sq","url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:1106469217/y/stream/hls"},
             {"preset":"mp3_1_0","url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:1106469217/z/stream/progressive"}
           ]}},
          {"id":222,"title":"Một bài đã tắt phát trực tuyến","streamable":false,
           "track_authorization":"other-token","duration":180000,
           "user":{"username":"Ai Đó"},"media":null}
        ]}
    """.trimIndent()

    private val playlistAnswer = """
        {
          "artwork_url": "https://i1.sndcdn.com/artworks-69w3TzoluUm8kQIy-D5uwGw-large.jpg",
          "id": 352480527,
          "kind": "playlist",
          "title": "Lofi Chill",
          "track_count": 120,
          "user": {"username": "DiviSoul"}
        }
    """.trimIndent()

    private val chartsAnswer = """
        {
          "genre": "soundcloud:genres:all-music",
          "kind": "trending",
          "collection": [
            {
              "track": {
                "id": 2211456898,
                "title": "Midnight Drive",
                "full_duration": 214000,
                "streamable": true,
                "user": {"username": "Neon Kite"},
                "media": {"transcodings": [
                  {"preset": "aac_96k", "url": "https://api-v2.soundcloud.com/media/1", "format": {"protocol": "hls"}}
                ]}
              }
            }
          ]
        }
    """.trimIndent()
}

class SoundCloudRetryTest {
    @Test
    fun `401 refreshes the cached client id before repeating the catalog call`() {
        val host = RefreshHost()
        val module = SoundCloudModule().also { it.attach(host) }

        val tracks = module.search("lofi")

        assertEquals(1, tracks.size)
        assertEquals(listOf("oldClientId000000000000000000000", "newClientId000000000000000000000"), host.clientIds)
    }

    @Test
    fun `429 catalog response is retried and search succeeds`() {
        val host = RetryHost()
        val module = SoundCloudModule().also { it.attach(host) }

        val tracks = module.search("lofi")

        assertEquals(2, host.searchCalls)
        assertEquals("Retry works", tracks.single().title)
    }

    private class RefreshHost : com.mochyy.mochymusic.module.ModuleHost {
        private val values = mutableMapOf("client_id" to "oldClientId000000000000000000000", "client_id_at" to System.currentTimeMillis().toString())
        val clientIds = mutableListOf<String>()
        override val storage = object : com.mochyy.mochymusic.module.ModuleStorage {
            override fun get(key: String): String? = values[key]
            override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }
        override val folder = java.io.File(System.getProperty("java.io.tmpdir"), "sc-refresh").apply { mkdirs() }
        override fun log(message: String) = Unit
        override fun http(request: com.mochyy.mochymusic.module.HttpRequest): com.mochyy.mochymusic.module.HttpResponse {
            val url = request.url
            return when {
                url.startsWith("https://soundcloud.com/") -> com.mochyy.mochymusic.module.HttpResponse(200, "<script src=\"https://a-v2.sndcdn.com/assets/1.js\"></script>".toByteArray())
                url.contains("a-v2.sndcdn.com/assets") -> com.mochyy.mochymusic.module.HttpResponse(200, "client_id=\"newClientId000000000000000000000\"".toByteArray())
                url.contains("/search/tracks") -> {
                    val id = url.substringAfter("client_id=").substringBefore('&')
                    clientIds += id
                    if (id.startsWith("old")) com.mochyy.mochymusic.module.HttpResponse(401, "{}".toByteArray())
                    else com.mochyy.mochymusic.module.HttpResponse(200, """{"collection":[{"id":7,"title":"Refresh works","streamable":true,"user":{"username":"Artist"},"media":{"transcodings":[]}}]}""".toByteArray())
                }
                else -> com.mochyy.mochymusic.module.HttpResponse(404, "{}".toByteArray())
            }
        }
    }

    private class RetryHost : com.mochyy.mochymusic.module.ModuleHost {
        private val values = mutableMapOf<String, String>()
        var searchCalls = 0
        override val storage = object : com.mochyy.mochymusic.module.ModuleStorage {
            override fun get(key: String): String? = values[key]
            override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }
        override val folder = java.io.File(System.getProperty("java.io.tmpdir"), "sc-retry").apply { mkdirs() }
        override fun log(message: String) = Unit
        override fun http(request: com.mochyy.mochymusic.module.HttpRequest): com.mochyy.mochymusic.module.HttpResponse {
            val url = request.url
            if (url.startsWith("https://soundcloud.com/")) {
                return com.mochyy.mochymusic.module.HttpResponse(200, "<script src=\"https://a-v2.sndcdn.com/assets/1.js\"></script>".toByteArray())
            }
            if (url.contains("a-v2.sndcdn.com/assets")) {
                return com.mochyy.mochymusic.module.HttpResponse(200, "client_id=\"0123456789abcdefghijklmnopqrstuv\"".toByteArray())
            }
            if (url.contains("/search/tracks")) {
                searchCalls++
                if (searchCalls == 1) return com.mochyy.mochymusic.module.HttpResponse(429, "{}".toByteArray(), mapOf("retry-after" to "0"))
                return com.mochyy.mochymusic.module.HttpResponse(200, """{"collection":[{"id":7,"title":"Retry works","streamable":true,"user":{"username":"Artist"},"media":{"transcodings":[]}}]}""".toByteArray())
            }
            return com.mochyy.mochymusic.module.HttpResponse(404, "{}".toByteArray())
        }
    }
}

/** The whole path the player takes, against a stand-in for SoundCloud rather than the network. */
class SoundCloudResolveTest {

    private val module = SoundCloudModule()

    @Test
    fun `a track the player is playing comes back as an hls stream`() = kotlinx.coroutines.runBlocking {
        module.attach(FakeHost())
        val result = module.resolve(
            com.mochyy.mochymusic.module.TrackQuery(
                title = "Tuyển Tập Ca Khúc Hay Nhất",
                artists = listOf("Sơn Nguyễn"),
                durationMs = 4_126_354L,
            ),
            emptyMap(),
        )

        val found = result as com.mochyy.mochymusic.module.ResolveResult.Found
        assertEquals(StreamFormat.Hls, found.stream.format)
        assertTrue(found.stream.url.startsWith("https://playback."))
        assertEquals("SoundCloud", found.stream.sourceLabel)
        assertEquals("AAC ~128", found.stream.qualityLabel)
        assertEquals("AAC", found.stream.codec)
        assertEquals("1106469217", found.stream.matched.id)
    }

    @Test
    fun `an upload with no playable stream is reported, not returned`() = kotlinx.coroutines.runBlocking {
        module.attach(FakeHost(streamAnswer = """{"status":"no stream"}"""))
        val result = module.resolve(
            com.mochyy.mochymusic.module.TrackQuery("Tuyển Tập Ca Khúc Hay Nhất", listOf("Sơn Nguyễn")),
            emptyMap(),
        )

        assertTrue(result is com.mochyy.mochymusic.module.ResolveResult.Unavailable)
    }

    private class FakeHost(private val streamAnswer: String = """{"url":"https://playback.media-streaming.soundcloud.cloud/a/aac_96k/x.m3u8?token=1"}""") :
        com.mochyy.mochymusic.module.ModuleHost {
        private val values = mutableMapOf<String, String>()

        override val storage = object : com.mochyy.mochymusic.module.ModuleStorage {
            override fun get(key: String): String? = values[key]
            override fun set(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        override fun log(message: String) {}

        override val folder: java.io.File = kotlin.io.path.createTempDirectory("soundcloud-test").toFile()

        override fun http(request: com.mochyy.mochymusic.module.HttpRequest): com.mochyy.mochymusic.module.HttpResponse {
            val body = when {
                request.url.startsWith("https://soundcloud.com/") -> """<script src="https://a-v2.sndcdn.com/assets/55-d25a9296.js"></script>"""
                request.url.contains("a-v2.sndcdn.com/assets") -> """var a={client_id:"mCvc6pfl077AipUMKehAWm2ICrAJ1xiU"};"""
                request.url.contains("/search/tracks") -> SEARCH_ANSWER
                request.url.contains("/media/") -> streamAnswer
                else -> ""
            }
            return com.mochyy.mochymusic.module.HttpResponse(200, body.toByteArray())
        }

        companion object {
            private val SEARCH_ANSWER = """
                {"collection":[{"id":1106469217,"title":"Tuyển Tập Ca Khúc Hay Nhất","streamable":true,
                 "track_authorization":"auth","full_duration":4126354,"user":{"username":"Sơn Nguyễn"},
                 "media":{"transcodings":[
                   {"preset":"aac_96k","url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:1106469217/x/stream/hls"},
                   {"preset":"abr_sq","url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:1106469217/y/stream/hls"}]}}]}
            """.trimIndent()
        }
    }
}
