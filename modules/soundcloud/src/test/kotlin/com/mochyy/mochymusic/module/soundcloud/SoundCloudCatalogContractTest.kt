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

class SoundCloudCatalogContractTest {
    @Test fun `reactivation clears temporary offline marker without a network request`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        // browsing works signed out, so no account is not an expired account
        assertTrue(module.accountState() is com.mochyy.mochymusic.module.AccountState.SignedOut)
        module.reactivate()
        assertTrue(module.accountState() is com.mochyy.mochymusic.module.AccountState.SignedOut)
        assertTrue(host.requests.isEmpty())
    }

    @Test fun `Vietnam discovery queries Vietnamese music rather than ignored country parameter`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals("7", module.vietnameseDiscovery().single().id)
        val request = URLDecoder.decode(host.requests.single(), "UTF-8")
        assertTrue(request.startsWith("/search/tracks?"))
        assertTrue(request.contains("q=nhạc Việt"))
        assertFalse(request.contains("region="))
        assertFalse(request.contains("country="))
    }

    @Test fun `comments include required threaded parameter and bounded page size`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        assertEquals("Nice", module.comments("7", 1000).single().body)
        assertTrue(host.requests.single().contains("threaded=0"))
        assertTrue(host.requests.single().contains("limit=100"))
    }

    @Test fun `failed playlist hydration reports an error instead of a truncated success`() {
        val host = Host(failHydration = true)
        val module = SoundCloudModule().also { it.attach(host) }
        val failure = runCatching { module.playlistTracks("1") }.exceptionOrNull()
        assertTrue(failure is SoundCloudModule.SoundCloudApiException)
        assertEquals(400, (failure as SoundCloudModule.SoundCloudApiException).statusCode)
    }

    private class Host(val failHydration: Boolean = false) : ModuleHost {
        val requests = mutableListOf<String>()
        private val values = mutableMapOf("client_id" to "0123456789abcdefghijklmnopqrstuv", "client_id_at" to System.currentTimeMillis().toString())
        override val storage = object : ModuleStorage {
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }
        override val folder = File(System.getProperty("java.io.tmpdir"))
        override fun log(message: String) = Unit
        override fun http(request: HttpRequest): HttpResponse {
            val uri = URI(request.url)
            requests += uri.rawPath + "?" + uri.rawQuery
            val track = """{"id":7,"title":"Nhạc Việt","likes_count":9,"comment_count":1}"""
            val body = when (uri.path) {
                "/charts" -> """{"collection":[{"track":$track}]}"""
                "/search/tracks" -> """{"collection":[$track]}"""
                "/tracks/7/comments" -> """{"collection":[{"id":1,"body":"Nice","user":{"username":"User"}}]}"""
                "/playlists/1" -> """{"tracks":[{"id":7}]}"""
                "/tracks" -> if (failHydration) return HttpResponse(400, "{}".toByteArray()) else "[$track]"
                else -> error("Unexpected request ${uri.path}")
            }
            return HttpResponse(200, body.toByteArray())
        }
    }
}
