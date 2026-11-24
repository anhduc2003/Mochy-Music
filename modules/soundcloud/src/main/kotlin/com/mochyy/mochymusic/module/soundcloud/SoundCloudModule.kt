// SoundCloudModule.kt

package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SignInSpec
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import com.mochyy.mochymusic.module.BoundedCache

/**
 * SoundCloud's catalog, played the way the web player plays it: an HLS stream behind a signed URL.
 *
 * Public catalog requests use the web player's rotating client ID. Signing in happens on
 * soundcloud.com itself, inside Mochy's own sign-in browser: the session token the page sends to
 * api-v2 is kept (never the password), and it turns on what only an account can do, such as
 * posting a comment. A public client ID is not a user session, so browsing and reading comments
 * keep working while signed out.
 *
 * What it is good at is what the other sources are not: independent artists, DJ sets, remixes, and
 * uploads 40 minutes long.
 */
class SoundCloudModule : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "SoundCloud",
        version = "1.2",
        description = "Danh mục SoundCloud: tìm kiếm, gợi ý nhạc Việt, phát nhạc, đọc và viết bình luận. Đăng nhập SoundCloud ngay trong ứng dụng để bình luận bằng tài khoản của bạn; nghe nhạc vẫn dùng được khi chưa đăng nhập.",
    )

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val matches = BoundedCache<String, ScTrack>(RememberedMatches)
    private val misses = BoundedCache<String, Long>(RememberedMisses)

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val choices = listOf(
        ModuleChoice(
            id = ChoiceQuality,
            label = "Stream",
            options = listOf(
                StreamOption(OptionBest, "Best available", "SoundCloud's own quality, usually AAC around 128 kbps"),
                StreamOption(OptionDataSaver, "AAC 96", "Half the data, still clear"),
            ),
        ),
    )

    // ---- account ------------------------------------------------------------------------------

    /**
     * SoundCloud's own sign-in page, in Mochy's browser. The page is a single-page app, so the
     * session is not a cookie the sheet can hand over by itself: it is the `Authorization: OAuth …`
     * header the page puts on its api-v2 requests, plus the `oauth_token` cookie the web player
     * keeps. The hook in the sign-in browser reports both.
     */
    override val signIn = SignInSpec(
        startUrl = "https://soundcloud.com/signin",
        origins = listOf("https://soundcloud.com", "https://m.soundcloud.com", "https://secure.soundcloud.com"),
        sessionOrigins = setOf("https://soundcloud.com", "https://m.soundcloud.com", "https://secure.soundcloud.com"),
        desktopSite = false,
        title = "Đăng nhập SoundCloud",
        subtitle = "Bình luận bằng tài khoản của bạn. Nghe nhạc vẫn không cần đăng nhập.",
    )

    private var session: String?
        get() = host.storage.get(TokenKey)
        set(value) = host.storage.set(TokenKey, value)

    /** the web session's other half: SoundCloud rotates a short-lived access token with this */
    private var refreshToken: String?
        get() = host.storage.get(RefreshTokenKey)
        set(value) = host.storage.set(RefreshTokenKey, value)

    /**
     * The bot-protection cookie the browser earned on the sign-in page. api-v2 sits behind
     * DataDome, which refuses writes from clients that don't carry it (a VPN makes that more
     * likely), so the write repeats the cookie the browser already has.
     */
    private var dataDomeCookie: String?
        get() = host.storage.get(DataDomeKey)
        set(value) = host.storage.set(DataDomeKey, value)

    override fun accountState(): AccountState {
        session ?: return AccountState.SignedOut
        val expired = host.storage.get(ExpiredKey)
        if (expired != null) return AccountState.Expired(expired)
        return AccountState.SignedIn(host.storage.get(LabelKey) ?: SignedInLabel)
    }

    /** true once a soundcloud.com page has handed over a session api-v2 accepts */
    override fun onSignInHeader(name: String, value: String, url: String): Boolean {
        if (!url.isSoundCloudAddress()) return false
        when (name.trim().lowercase()) {
            "authorization" -> {
                val raw = value.trim()
                val candidate = raw.replace(AuthorizationPrefix, "").trim()
                // the page also sends Basic/Bearer headers for its own endpoints; only its OAuth
                // session is the one api-v2 takes, and only that one is kept
                if (raw.startsWith("OAuth ", ignoreCase = true) || SessionToken.matches(candidate)) saveSession(candidate)
            }
            "oauth_token" -> value.trim().trim('"').takeIf { SessionToken.matches(it) }?.let(::saveSession)
            "oauth_refresh_token" -> value.trim().trim('"').takeIf { SessionToken.matches(it) }?.let { refreshToken = it }
            "datadome" -> value.trim().trim('"').takeIf { it.isNotBlank() }?.let { dataDomeCookie = it }
        }
        return session != null
    }

    private fun saveSession(value: String) {
        session = value
        host.storage.set(ExpiredKey, null)
        // the web token is a JWT: its client id is what the refresh endpoint asks for, and its
        // exp is how a 401 is told apart from a rotating public key
        jwtPayload(value)?.let { claims ->
            claims.string("client_id")?.takeIf { it.isNotBlank() }?.let { host.storage.set(SessionClientIdKey, it) }
            claims.long("exp")?.let { host.storage.set(SessionExpiresAtKey, (it * 1000L).toString()) }
        }
    }

    override suspend fun verifyAccount(): AccountState {
        session ?: return AccountState.SignedOut
        // A network that didn't answer is not a sign-out: keep the session and the label it had.
        val response = runCatching { accountApi("/me") }.getOrNull() ?: return accountState()
        if (response.code == 401 || response.code == 403) {
            // A refused refresh, or a legacy session with no refresh token at all, is a real
            // sign-out. A refresh that could not answer (no network) is not: keep the session.
            host.storage.get(ExpiredKey)?.let { return AccountState.Expired(it) }
            if (refreshToken != null) return accountState()
            expire(SessionEndedReason)
            return AccountState.Expired(SessionEndedReason)
        }
        if (!response.ok) return accountState()
        val user = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull()
            ?: return accountState()
        val username = user.string("username")?.takeIf { it.isNotBlank() }
        val label = username?.let { "$SignedInLabel · $it" } ?: SignedInLabel
        host.storage.set(LabelKey, label)
        host.storage.set(ExpiredKey, null)
        return AccountState.SignedIn(label)
    }

    private fun expire(reason: String) {
        host.storage.set(ExpiredKey, reason)
    }

    /** true when the JWT is at (or past) its end, or when it isn't a JWT and cannot say */
    private fun sessionDue(): Boolean {
        val expiresAt = host.storage.get(SessionExpiresAtKey)?.toLongOrNull() ?: return true
        return expiresAt <= System.currentTimeMillis() + RefreshMarginMs
    }

    /**
     * Exchanges the web session's `oauth_refresh_token` for a fresh access token, the same way the
     * web player keeps itself signed in. The endpoint is a public client: `client_id` is the claim
     * inside the JWT, and the response rotates both tokens.
     *
     * true = refreshed, false = SoundCloud refused the refresh token (a real sign-out), null = it
     * could not be tried or did not answer, which is not a sign-out.
     */
    private fun refreshSession(): Boolean? {
        val refresh = refreshToken?.takeIf { it.isNotBlank() } ?: return null
        // The token endpoint needs the client the web session was issued to. A JWT that carries
        // its own client_id names it; newer web tokens leave that claim out, and then the public
        // player key (the one the session was signed in with) is the client id the endpoint knows.
        val claim = host.storage.get(SessionClientIdKey)?.takeIf { it.isNotBlank() }
        val public = runCatching { clientId(forceRefresh = false) }.getOrNull()?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(claim, public).distinct()
        if (candidates.isEmpty()) return null
        for (clientId in candidates) {
            val form = queryString(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId))
            val response = runCatching {
                host.http(
                    HttpRequest(
                        url = TokenUrl,
                        method = "POST",
                        headers = tokenHeaders,
                        body = form.toByteArray(),
                        contentType = FormContentType,
                    ),
                )
            }.getOrNull() ?: return null
            if (response.ok) {
                val root = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull() ?: return null
                val access = root.string("access_token")?.takeIf { it.isNotBlank() } ?: return null
                saveSession(access)
                root.string("refresh_token")?.takeIf { it.isNotBlank() }?.let { refreshToken = it }
                return true
            }
            // `invalid_grant` is the endpoint saying the refresh token itself is dead: that is a
            // real sign-out. Any other refusal (a stale public key, for instance) is not, and the
            // caller's key refresh plus one more attempt may still get through.
            if (response.code == 400 && response.text.contains("invalid_grant")) {
                expire(SessionEndedReason)
                return false
            }
            if (response.code !in 400..499) return null
        }
        return null
    }

    fun reactivate() {
        host.storage.set("unreachable", null)
        host.storage.set("unreachable_at", null)
        misses.clear()
    }

    override fun signOut() {
        listOf(TokenKey, RefreshTokenKey, SessionClientIdKey, SessionExpiresAtKey, LabelKey, ExpiredKey)
            .forEach { host.storage.set(it, null) }
        matches.clear()
        misses.clear()
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        val key = "${track.title}|${track.artists.joinToString(",")}"
        val missedAt = misses[key]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MissMemoryMs) {
            report(DiagnosticStep("Find the song", false, "Not on SoundCloud"))
            return ResolveResult.NotFound("Not on SoundCloud")
        }

        report(DiagnosticStep("Find the song", null, "Searching SoundCloud…"))
        val found = matches[key] ?: run {
            val query = "${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
            val response = runCatching { api(SearchPath, mapOf("q" to query, "limit" to MaxMatches.toString())) }.getOrNull()
            if (response == null) {
                report(DiagnosticStep("Find the song", false, "SoundCloud didn't answer"))
                markUnreachable("SoundCloud didn't answer")
                return ResolveResult.Unavailable("SoundCloud didn't answer")
            }
            if (!response.ok) {
                report(DiagnosticStep("Find the song", false, "SoundCloud answered ${response.code}"))
                return ResolveResult.Unavailable("SoundCloud answered ${response.code}")
            }
            host.storage.set("unreachable", null)
            val candidates = tracksIn(response.text)
            val best = TrackMatcher.ranked(track, candidates.map { it.catalog }).firstOrNull()
                ?.let { scored -> candidates.first { it.catalog === scored.track } }
            if (best == null) {
                rememberMiss(key)
                report(DiagnosticStep("Find the song", false, "No matching upload of “${track.title}”"))
                return ResolveResult.NotFound("No matching upload on SoundCloud")
            }
            rememberMatch(key, best)
            best
        }
        report(DiagnosticStep("Find the song", true, "${found.catalog.title} · ${found.catalog.artist}"))

        if (!found.streamable) {
            report(DiagnosticStep("Stream", false, "The uploader turned streaming off"))
            return ResolveResult.NotFound("Streaming is off for this upload")
        }

        report(DiagnosticStep("Stream", null, "Opening the stream…"))
        val opened = openStream(found, picks[ChoiceQuality] ?: OptionBest)
        if (opened == null) {
            val encrypted = found.transcodings.isNotEmpty() && ordered(found.transcodings, OptionBest).isEmpty()
            val why = if (encrypted) "this upload is DRM-protected, which Mochy can't play" else "SoundCloud gave no playable stream"
            report(DiagnosticStep("Stream", false, why))
            return ResolveResult.Unavailable(why)
        }
        report(DiagnosticStep("Stream", true, opened.quality))

        return ResolveResult.Found(
            SourceStream(
                url = opened.url,
                format = opened.format,
                sourceLabel = "SoundCloud",
                qualityLabel = opened.quality,
                codec = opened.codec,
                matched = MatchedTrack(
                    id = found.catalog.id,
                    title = found.catalog.title,
                    artist = found.catalog.artist,
                    album = found.catalog.album,
                    durationMs = found.catalog.durationMs,
                    confidence = TrackMatcher.score(track, found.catalog),
                ),
            )
        )
    }

    // ---- the api, and the key it needs ----

    private fun api(
        path: String,
        query: Map<String, String>,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        authorization: String? = null,
        cookie: String? = null,
    ): HttpResponse? = requestWithClientId(method, body, contentType, authorization, cookie) { id ->
        ApiBase + path + "?" + queryString(query + ("client_id" to id))
    }

    /** the account's own calls, which need both the public key and the signed-in session */
    private fun accountApi(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
    ): HttpResponse? = api(
        path = path,
        query = query,
        method = method,
        body = body,
        contentType = body?.let { "application/json; charset=utf-8" },
        authorization = session,
        cookie = dataDomeCookie?.let { "datadome=$it" },
    )

    private fun requestWithClientId(
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        authorization: String? = null,
        cookie: String? = null,
        urlFor: (String) -> String,
    ): HttpResponse? {
        var activeClientId = clientId(forceRefresh = false) ?: return null
        var activeSession = authorization
        var refreshedKey = false
        var refreshedSession = false
        var retry = 0
        while (true) {
            var headers = if (activeSession != null) apiHeaders + ("Authorization" to "OAuth $activeSession") else apiHeaders
            if (cookie != null) headers = headers + ("Cookie" to cookie)
            val response = runCatching {
                host.http(
                    HttpRequest(
                        url = urlFor(activeClientId),
                        method = method,
                        headers = headers,
                        body = body?.toByteArray(),
                        contentType = contentType,
                    ),
                )
            }.getOrNull()
            // A 401 can mean the short-lived web token expired, the rotating public key went stale,
            // or the account's session ended. The token is refreshed first when its JWT says it is
            // due; then one key refresh tells a stale key from a dead session: a new key is retried
            // once, an unchanged one is not.
            if (response?.code == 401) {
                // the usual cause: the short-lived web token is due, so refresh it first
                if (activeSession != null && !refreshedSession && sessionDue()) {
                    refreshedSession = true
                    when (refreshSession()) {
                        true -> {
                            activeSession = session
                            continue
                        }
                        // the token endpoint refused the refresh token: the session is really over
                        false -> return response
                        // no refresh token, or a network that could not answer: try the key next
                        null -> Unit
                    }
                }
                if (!refreshedKey) {
                    refreshedKey = true
                    host.storage.set(ClientIdKey, null)
                    host.storage.set(ClientIdAtKey, null)
                    val renewed = clientId(forceRefresh = true)
                    if (renewed != null && renewed != activeClientId) {
                        activeClientId = renewed
                        continue
                    }
                }
                // a token that is not due can still have been revoked; one refresh is the last
                // chance before the caller decides the session ended
                if (activeSession != null && !refreshedSession) {
                    refreshedSession = true
                    when (refreshSession()) {
                        true -> {
                            activeSession = session
                            continue
                        }
                        false -> return response
                        null -> Unit
                    }
                }
                return response
            }
            // retrying a POST could post the same comment twice, so only reads are retried
            if (method == "GET" && retry < MaxApiRetries && (response == null || response.code == 429 || response.code in 500..599)) {
                val delayMs = apiRetryDelayMs(retry++, response?.header("retry-after"))
                try {
                    Thread.sleep(delayMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                continue
            }
            return response
        }
    }

    /** SoundCloud's public web client key rotates; serialize discovery to avoid parallel bundle scans. */
    @Synchronized
    private fun clientId(forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            val saved = host.storage.get(ClientIdKey)?.takeIf { it.isNotBlank() }
            val savedAt = host.storage.get(ClientIdAtKey)?.toLongOrNull() ?: 0L
            if (saved != null && System.currentTimeMillis() - savedAt < ClientIdLifeMs) return saved
        }
        val found = discoverClientId()
        if (found == null) {
            if (forceRefresh) {
                host.storage.set(ClientIdKey, null)
                host.storage.set(ClientIdAtKey, null)
                return null
            }
            return host.storage.get(ClientIdKey)?.takeIf { it.isNotBlank() }
        }
        host.storage.set(ClientIdKey, found)
        host.storage.set(ClientIdAtKey, System.currentTimeMillis().toString())
        return found
    }

    internal fun apiRetryDelayMs(retry: Int, retryAfter: String? = null): Long {
        val serverDelay = retryAfter?.trim()?.toLongOrNull()?.times(1000L)
        return (serverDelay ?: (400L shl retry.coerceIn(0, 3))).coerceIn(250L, MaxRetryDelayMs)
    }

    class SoundCloudApiException(val statusCode: Int?) : RuntimeException(
        when (statusCode) {
            null -> "SoundCloud không phản hồi. Kiểm tra mạng rồi thử lại."
            400 -> "SoundCloud từ chối tham số yêu cầu (400). Thử tải lại hoặc cập nhật ứng dụng."
            401 -> "SoundCloud từ chối mã truy cập công khai. Thử lại sau để làm mới kết nối."
            403 -> "SoundCloud từ chối truy cập nội dung này (403). Nội dung có thể riêng tư hoặc bị giới hạn."
            404 -> "Nội dung SoundCloud này không còn tồn tại."
            429 -> "SoundCloud đang giới hạn yêu cầu. Chờ một lúc rồi thử lại."
            in 500..599 -> "SoundCloud đang gặp sự cố tạm thời. Thử lại sau."
            else -> "SoundCloud trả về lỗi $statusCode."
        },
    )

    /** the session is gone or was never there: the UI offers the sign-in again instead of a retry */
    class SoundCloudAuthException : RuntimeException("Đăng nhập SoundCloud để bình luận bằng tài khoản của bạn.")

    /** SoundCloud read the request and refused what was in it (an empty or too long comment) */
    class SoundCloudRejectedException(message: String) : RuntimeException(message)

    /** DataDome refused the write itself; the app retries it inside a WebView, as a browser */
    class SoundCloudBlockedException : RuntimeException("SoundCloud đang chặn yêu cầu từ mạng này.")

    /** the browser's own credentials, for a write that has to look like the web player */
    class BrowserSession(val clientId: String, val token: String)

    private fun catalogApi(path: String, query: Map<String, String>): HttpResponse {
        val response = api(path, query) ?: throw SoundCloudApiException(null)
        if (!response.ok) throw SoundCloudApiException(response.code)
        return response
    }

    private fun discoverClientId(): String? {
        val page = runCatching { host.http(HttpRequest(HomePage, headers = browserHeaders)) }.getOrNull() ?: return null
        if (!page.ok) return null
        // some deploys inline the key in the page itself instead of shipping it in a bundle
        clientIdIn(page.text)?.let { return it }
        val scripts = bundleUrlsIn(page.text).take(MaxBundles)
        for (script in scripts) {
            val bundle = runCatching { host.http(HttpRequest(script, headers = browserHeaders)) }.getOrNull() ?: continue
            if (!bundle.ok) continue
            clientIdIn(bundle.text)?.let { return it }
        }
        return null
    }

    private fun openStream(track: ScTrack, quality: String): ScStream? {
        for (transcoding in ordered(track.transcodings, quality)) {
            val body = requestWithClientId { clientId ->
                mediaRequestUrl(transcoding.url, clientId, track.authorization)
            }
            if (body == null) continue
            if (!body.ok) continue
            val resolved = field(body.text, "url")?.takeIf(::isHttpsUrl) ?: continue
            return ScStream(
                url = resolved,
                format = if (transcoding.protocol.equals("hls", ignoreCase = true)) StreamFormat.Hls else formatOf(resolved),
                quality = qualityLabel(transcoding.preset),
                codec = codecOf(transcoding.preset),
            )
        }
        return null
    }

    private fun markUnreachable(reason: String) {
        host.storage.set("unreachable", reason)
        host.storage.set("unreachable_at", System.currentTimeMillis().toString())
    }

    // the song cache is a convenience, not a library: bound it so a long session can't grow it forever
    private fun rememberMatch(key: String, track: ScTrack) {
        if (matches.size >= MaxCachedMatches) matches.clear()
        matches[key] = track
    }

    private fun rememberMiss(key: String) {
        if (misses.size >= MaxCachedMisses) misses.clear()
        misses[key] = System.currentTimeMillis()
    }

    private fun String.isSoundCloudAddress(): Boolean = runCatching {
        val host = URI(this).host?.lowercase() ?: return@runCatching false
        host == "soundcloud.com" || host.endsWith(".soundcloud.com")
    }.getOrDefault(false)

    // ---- reading what SoundCloud sent ----

    /** every upload in a search answer, in the order SoundCloud ranked them */
    internal fun tracksIn(text: String): List<ScTrack> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val collection = root["collection"] as? JsonArray ?: return emptyList()
        return collection.mapNotNull { element -> (element as? JsonObject)?.let(::trackOf) }
    }

    /** one upload as the api describes it, from a search answer or from `/tracks/{id}` */
    private fun trackOf(upload: JsonObject): ScTrack? {
        val id = upload.long("id") ?: return null
        val title = upload.string("title")?.takeIf { it.isNotBlank() } ?: return null
        val transcodings = (upload["media"] as? JsonObject)?.let { it["transcodings"] as? JsonArray }
            ?.mapNotNull { (it as? JsonObject)?.let(::transcodingOf) }
            .orEmpty()
        val user = upload["user"] as? JsonObject
        val artist = user?.string("username").orEmpty()
        return ScTrack(
            catalog = CatalogTrack(
                id = id.toString(),
                title = title,
                artist = artist,
                durationMs = upload.long("full_duration")?.takeIf { it > 0 } ?: upload.long("duration"),
            ),
            transcodings = transcodings,
            authorization = upload.string("track_authorization").orEmpty(),
            streamable = upload.boolean("streamable") != false,
            artworkUrl = upload.string("artwork_url")?.replace("-large", "-t500x500"),
            likesCount = upload.long("likes_count") ?: upload.long("favoritings_count"),
            commentCount = upload.long("comment_count"),
            permalinkUrl = upload.string("permalink_url"),
            hasVietnameseEvidence = vietnameseEvidence(title, artist, upload, user),
        )
    }

    // ---- what the app's own SoundCloud screen asks for ----

    /** An upload as the browse screen lists it. */
    class Upload internal constructor(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val artworkUrl: String?,
        val likesCount: Long? = null,
        val commentCount: Long? = null,
        val permalinkUrl: String? = null,
        val hasVietnameseEvidence: Boolean = false,
    )

    data class Comment(
        val id: String,
        val body: String,
        val author: String,
        /** where in the upload the comment was left, for a "1:25" chip; null for a general one */
        val timestampMs: Long?,
        val avatarUrl: String?,
        /** when it was written; the list shows this, and falls back to nothing when the API omits it */
        val createdAtMs: Long? = null,
    )

    data class CommentPage(val comments: List<Comment>, val nextOffset: Int?)

    fun comments(id: String, limit: Int = 50): List<Comment> = commentsPage(id, limit).comments

    fun commentsPage(id: String, limit: Int = 50, offset: Int = 0): CommentPage {
        require(TrackIdPattern.matches(id)) { "Invalid SoundCloud track id" }
        require(offset >= 0) { "Invalid comment offset" }
        val pageSize = limit.coerceIn(1, 100)
        val response = catalogApi("/tracks/$id/comments", mapOf(
            "threaded" to "0", "filter_replies" to "0", "offset" to offset.toString(),
            "limit" to pageSize.toString(),
        ))
        return commentPageIn(response.text, pageSize, offset)
    }

    internal fun commentPageIn(text: String, limit: Int, offset: Int): CommentPage {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw SoundCloudResponseException()
        val raw = root["collection"] as? JsonArray ?: throw SoundCloudResponseException()
        val next = when {
            raw.isEmpty() || offset > Int.MAX_VALUE - raw.size -> null
            "next_href" in root && root.string("next_href").isNullOrBlank() -> null
            "next_href" in root || raw.size >= limit -> offset + raw.size
            else -> null
        }
        // Use only the locally computed offset: never follow a server URL with a client ID.
        return CommentPage(commentsIn(text), next)
    }

    class SoundCloudResponseException : RuntimeException("SoundCloud trả dữ liệu không hợp lệ. Hãy thử tải lại.")

    internal fun commentsIn(text: String): List<Comment> =
        (objectsIn(text, "collection") ?: emptyList()).mapNotNull(::commentOf).distinctBy { it.id }

    internal fun commentOf(entry: JsonObject): Comment? {
        val id = entry.long("id") ?: return null
        val body = entry.string("body")?.takeIf { it.isNotBlank() } ?: return null
        val user = entry["user"] as? JsonObject
        return Comment(
            id = id.toString(),
            body = body,
            author = user?.string("username").orEmpty(),
            // `timestamp` is where in the upload the comment sits, not when it was written;
            // `created_at` is the moment it was posted
            timestampMs = entry.long("timestamp")?.takeIf { it >= 0 },
            avatarUrl = user?.string("avatar_url"),
            createdAtMs = entry.string("created_at")?.let(::instantMs),
        )
    }

    /**
     * Posts a comment as the signed-in account. Blocking, as [search]; call it off the main thread.
     * The comment is written by the same api-v2 the web player uses, so the account's own session
     * is what authorizes it.
     */
    fun postComment(trackId: String, body: String): Comment {
        require(TrackIdPattern.matches(trackId)) { "Invalid SoundCloud track id" }
        val text = body.trim()
        require(text.isNotEmpty()) { EmptyCommentReason }
        require(text.length <= MaxCommentLength) { "Bình luận tối đa $MaxCommentLength ký tự." }
        if (session == null) throw SoundCloudAuthException()
        // The documented write (the one the registered-app clients use) takes a JSON body on
        // api.soundcloud.com; it is a different host from the web player's api-v2, so it is tried
        // first and the web shape below remains the fallback when this session isn't taken there.
        postOfficialComment(trackId, text)?.let { return it }
        // api-v2, the endpoint the web player writes with, takes the comment as query parameters
        // (`body`, and `timestamp` for one placed on the track), not as a JSON body
        val response = accountApi(
            path = "/tracks/$trackId/comments",
            query = mapOf("body" to text),
            method = "POST",
        ) ?: throw SoundCloudApiException(null)
        when {
            response.code == 401 -> {
                // the request layer already refreshed the token and the key; a 401 here is the
                // session's own answer, so ask for the sign-in again instead of blaming the network
                expire(SessionEndedReason)
                throw SoundCloudAuthException()
            }
            response.code == 403 -> {
                // DataDome sits in front of api-v2. A refusal hands out a `datadome` cookie; a
                // write that carries it is the browser's own request, so keep it and try once more
                val challenge = response.header("set-cookie")?.let { DataDomeCookie.find(it)?.groupValues?.getOrNull(1) }
                if (challenge != null && challenge != dataDomeCookie) {
                    dataDomeCookie = challenge
                    val retried = accountApi(
                        path = "/tracks/$trackId/comments",
                        query = mapOf("body" to text),
                        method = "POST",
                    )
                    if (retried != null && retried.ok) {
                        return postedComment(retried.text, text)
                    }
                }
                val blocked = response.header("x-datadome") != null ||
                    response.text.contains("datadome", ignoreCase = true) ||
                    response.text.contains("captcha", ignoreCase = true)
                if (blocked) throw SoundCloudBlockedException()
                val detail = response.text.trim().take(140)
                throw SoundCloudRejectedException(
                    "SoundCloud từ chối bình luận này (403). " +
                        detail.ifBlank { "Bài hát có thể đã tắt bình luận hoặc tài khoản bị giới hạn." },
                )
            }
            response.code == 422 || response.code == 400 ->
                throw SoundCloudRejectedException("SoundCloud không nhận bình luận này. Thử nội dung ngắn hơn rồi gửi lại.")
            !response.ok -> throw SoundCloudApiException(response.code)
        }
        return postedComment(response.text, text)
    }

    /**
     * The official comment write: `POST https://api.soundcloud.com/tracks/{id}/comments` with
     * `{"comment":{"body":"..."}}`. Null means this session isn't accepted on that host (401/403)
     * or the answer wasn't a comment, so the caller can fall back to the web endpoint.
     */
    private fun postOfficialComment(trackId: String, text: String): Comment? {
        val token = session ?: return null
        val payload = """{"comment":{"body":${JsonPrimitive(text)}}}"""
        val response = runCatching {
            host.http(
                HttpRequest(
                    url = OfficialApiBase + "/tracks/$trackId/comments",
                    method = "POST",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Authorization" to "OAuth $token",
                    ),
                    body = payload.toByteArray(),
                    contentType = "application/json; charset=utf-8",
                ),
            )
        }.getOrNull() ?: return null
        return when {
            response.ok -> postedComment(response.text, text)
            response.code == 422 || response.code == 400 ->
                throw SoundCloudRejectedException("SoundCloud không nhận bình luận này. Thử nội dung ngắn hơn rồi gửi lại.")
            response.code == 429 -> throw SoundCloudApiException(429)
            // 401/403: the web session isn't a registered app's token on this host; api-v2 below
            // is where the web player itself writes
            else -> null
        }
    }

    /** the answer is the comment itself; if a deploy answers with something else, show what was
     * written rather than claiming the post failed when the server accepted it */
    private fun postedComment(text: String, body: String): Comment =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let(::commentOf) ?: Comment(
            id = "new-${System.currentTimeMillis()}",
            body = body,
            author = host.storage.get(LabelKey)?.substringAfterLast("·")?.trim().orEmpty(),
            timestampMs = null,
            avatarUrl = null,
            createdAtMs = System.currentTimeMillis(),
        )

    private fun instantMs(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

    /**
     * The public player key and the account token as the browser holds them. The app's own HTTP
     * client is what DataDome refuses, so a blocked write is repeated inside a WebView instead.
     */
    fun browserSession(): BrowserSession? {
        val id = runCatching { clientId(forceRefresh = false) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val token = session ?: return null
        return BrowserSession(id, token)
    }

    /** a comment from the api's own JSON, for a write the app made outside this client */
    fun commentFromJson(text: String): Comment? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let(::commentOf)

    /** the payload of a JWT-shaped web token, or null for a legacy opaque token */
    internal fun jwtPayload(token: String): JsonObject? {
        val part = token.split('.').getOrNull(1) ?: return null
        val text = base64Url(part)?.decodeToString() ?: return null
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    /** base64url without java.util.Base64, which only exists on newer Android than Mochy supports */
    internal fun base64Url(text: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val clean = text.trimEnd('=')
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (character in clean) {
            val value = alphabet.indexOf(character)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** A playable stream, resolved for one upload. */
    class Stream internal constructor(
        val url: String,
        val format: StreamFormat,
        val qualityLabel: String,
        val codec: String?,
    )

    /** A playlist (a set) as the browse screen lists it. */
    class Playlist internal constructor(
        val id: String,
        val title: String,
        val artist: String,
        val trackCount: Int,
        val artworkUrl: String?,
        val hasVietnameseEvidence: Boolean,
    )

    /** Searches the full catalog. Blocking: call it off the main thread. */
    fun search(text: String, limit: Int = SearchPage): List<Upload> = searchTracks(text, limit).map(::uploadOf)

    private fun searchTracks(text: String, limit: Int): List<ScTrack> {
        if (text.isBlank()) return emptyList()
        val response = catalogApi(SearchPath, mapOf("q" to text.trim(), "limit" to limit.coerceIn(1, MaxCatalogLimit).toString()))
        return requireObjectsIn(response.text, "collection").mapNotNull(::trackOf)
    }

    /** App-facing song search: only playable uploads with Vietnamese public metadata signals. */
    fun vietnameseSearch(text: String, limit: Int = SearchPage): List<Upload> =
        searchTracks(text, candidateLimit(limit)).vietnameseUploads(limit, excludedId = null)

    /** Searches playlists, then keeps only sets whose public metadata supports Vietnamese curation. */
    fun searchPlaylists(text: String, limit: Int = SearchPage): List<Playlist> {
        if (text.isBlank()) return emptyList()
        val response = catalogApi(PlaylistsPath, mapOf("q" to text.trim(), "limit" to limit.coerceIn(1, MaxCatalogLimit).toString()))
        return requireObjectsIn(response.text, "collection").mapNotNull(::playlistOf).filter { it.hasVietnameseEvidence }
    }

    /** the uploads of a playlist, in its own order. Blocking, as [search]. */
    fun playlistTracks(id: String): List<Upload> {
        val response = catalogApi("/playlists/$id", emptyMap())
        val root = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull()
            ?: throw SoundCloudApiException(response.code)
        val entries = (root["tracks"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        // a playlist answers with its first few uploads whole and the rest as bare ids: without
        // asking for those by id, a 416-track set lists as five tracks
        val known = mutableMapOf<String, Upload>()
        val stubs = mutableListOf<String>()
        entries.forEach { entry ->
            val upload = trackOf(entry)?.let(::uploadOf)
            if (upload != null) known[upload.id] = upload else entry.long("id")?.let { stubs += it.toString() }
        }
        stubs.chunked(IdsPerAsk).forEach { ids ->
            val answer = catalogApi(TracksPath, mapOf("ids" to ids.joinToString(",")))
            val whole = runCatching { json.parseToJsonElement(answer.text) as? JsonArray }.getOrNull()
                ?: throw SoundCloudApiException(answer.code)
            whole.mapNotNull { (it as? JsonObject)?.let(::trackOf) }.mapNotNull(::uploadOf)
                .forEach { known[it.id] = it }
        }
        return entries.mapNotNull { entry -> entry.long("id")?.toString()?.let(known::get) }
            .filter { it.hasVietnameseEvidence }
    }

    /** Explicit related-track browse. Blocking, as [search]. */
    fun related(id: String, limit: Int = RelatedPage): List<Upload> = relatedTracks(id, limit).map(::uploadOf)

    private fun relatedTracks(id: String, limit: Int): List<ScTrack> {
        if (!TrackIdPattern.matches(id)) return emptyList()
        val response = catalogApi("/tracks/$id/related", mapOf("limit" to limit.coerceIn(1, MaxCatalogLimit).toString()))
        return requireObjectsIn(response.text, "collection").mapNotNull(::trackOf)
    }

    /** Best-effort discovery based on public metadata, not a geographic or licensing filter. */
    fun vietnameseDiscovery(limit: Int = RelatedPage): List<Upload> = vietnameseDiscovery(limit, excludedId = null)

    private fun vietnameseDiscovery(limit: Int, excludedId: String?): List<Upload> =
        searchTracks(VietnameseQuery, candidateLimit(limit)).vietnameseUploads(limit, excludedId)

    /** Related tracks with Vietnamese evidence; no unfiltered fallback. */
    fun vietnameseRelated(id: String, limit: Int = RelatedPage): List<Upload> =
        relatedTracks(id, candidateLimit(limit)).vietnameseUploads(limit, excludedId = id)

    /** Prefer Vietnamese related tracks; if none are available, use only Vietnamese discovery. */
    fun recommendations(seed: String? = null, limit: Int = RelatedPage): List<Upload> {
        val value = seed?.trim().orEmpty()
        val id = value.takeIf { TrackIdPattern.matches(it) }
            ?: value.takeIf(String::isNotBlank)?.let { searchTracks(it, SearchSeedLimit).firstOrNull()?.catalog?.id }
        if (id != null) {
            val similar = try {
                vietnameseRelated(id, limit)
            } catch (error: SoundCloudApiException) {
                if (error.statusCode != 404) throw error
                emptyList()
            }
            if (similar.isNotEmpty()) return similar
        }
        return vietnameseDiscovery(limit, excludedId = id)
    }

    private fun candidateLimit(limit: Int): Int = (limit.coerceIn(1, MaxCatalogLimit) * 3).coerceAtMost(MaxCatalogLimit)

    private fun List<ScTrack>.vietnameseUploads(limit: Int, excludedId: String?): List<Upload> = asSequence()
        .filter { it.streamable && it.hasVietnameseEvidence && it.catalog.id != excludedId }
        .distinctBy { it.catalog.id }
        .take(limit.coerceIn(1, MaxCatalogLimit))
        .map(::uploadOf)
        .toList()

    private fun vietnameseEvidence(title: String, artist: String, upload: JsonObject, user: JsonObject?): Boolean {
        // An explicit non-Vietnam uploader/track country is stronger than title accents or tags.
        val countries = listOfNotNull(upload.string("country_code"), user?.string("country_code"))
            .map { it.trim().uppercase() }
            .filter(String::isNotEmpty)
        if (countries.any { it != "VN" }) return false
        if (countries.isNotEmpty()) return true
        if (upload.string("language")?.trim()?.let { VietnameseLanguage.matches(it) } == true) return true
        return listOfNotNull(title, artist, upload.string("tag_list"), upload.string("genre")).any { value ->
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            VietnameseCharacters.containsMatchIn(normalized) || VietnameseLabels.containsMatchIn(normalized)
        }
    }

    private fun uploadOf(upload: ScTrack) = Upload(
        id = upload.catalog.id,
        title = upload.catalog.title,
        artist = upload.catalog.artist,
        durationMs = upload.catalog.durationMs,
        artworkUrl = upload.artworkUrl,
        likesCount = upload.likesCount,
        commentCount = upload.commentCount,
        permalinkUrl = upload.permalinkUrl,
        hasVietnameseEvidence = upload.hasVietnameseEvidence,
    )

    internal fun playlistOf(element: JsonObject): Playlist? {
        val id = element.long("id") ?: return null
        val title = element.string("title")?.takeIf { it.isNotBlank() } ?: return null
        return Playlist(
            id = id.toString(),
            title = title,
            artist = (element["user"] as? JsonObject)?.string("username").orEmpty(),
            trackCount = element.long("track_count")?.toInt() ?: 0,
            artworkUrl = element.string("artwork_url")?.replace("-large", "-t500x500"),
            hasVietnameseEvidence = vietnameseEvidence(
                title, (element["user"] as? JsonObject)?.string("username").orEmpty(), element, element["user"] as? JsonObject,
            ),
        )
    }

    private fun requireObjectsIn(text: String, key: String): List<JsonObject> =
        objectsIn(text, key) ?: throw SoundCloudResponseException()

    private fun objectsIn(text: String, key: String): List<JsonObject>? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return (root[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
    }

    /**
     * Opens the upload with this id, without searching for it by name: the browse screen knows
     * exactly which upload was tapped, and three uploads can share a title. Blocking, as [search].
     */
    fun streamFor(id: String, quality: String = OptionBest): Stream? {
        val response = api("/tracks/$id", emptyMap()) ?: return null
        if (!response.ok) return null
        val upload = (runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull())?.let(::trackOf) ?: return null
        if (!upload.streamable) return null
        val opened = openStream(upload, quality) ?: return null
        return Stream(opened.url, opened.format, opened.quality, opened.codec)
    }

    internal fun mediaRequestUrl(url: String, clientId: String, authorization: String): String {
        val base = url.substringBefore('#')
        val separator = if ('?' in base) "&" else "?"
        return base + separator + queryString(mapOf("client_id" to clientId, "track_authorization" to authorization))
    }

    private fun isHttpsUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private fun transcodingOf(element: JsonObject): ScTranscoding? {
        val preset = element.string("preset") ?: return null
        val url = element.string("url")?.takeIf(::isHttpsUrl) ?: return null
        return ScTranscoding(
            preset = preset,
            url = url,
            protocol = (element["format"] as? JsonObject)?.string("protocol"),
        )
    }

    /**
     * The transcodings to try, best first. `abr_sq` is the adaptive one SoundCloud gives the web
     * player; not every upload carries it, so the 96k one is next and any other preset last.
     */
    internal fun ordered(transcodings: List<ScTranscoding>, quality: String): List<ScTranscoding> {
        // `cbc-encrypted-hls` is FairPlay and `ctr-encrypted-hls` is Widevine: both are DRM, and
        // handing one to the player only ends as a stream that dies after a second
        val playable = transcodings.filterNot { it.protocol?.contains("encrypted", ignoreCase = true) == true }
        val (wanted, rest) = playable.partition { it.preset in Preferred }
        val order = if (quality == OptionDataSaver) listOf(DataSaverPreset, BestPreset) else listOf(BestPreset, DataSaverPreset)
        val ranked = order.flatMap { preset -> wanted.filter { it.preset == preset } }
        return (ranked + rest).distinctBy { it.url }
    }

    /** an HLS playlist unless the resolved link names a file */
    internal fun formatOf(url: String): StreamFormat =
        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) StreamFormat.Hls else StreamFormat.Progressive

    internal fun qualityLabel(preset: String): String = when (preset) {
        BestPreset -> "AAC ~128"
        DataSaverPreset -> "AAC 96"
        else -> preset.replace('_', ' ').uppercase()
    }

    internal fun codecOf(preset: String): String? = when {
        preset.startsWith("aac") || preset.startsWith("abr") -> "AAC"
        preset.startsWith("mp3") || preset.startsWith("hls_mp3") -> "MP3"
        preset.startsWith("opus") -> "OPUS"
        else -> null
    }

    /** the first `client_id` a bundle names: `client_id:"<32 characters>"`, or its `=` form */
    internal fun bundleUrlsIn(html: String): List<String> = BundlePattern.findAll(html.replace("\\/", "/"))
        .map { match -> if (match.value.startsWith("//")) "https:${match.value}" else match.value }
        .distinct()
        .toList()
    internal fun clientIdIn(text: String): String? = ClientIdPattern.find(text)?.groupValues?.getOrNull(1)

    private fun field(text: String, name: String): String? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.string(name)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun queryString(params: Map<String, String>): String =
        params.entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }

    /** an upload as a search answer described it, with what is needed to open its stream */
    internal class ScTrack(
        val catalog: CatalogTrack,
        val transcodings: List<ScTranscoding>,
        val authorization: String,
        val streamable: Boolean,
        val artworkUrl: String? = null,
        val likesCount: Long? = null,
        val commentCount: Long? = null,
        val permalinkUrl: String? = null,
        val hasVietnameseEvidence: Boolean = false,
    )

    internal class ScTranscoding(val preset: String, val url: String, val protocol: String?)

    internal class ScStream(val url: String, val format: StreamFormat, val quality: String, val codec: String?)

    companion object {
        /** the songs this module remembers for the session; a long session plays more than this, so the oldest go */
        private const val RememberedMatches = 500
        private const val RememberedMisses = 2000

        /** the media id the app gives an upload of this module: the upload's own id, so playback opens it by id */
        const val MediaIdPrefix = "SC_"

        const val Id = "soundcloud"
        const val ChoiceQuality = "quality"
        const val OptionBest = "best"
        const val OptionDataSaver = "96"

        private const val BestPreset = "abr_sq"
        private const val DataSaverPreset = "aac_96k"
        private val Preferred = setOf(BestPreset, DataSaverPreset)

        private const val ApiBase = "https://api-v2.soundcloud.com"
        private const val OfficialApiBase = "https://api.soundcloud.com"
        private const val SearchPath = "/search/tracks"
        private const val PlaylistsPath = "/search/playlists"
        private const val TracksPath = "/tracks"
        private const val IdsPerAsk = 50
        private const val VietnameseQuery = "nhạc Việt"
        private val VietnameseLanguage = Regex("vi(?:[-_]vn)?", RegexOption.IGNORE_CASE)
        private val VietnameseCharacters = Regex("[ăđơưĂĐƠƯ\u1EA0-\u1EF9]")
        private val VietnameseLabels = Regex("""\b(?:v[- ]?pop|vinahouse|vietnamese|viet nam|vietnam|nhac viet|nhac tre)\b""", RegexOption.IGNORE_CASE)
        private const val RelatedPage = 20
        private const val HomePage = "https://soundcloud.com/"
        private const val MaxBundles = 12
        private const val MaxMatches = 10
        private const val SearchPage = 30
        private const val MaxCatalogLimit = 200
        private const val SearchSeedLimit = 5
        private val TrackIdPattern = Regex("[0-9]{1,20}")
        private const val MaxApiRetries = 2
        private const val MaxRetryDelayMs = 4_000L
        private const val ClientIdKey = "client_id"
        private const val ClientIdAtKey = "client_id_at"
        /** how long a key is trusted before it is read again even if it still answers */
        private const val ClientIdLifeMs = 30L * 24 * 60 * 60 * 1000
        private const val MissMemoryMs = 30L * 60 * 1000
        private const val MaxCachedMatches = 300
        private const val MaxCachedMisses = 300

        /** where the account's session and its label live in the module's own storage */
        private const val TokenKey = "session_token"
        private const val RefreshTokenKey = "refresh_token"
        private const val DataDomeKey = "datadome_cookie"
        private const val SessionClientIdKey = "session_client_id"
        private const val SessionExpiresAtKey = "session_expires_at"
        private const val LabelKey = "account_label"
        private const val ExpiredKey = "session_expired"
        private const val SignedInLabel = "Đã đăng nhập SoundCloud"
        private const val SessionEndedReason = "Phiên SoundCloud đã kết thúc. Hãy đăng nhập lại."
        private const val TokenUrl = "https://secure.soundcloud.com/oauth/token"
        private const val FormContentType = "application/x-www-form-urlencoded"
        private const val RefreshMarginMs = 60_000L
        private val tokenHeaders = mapOf("Accept" to "application/json")
        private const val EmptyCommentReason = "Bình luận không được để trống."
        const val MaxCommentLength = 1000

        private val SessionToken = Regex("[A-Za-z0-9._~+/=-]{20,4096}")
        private val DataDomeCookie = Regex("""(?:^|[;,\s])datadome=([^;,\s]+)""")
        private val AuthorizationPrefix = Regex("^(?i)oauth\\s+")

        private val BundlePattern = Regex("""(?i)(?:https?:)?//a-v2\.sndcdn\.com/assets/[A-Za-z0-9._-]+\.js(?:\?[^\s\"'<>]*)?""")
        private val ClientIdPattern = Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{32})["']""")

        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private val browserHeaders = mapOf("User-Agent" to UserAgent, "Accept-Language" to "en")
        private val apiHeaders = mapOf("Accept" to "application/json", "User-Agent" to UserAgent)
    }
}
