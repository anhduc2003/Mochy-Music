package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SoundCloudViewModelTest {
    @Test
    fun `switch gates catalog and comments and reactivation reloads them only on demand`() = runTest(timeout = 15.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val enabled = MutableStateFlow(false)
        val host = CatalogHost()
        val module = SoundCloudModule().also { it.attach(host) }
        val moduleLookups = AtomicInteger()
        val model = SoundCloudViewModel({ enabled.value }, enabled) {
            moduleLookups.incrementAndGet()
            module
        }
        try {
            runCurrent()
            assertFalse(model.state.value.enabled)
            assertEquals(0, moduleLookups.get())
            assertEquals(0, host.totalCalls.get())

            enabled.value = true
            val firstUpload = model.state.first { it.uploads.isNotEmpty() }.uploads.single()
            assertEquals("Chart request 1", firstUpload.title)

            model.openComments(firstUpload)
            assertEquals("First comment", model.state.first { it.comments.isNotEmpty() }.comments.single().body)
            val existingJobs = model.viewModelScope.coroutineContext.job.children.toSet()
            model.openComments(firstUpload)
            host.staleCommentStarted.await()
            val staleJob = model.viewModelScope.coroutineContext.job.children.single { it !in existingJobs }

            enabled.value = false
            val disabled = model.state.first { !it.enabled }
            assertTrue(disabled.uploads.isEmpty())
            assertTrue(disabled.comments.isEmpty())
            assertNull(disabled.commentsFor)
            assertFalse(disabled.commentsLoading)
            model.openComments(firstUpload)
            runCurrent()
            assertEquals(3, host.totalCalls.get())

            enabled.value = true
            val reloaded = model.state.first { it.enabled && it.uploads.isNotEmpty() }
            assertEquals("Chart request 2", reloaded.uploads.single().title)
            assertEquals(4, host.totalCalls.get())

            host.releaseStaleComment.countDown()
            staleJob.join()
            assertTrue(model.state.value.comments.isEmpty())
            assertNull(model.state.value.commentsFor)
        } finally {
            host.releaseStaleComment.countDown()
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `back restores playlist and original search after nested related retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val enabled = MutableStateFlow(true)
        var relatedCalls = 0
        val host = CatalogHost { uri ->
            when (uri.path) {
                "/search/playlists" -> HttpResponse(200, """{"collection":[{"id":17,"title":"My playlist"}]}""".encodeToByteArray())
                "/playlists/17" -> HttpResponse(200, """{"tracks":[{"id":7,"title":"Playlist track"}]}""".encodeToByteArray())
                "/tracks/7/related" -> if (++relatedCalls == 1) HttpResponse(400, byteArrayOf()) else
                    HttpResponse(200, """{"collection":[{"id":8,"title":"Related track"}]}""".encodeToByteArray())
                else -> null
            }
        }
        val module = SoundCloudModule().also { it.attach(host) }
        val model = SoundCloudViewModel({ enabled.value }, enabled, StandardTestDispatcher(testScheduler)) { module }
        try {
            runCurrent()
            model.onQueryChange("playlist search")
            model.onTabChange(SoundCloudViewModel.Tab.Playlists)
            assertTrue(model.state.value.loading)
            assertTrue(model.state.value.uploads.isEmpty())
            runCurrent()
            val search = model.state.value
            model.openPlaylist(search.playlists.single())
            runCurrent()
            val playlist = model.state.value
            model.openRelated(playlist.uploads.single())
            runCurrent()
            assertTrue(model.state.value.error != null)
            model.retry()
            runCurrent()
            assertEquals("Related track", model.state.value.uploads.single().title)
            model.closeRelated()
            assertEquals(playlist, model.state.value)
            model.closePlaylist()
            assertEquals(search, model.state.value)
            assertEquals(5, host.totalCalls.get())
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `comment pagination retains rows on error then merges unique ids and stops on repeated page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val enabled = MutableStateFlow(true)
        var pageTwoCalls = 0
        val host = CatalogHost { uri ->
            if (uri.path != "/tracks/7/comments") null else {
                val offset = uri.query.substringAfter("offset=", "0").substringBefore('&').toInt()
                val first = """{"id":1,"body":"First"}"""
                val second = """{"id":2,"body":"Second"}"""
                when (offset) {
                    0 -> commentPage(first)
                    1 -> if (++pageTwoCalls == 1) HttpResponse(400, byteArrayOf()) else commentPage("$first,$second")
                    3 -> commentPage(second)
                    else -> error("Unexpected offset $offset")
                }
            }
        }
        val module = SoundCloudModule().also { it.attach(host) }
        val model = SoundCloudViewModel({ enabled.value }, enabled, StandardTestDispatcher(testScheduler)) { module }
        try {
            runCurrent()
            model.openComments(model.state.value.uploads.single())
            runCurrent()
            assertEquals(1, model.state.value.commentsNextOffset)
            model.loadMoreComments()
            model.loadMoreComments()
            runCurrent()
            assertEquals(listOf("1"), model.state.value.comments.map { it.id })
            assertTrue(model.state.value.commentsMoreError != null)
            assertEquals(1, model.state.value.commentsNextOffset)
            assertFalse(model.state.value.commentsLoadingMore)
            model.loadMoreComments()
            runCurrent()
            assertEquals(listOf("1", "2"), model.state.value.comments.map { it.id })
            assertNull(model.state.value.commentsMoreError)
            assertEquals(3, model.state.value.commentsNextOffset)
            model.loadMoreComments()
            runCurrent()
            assertNull(model.state.value.commentsNextOffset)
            model.closeComments()
            assertTrue(model.state.value.comments.isEmpty())
            assertNull(model.state.value.commentsFor)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun commentPage(comments: String) = HttpResponse(
        200, """{"collection":[$comments],"next_href":"https://example.test/unused"}""".encodeToByteArray(),
    )

    private class CatalogHost(private val route: (URI) -> HttpResponse? = { null }) : ModuleHost {
        private val data = ConcurrentHashMap(mapOf(
            "client_id" to "0123456789abcdefghijklmnopqrstuv",
            "client_id_at" to System.currentTimeMillis().toString(),
        ))
        override val storage = object : ModuleStorage {
            override fun get(key: String): String? = data[key]
            override fun set(key: String, value: String?) {
                if (value == null) data.remove(key) else data[key] = value
            }
        }
        override val folder = File(System.getProperty("java.io.tmpdir"))
        override fun log(message: String) = Unit

        val totalCalls = AtomicInteger()
        private val chartCalls = AtomicInteger()
        private val commentCalls = AtomicInteger()
        val staleCommentStarted = CompletableDeferred<Unit>()
        val releaseStaleComment = CountDownLatch(1)

        override fun http(request: HttpRequest): HttpResponse {
            totalCalls.incrementAndGet()
            val uri = URI(request.url)
            route(uri)?.let { return it }
            return when (uri.path) {
                "/charts" -> ok("""{"collection":[{"track":{"id":7,"title":"Chart request ${chartCalls.incrementAndGet()}"}}]}""")
                "/tracks/7/comments" -> when (commentCalls.incrementAndGet()) {
                    1 -> ok("""{"collection":[{"id":1,"body":"First comment"}]}""")
                    2 -> {
                        staleCommentStarted.complete(Unit)
                        check(releaseStaleComment.await(10, TimeUnit.SECONDS))
                        ok("""{"collection":[{"id":2,"body":"Stale comment"}]}""")
                    }
                    else -> error("Unexpected extra comment request")
                }
                else -> error("Unexpected SoundCloud request")
            }
        }

        private fun ok(body: String) = HttpResponse(200, body.encodeToByteArray())
    }
}
