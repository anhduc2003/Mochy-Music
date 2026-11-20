package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Public metadata is evidence for curation, not a country/location guarantee. */
class VietnameseDiscoveryTest {
    @Test fun `discovery excludes foreign and missing evidence but allows Vietnamese metadata signals`() {
        val host = Host(search = mixedTracks)
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals(listOf("2", "3", "4", "5", "6", "7"), module.vietnameseDiscovery().map { it.id })
        assertEquals(1, host.paths.size)
        assertTrue(URLDecoder.decode(host.paths.single(), "UTF-8").contains("q=nhạc Việt"))
        assertFalse(host.paths.any { it.contains("/charts") || it.contains("country=") || it.contains("region=") })
    }

    @Test fun `app-facing track and playlist searches exclude explicit foreign country metadata`() {
        val host = Host(
            search = mixedTracks,
            playlists = """[{"id":11,"title":"Playlist Việt","user":{"username":"Host","country_code":"VN"}},{"id":12,"title":"Đường về quê hương","user":{"username":"Foreign host","country_code":"US"}}]""",
        )
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals(listOf("2", "3", "4", "5", "6", "7"), module.vietnameseSearch("mix").map { it.id })
        assertEquals(listOf("11"), module.searchPlaylists("nhạc Việt").map { it.id })
    }

    @Test fun `related recommendations never leak unverified foreign tracks and preserve general browse`() {
        val host = Host(related = mixedTracks)
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals(listOf("2", "3"), module.recommendations("99", 2).map { it.id })
        assertEquals(1, host.paths.size)
        assertTrue(module.related("99").any { it.id == "1" })
        assertFalse(module.vietnameseRelated("99").any { it.id == "1" })
    }

    @Test fun `foreign-only related results use Vietnamese discovery instead of global charts`() {
        val host = Host(
            search = """[{"id":8,"title":"Quê hương"},{"id":9,"title":"Foreign hit","user":{"country_code":"US"}}]""",
            related = """[{"id":1,"title":"English song","user":{"country_code":"US"}}]""",
        )
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals(listOf("8"), module.recommendations("99").map { it.id })
        assertEquals(listOf("/tracks/99/related", "/search/tracks"), host.paths.map { it.substringBefore('?') })
    }

    @Test fun `no evidence anywhere returns empty rather than unfiltered fallback`() {
        val host = Host(search = """[{"id":1,"title":"Global hit"}]""", related = """[{"id":2,"title":"French café","user":{"username":"José"}}]""")
        val module = SoundCloudModule().also { it.attach(host) }
        assertTrue(module.recommendations("99").isEmpty())
        assertTrue(module.recommendations().isEmpty())
        assertTrue(host.paths.none { it.contains("charts") })
    }

    @Test fun `duplicates unstreamable and seed uploads do not fill recommendations`() {
        val host = Host(related = """[{"id":99,"title":"Quê hương"},{"id":2,"title":"Nhạc Việt","streamable":false},{"id":3,"title":"Nhạc Việt"},{"id":3,"title":"Nhạc Việt"}]""")
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals(listOf("3"), module.recommendations("99").map { it.id })
    }

    @Test fun `decomposed Vietnamese marks normalize while ordinary western accents do not qualify`() {
        val host = Host(search = """[{"id":1,"title":"Đường về"},{"id":2,"title":"Café déjà vu","user":{"username":"Beyoncé"}}]""")
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals(listOf("1"), module.vietnameseDiscovery(1).map { it.id })
    }

    private val mixedTracks = """[
        {"id":1,"title":"Foreign hit","user":{"username":"Foreign artist","country_code":"US"}},
        {"id":2,"title":"Đường về quê hương"},
        {"id":3,"title":"Unaccented song","user":{"username":"Uploader","country_code":"vn"}},
        {"id":4,"title":"Unaccented song","language":"vi-VN"},
        {"id":5,"title":"Unaccented song","tag_list":"\"v-pop\" dance"},
        {"id":6,"title":"Unaccented song","genre":"Vinahouse"},
        {"id":7,"title":"Unaccented song","user":{"username":"Sơn Nguyễn"}},
        {"id":8,"title":"Nothing known"},
        {"id":9,"title":"Café","user":{"username":"José"}}
    ]"""

    private class Host(val search: String = "[]", val related: String = "[]", val playlists: String = "[]") : ModuleHost {
        val paths = mutableListOf<String>()
        private val data = ConcurrentHashMap(mapOf("client_id" to "0123456789abcdefghijklmnopqrstuv", "client_id_at" to System.currentTimeMillis().toString()))
        override val storage = object : ModuleStorage {
            override fun get(key: String) = data[key]
            override fun set(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        }
        override val folder = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        override fun log(message: String) = Unit
        override fun http(request: HttpRequest): HttpResponse {
            val uri = URI(request.url)
            paths += "${uri.path}?${uri.rawQuery}"
            val collection = when (uri.path) {
                "/search/tracks" -> search
                "/search/playlists" -> playlists
                "/tracks/99/related" -> related
                else -> error("Unexpected catalog route ${uri.path}")
            }
            return HttpResponse(200, """{"collection":$collection}""".toByteArray())
        }
    }
}
