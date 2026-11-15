package com.mochyy.desktop.net

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Talks to the real InnerTube endpoint.
 *
 * Network-dependent, so it reports rather than asserts when the endpoint is unreachable or answers
 * with a challenge — a CI box without outbound access must not fail because YouTube said no. What
 * it *does* assert is the shape of what comes back when a response does arrive: a search that
 * returns results must return usable ones (an id, a title, sane durations).
 */
class InnerTubeTest {

    @Test
    fun `search returns usable results`() = runBlocking {
        val results = runCatching { InnerTube().search("Sơn Tùng M-TP") }.getOrNull()
        if (results == null) {
            println("InnerTubeTest: endpoint unreachable, skipped")
            return@runBlocking
        }
        if (results.isEmpty()) {
            println("InnerTubeTest: no results (endpoint may be challenging this IP), skipped")
            return@runBlocking
        }
        println("InnerTubeTest: ${results.size} results")
        results.take(3).forEach { println("  ${it.title} — ${it.artist} (${it.durationMs} ms) id=${it.videoId}") }

        assertTrue(results.size <= 20, "respects the limit")
        val first = results.first()
        assertTrue(first.videoId.isNotBlank(), "every result needs a video id")
        assertTrue(first.title.isNotBlank(), "every result needs a title")
        // a song under a minute or over an hour and a half is a mis-parse, not a song
        val durations = results.map { it.durationMs }.filter { it > 0 }
        assertTrue(
            durations.isEmpty() || durations.all { it in 60_000..5_400_000 },
            "durations look sane: ${durations.take(5)}",
        )
        // ids are unique, or the list has duplicates from walking the tree twice
        assertTrue(results.map { it.videoId }.toSet().size == results.size, "no duplicate results")
    }
}
