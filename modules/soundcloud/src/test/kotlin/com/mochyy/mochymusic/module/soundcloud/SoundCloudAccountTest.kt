package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * Signing in to SoundCloud inside Mochy: the session the sign-in browser hands over, the account
 * check, and the comment the account writes. No network is involved.
 */
class SoundCloudAccountTest {

    private val module = SoundCloudModule()

    @Test
    fun `the session the web player sends is kept and makes the account signed in`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }

        val complete = module.onSignInHeader(
            "authorization",
            "OAuth 2-293230-123456789-abcdefghij",
            "https://api-v2.soundcloud.com/me",
        )

        assertTrue(complete)
        val state = module.accountState()
        assertTrue(state is AccountState.SignedIn)
        assertEquals("2-293230-123456789-abcdefghij", host.storage.get("session_token"))
    }

    @Test
    fun `a header from another site is not a soundcloud session`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }

        assertFalse(module.onSignInHeader("authorization", "OAuth 2-293230-123456789-abcdefghij", "https://example.test/x"))
        assertTrue(module.accountState() is AccountState.SignedOut)
        assertNull(host.storage.get("session_token"))
    }

    @Test
    fun `the oauth token cookie is accepted as the session`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }

        assertTrue(module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/"))
        assertTrue(module.accountState() is AccountState.SignedIn)
    }

    @Test
    fun `verifying an account keeps its username and a refused session expires`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")

        val signedIn = runBlocking { module.verifyAccount() }
        assertTrue(signedIn is AccountState.SignedIn)
        assertTrue((signedIn as AccountState.SignedIn).label.contains("Nghe Sĩ"))

        host.refuseAccount = true
        val expired = runBlocking { module.verifyAccount() }
        assertTrue(expired is AccountState.Expired)
        assertTrue(module.accountState() is AccountState.Expired)
    }

    @Test
    fun `an expiring JWT is refreshed with the web refresh token before the account is asked again`() {
        val old = jwt("""{"client_id":"web-client","exp":${System.currentTimeMillis() / 1000 - 60}}""")
        val new = jwt("""{"client_id":"web-client","exp":${System.currentTimeMillis() / 1000 + 3600}}""")
        val host = Host(refreshTo = new)
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", old, "https://soundcloud.com/")
        module.onSignInHeader("oauth_refresh_token", "refresh-123456789012345678901", "https://soundcloud.com/")

        val state = runBlocking { module.verifyAccount() }

        assertTrue(state is AccountState.SignedIn)
        val refresh = host.requests.single { it.url.startsWith("https://secure.soundcloud.com/oauth/token") }
        assertEquals("POST", refresh.method)
        assertTrue(refresh.body?.decodeToString()?.contains("grant_type=refresh_token") == true)
        assertTrue(refresh.body?.decodeToString()?.contains("refresh_token=refresh-123456789012345678901") == true)
        assertTrue(refresh.body?.decodeToString()?.contains("client_id=web-client") == true)
        assertEquals(new, host.storage.get("session_token"))
        assertEquals("rotated-refresh", host.storage.get("refresh_token"))
    }

    @Test
    fun `a token without a client claim refreshes with the public player key`() {
        // newer web tokens carry no client_id; the endpoint still knows the public key the
        // session was signed in with, so a token refresh must fall back to it
        val old = jwt("""{"exp":${System.currentTimeMillis() / 1000 - 60}}""")
        val new = jwt("""{"client_id":"web-client","exp":${System.currentTimeMillis() / 1000 + 3600}}""")
        val host = Host(refreshTo = new)
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", old, "https://soundcloud.com/")
        module.onSignInHeader("oauth_refresh_token", "refresh-123456789012345678901", "https://soundcloud.com/")

        assertTrue(runBlocking { module.verifyAccount() } is AccountState.SignedIn)
        val refresh = host.requests.single { it.url.startsWith("https://secure.soundcloud.com/oauth/token") }
        assertTrue(refresh.body?.decodeToString()?.contains("client_id=0123456789abcdefghijklmnopqrstuv") == true)
        assertEquals(new, host.storage.get("session_token"))
    }

    @Test
    fun `a refused refresh signs the account out instead of retrying forever`() {
        val old = jwt("""{"client_id":"web-client","exp":${System.currentTimeMillis() / 1000 - 60}}""")
        val host = Host(refuseRefresh = true).apply { refuseAccount = true }
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", old, "https://soundcloud.com/")
        module.onSignInHeader("oauth_refresh_token", "refresh-123456789012345678901", "https://soundcloud.com/")

        assertTrue(runBlocking { module.verifyAccount() } is AccountState.Expired)
        assertTrue(module.accountState() is AccountState.Expired)
        assertEquals(1, host.requests.count { it.url.contains("/oauth/token") })
    }

    @Test
    fun `jwt payload is read without newer Android base64 helpers`() {
        val token = jwt("""{"client_id":"web-client","exp":1234567890}""")
        val payload = module.jwtPayload(token)
        assertEquals("web-client", payload?.get("client_id")?.toString()?.trim('"'))
        assertEquals(1234567890L, payload?.get("exp")?.toString()?.toLong())
        assertNull(module.jwtPayload("2-legacy-token-without-dots"))
    }

    private fun jwt(payload: String): String {
        fun encode(text: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        return encode("""{"alg":"none","typ":"JWT"}""") + "." + encode(payload) + ".signature"
    }

    @Test
    fun `a refused account check is not repeated with the same public key`() {
        val host = Host().apply { refuseAccount = true }
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")

        assertTrue(runBlocking { module.verifyAccount() } is AccountState.Expired)
        // one /me, then one key discovery; the same key answering 401 again is the session's fault
        assertEquals(1, host.requests.count { it.url.contains("/me") })
    }

    @Test
    fun `an offline account check does not sign the account out`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")
        runBlocking { module.verifyAccount() }

        host.offline = true
        assertTrue(runBlocking { module.verifyAccount() } is AccountState.SignedIn)
        assertTrue(module.accountState() is AccountState.SignedIn)
    }

    @Test
    fun `signing out forgets the session and the label`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")
        runBlocking { module.verifyAccount() }

        module.signOut()

        assertTrue(module.accountState() is AccountState.SignedOut)
        assertNull(host.storage.get("session_token"))
        assertNull(host.storage.get("account_label"))
    }

    @Test
    fun `posting a comment needs the account, sends the session and reads the answer`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }

        val refused = runCatching { module.postComment("7", "Hay quá") }.exceptionOrNull()
        assertTrue(refused is SoundCloudModule.SoundCloudAuthException)

        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")
        val posted = module.postComment("7", "  Hay quá  ")

        assertEquals("Hay quá", posted.body)
        assertEquals("Nghe Sĩ", posted.author)
        assertNotNull(posted.createdAtMs)
        val request = host.requests.single { it.method == "POST" }
        assertEquals("OAuth 2-293230-123456789-abcdefghij", request.header("Authorization"))
        assertTrue(request.url.contains("client_id=0123456789abcdefghijklmnopqrstuv"))
        assertTrue(request.url.contains("body=Hay+qu%C3%A1"))
        assertNull(request.body)
    }

    @Test
    fun `a refused comment says so instead of pretending it posted`() {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")
        host.refuseComment = true

        val error = runCatching { module.postComment("7", "Hay quá") }.exceptionOrNull()
        assertTrue(error is SoundCloudModule.SoundCloudRejectedException)
    }

    @Test
    fun `a forbidden comment names the status instead of blaming the network`() {
        val host = Host().apply { refuseComment = true; commentStatus = 403 }
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")

        val error = runCatching { module.postComment("7", "Hay quá") }.exceptionOrNull()
        assertTrue(error is SoundCloudModule.SoundCloudRejectedException)
        assertTrue(error?.message?.contains("403") == true)
    }

    @Test
    fun `a challenged comment repeats the cookie the challenge handed out`() {
        val host = Host().apply { challengeComment = true }
        val module = SoundCloudModule().also { it.attach(host) }
        module.onSignInHeader("oauth_token", "2-293230-123456789-abcdefghij", "https://soundcloud.com/")

        val posted = module.postComment("7", "Hay quá")

        assertEquals("Hay quá", posted.body)
        val posts = host.requests.filter { it.method == "POST" }
        assertEquals(2, posts.size)
        assertNull(posts[0].headers["Cookie"])
        assertEquals("datadome=challenge-123", posts[1].headers["Cookie"])
    }

    @Test
    fun `a comment keeps its track position and the moment it was written apart`() {
        val parsed = commentIn(
            """{"id":9,"body":"Hay","timestamp":85000,"created_at":"2026-10-22T23:53:04Z","user":{"username":"Listener","avatar_url":"https://i.example.test/a.jpg"}}""",
        )

        assertNotNull(parsed)
        assertEquals(85_000L, parsed!!.timestampMs)
        assertNotNull(parsed.createdAtMs)
        assertEquals("Listener", parsed.author)
        assertEquals("https://i.example.test/a.jpg", parsed.avatarUrl)
    }

    private fun commentIn(text: String): SoundCloudModule.Comment? {
        val host = Host()
        val module = SoundCloudModule().also { it.attach(host) }
        return module.commentOf(kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)
    }

    private class RecordedRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray?, val contentType: String?) {
        fun header(name: String) = headers[name]
    }

    private class Host(refreshTo: String? = null, val refuseRefresh: Boolean = false) : ModuleHost {
        val requests = mutableListOf<RecordedRequest>()
        var refuseAccount = false
        var offline = false
        var refuseComment = false
        var commentStatus = 422
        var challengeComment = false
        private val refreshTo = refreshTo
        private val values = mutableMapOf(
            "client_id" to "0123456789abcdefghijklmnopqrstuv",
            "client_id_at" to System.currentTimeMillis().toString(),
        )
        override val storage = object : ModuleStorage {
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        override val folder = File(System.getProperty("java.io.tmpdir"))
        override fun log(message: String) = Unit

        override fun http(request: HttpRequest): HttpResponse {
            requests += RecordedRequest(request.url, request.method, request.headers, request.body, request.contentType)
            if (offline) error("offline")
            val path = URI(request.url).path
            if (path == "/me") {
                val session = request.headers["Authorization"]
                val accepted = refreshTo == null || session == "OAuth $refreshTo"
                return if (refuseAccount || !accepted) {
                    HttpResponse(401, "{}".toByteArray())
                } else {
                    HttpResponse(200, """{"id":1,"username":"Nghe Sĩ","permalink_url":"https://soundcloud.com/nghe-si"}""".toByteArray())
                }
            }
            if (path == "/oauth/token") {
                return if (refuseRefresh) {
                    // the endpoint's own answer for a dead refresh token
                    HttpResponse(400, """{"error":"invalid_grant"}""".toByteArray())
                } else {
                    HttpResponse(200, """{"access_token":"$refreshTo","refresh_token":"rotated-refresh"}""".toByteArray())
                }
            }
            if (path == "/tracks/7/comments" && request.method == "POST") {
                if (challengeComment && request.headers["Cookie"]?.contains("datadome=") != true) {
                    return HttpResponse(
                        403,
                        "{}".toByteArray(),
                        mapOf("set-cookie" to "datadome=challenge-123; Path=/; Domain=.soundcloud.com"),
                    )
                }
                return if (refuseComment) {
                    HttpResponse(commentStatus, "{}".toByteArray())
                } else {
                    HttpResponse(
                        201,
                        """{"id":10,"body":"Hay quá","created_at":"2026-11-21T09:00:00Z","user":{"username":"Nghe Sĩ"}}""".toByteArray(),
                    )
                }
            }
            error("Unexpected request ${request.method} $path")
        }
    }
}
