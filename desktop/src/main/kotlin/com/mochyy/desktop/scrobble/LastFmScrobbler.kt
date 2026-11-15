package com.mochyy.desktop.scrobble

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Last.fm scrobbling over its web API, on plain HTTP.
 *
 * The Android build's `scrobble/` package is not portable as-is: every file there is built around
 * `Context`, `DataStore`, `WorkManager` or `AtomicFile` (background send, toast on failure, a
 * queue in app-private storage). None of that exists here, and none of it is needed — the PC
 * build is always online while it plays and can sign a request in a few lines.
 *
 * The API contract is the same one the phone uses: an MD5 signature over the sorted parameters
 * plus the shared secret, then a form POST. No OAuth dance, since `auth.getMobileSession` with
 * username/password gives a session key that never expires.
 */
class LastFmScrobbler(
    private val apiKey: String,
    private val apiSecret: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {

    private var sessionKey: String? = null

    val isReady: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank() && sessionKey != null

    /** exchanges a username/password for a session key. The password is used once and not kept. */
    fun signIn(username: String, password: String): Result<Unit> = runCatching {
        val response = call(
            "auth.getMobileSession",
            mapOf("username" to username, "password" to password),
            needsSession = false,
        )
        val key = response.substringAfter("\"key\":\"", "").substringBefore('"')
        require(key.isNotBlank()) { "Last.fm trả về không có session key" }
        sessionKey = key
    }

    /**
     * Scrobbles one play. Last.fm's rule is unchanged: a track counts once it has played for half
     * its length or four minutes, whichever comes first.
     */
    fun scrobble(artist: String, title: String, album: String?, durationSeconds: Long, startedAtEpochSeconds: Long): Result<Unit> =
        runCatching {
            require(isReady) { "Chưa đăng nhập Last.fm" }
            val params = mutableMapOf(
                "artist" to artist,
                "track" to title,
                "timestamp" to startedAtEpochSeconds.toString(),
            )
            album?.takeIf { it.isNotBlank() }?.let { params["album"] = it }
            if (durationSeconds > 0) params["duration"] = durationSeconds.toString()
            call("track.scrobble", params)
        }

    /** the "now playing" ping, which does not count as a play */
    fun nowPlaying(artist: String, title: String, album: String?, durationSeconds: Long): Result<Unit> = runCatching {
        require(isReady) { "Chưa đăng nhập Last.fm" }
        val params = mutableMapOf("artist" to artist, "track" to title)
        album?.takeIf { it.isNotBlank() }?.let { params["album"] = it }
        if (durationSeconds > 0) params["duration"] = durationSeconds.toString()
        call("track.updateNowPlaying", params)
    }

    private fun call(method: String, params: Map<String, String>, needsSession: Boolean = true): String {
        val signed = params.toMutableMap()
        signed["method"] = method
        signed["api_key"] = apiKey
        if (needsSession) signed["sk"] = sessionKey ?: error("Chưa đăng nhập Last.fm")

        // the signature is MD5 of the parameters sorted by name, name+value concatenated, plus the secret
        val signatureBase = signed.toSortedMap().entries.joinToString("") { (k, v) -> k + v } + apiSecret
        signed["api_sig"] = md5(signatureBase)
        signed["format"] = "json"

        val body = signed.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val request = HttpRequest.newBuilder(URI.create("https://ws.audioscrobbler.com/2.0/"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Mochy-Desktop/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Last.fm trả về HTTP ${response.statusCode()}" }
        if (response.body().contains("\"error\"")) {
            val message = response.body().substringAfter("\"message\":\"", "").substringBefore('"')
            error("Last.fm: ${message.ifBlank { "lỗi không rõ" }}")
        }
        return response.body()
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** how long a track must play before Last.fm counts it */
    companion object {
        fun countsAsPlay(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0) return positionMs >= 240_000
            return positionMs >= minOf(durationMs / 2, 240_000)
        }
    }
}
