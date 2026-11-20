package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * the whole module against the real SoundCloud, to see that the browse calls answer today.
 * It needs the network, so it only runs when asked: `./gradlew :modules:soundcloud:test
 * -Dsoundcloud.live=true --tests '*SoundCloudLiveTest*'`.
 */
class SoundCloudLiveTest {

    @Before
    fun onlyWhenAsked() {
        assumeTrue("needs the network: pass -Dsoundcloud.live=true", System.getProperty("soundcloud.live") == "true")
    }

    private val host = object : ModuleHost {
        private val store = mutableMapOf<String, String>()
        override val storage = object : ModuleStorage {
            override fun get(key: String): String? = store[key]
            override fun set(key: String, value: String?) {
                if (value == null) store.remove(key) else store[key] = value
            }
        }
        override val folder: File = File(System.getProperty("java.io.tmpdir"), "soundcloud-live").apply { mkdirs() }
        override fun log(message: String) = Unit
        override fun http(request: HttpRequest): HttpResponse {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResponse(code, body.toByteArray())
        }
    }

    private val module = SoundCloudModule().also { it.attach(host) }

    @Test
    fun `every browse call answers with real data`() {
        val tracks = module.search("lofi")
        println("search(lofi) -> ${tracks.size} uploads, first: ${tracks.firstOrNull()?.title} · ${tracks.firstOrNull()?.artist} · ${tracks.firstOrNull()?.durationMs}ms · art=${tracks.firstOrNull()?.artworkUrl != null}")
        assertTrue("search returned nothing", tracks.isNotEmpty())

        val playlists = module.searchPlaylists("lofi")
        println("searchPlaylists(lofi) -> ${playlists.size} playlists, first: ${playlists.firstOrNull()?.title} · ${playlists.firstOrNull()?.trackCount} tracks")
        assertTrue("playlist search returned nothing", playlists.isNotEmpty())

        val big = playlists.maxByOrNull { it.trackCount }!!
        val inside = module.playlistTracks(big.id)
        println("playlistTracks(${big.id}) -> ${inside.size} uploads of ${big.trackCount} claimed, first: ${inside.firstOrNull()?.title}")
        assertTrue("playlist had no tracks", inside.isNotEmpty())
        // a playlist answers with only its first few uploads whole; the rest come back by id
        assertTrue("only ${inside.size} of ${big.trackCount} tracks were read", inside.size > 5)

        val charts = module.vietnameseDiscovery()
        println("vietnameseDiscovery() -> ${charts.size} uploads, first: ${charts.firstOrNull()?.title} · ${charts.firstOrNull()?.artist}")
        assertTrue("Vietnamese discovery returned nothing", charts.isNotEmpty())

        val related = module.related(charts.first().id)
        println("related(${charts.first()?.id}) -> ${related.size} uploads, first: ${related.firstOrNull()?.title}")
        assertTrue("related returned nothing", related.isNotEmpty())

        val stream = module.streamFor(charts.first().id)
        println("streamFor(${charts.first()?.id}) -> ${stream?.qualityLabel} ${stream?.format} ${stream?.codec} url=${stream?.url?.take(70)}")
        assertNotNull("no stream for a discovered Vietnamese upload", stream)
        assertTrue("stream url is not absolute", stream!!.url.startsWith("http"))

    }
}
