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
import com.mochyy.mochymusic.module.NO_ACCOUNT_LABEL
import com.mochyy.mochymusic.module.ResolveResult
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
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * SoundCloud's catalog, played the way the web player plays it: an HLS stream behind a signed URL.
 *
 * Public catalog requests currently use the web player's rotating client ID. Account APIs need
 * separately registered OAuth credentials; a public client ID is not a logged-in user session.
 * The web client ID is read from player bundles when expired or rejected.
 *
 * What it is good at is what the other sources are not: independent artists, DJ sets, remixes, and
 * uploads 40 minutes long.
 */
class SoundCloudModule : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "SoundCloud",
        version = "1.1",
        description = "Danh mục SoundCloud: tìm kiếm, gợi ý nhạc Việt theo metadata công khai, phát nhạc, xem bình luận và lượt thích. Đăng nhập tài khoản mở trên SoundCloud.",
    )

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val matches = ConcurrentHashMap<String, ScTrack>()
    private val misses = ConcurrentHashMap<String, Long>()

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

    // a server that didn't answer is left alone for a while, as the other sources do
    override fun accountState(): AccountState {
        val reason = host.storage.get("unreachable") ?: return AccountState.SignedIn(NO_ACCOUNT_LABEL)
        val markedAt = host.storage.get("unreachable_at")?.toLongOrNull() ?: 0L
        return if (System.currentTimeMillis() - markedAt < UnreachableRetryMs) AccountState.Expired(reason) else AccountState.SignedIn(NO_ACCOUNT_LABEL)
    }

    override suspend fun verifyAccount(): AccountState {
        val response = runCatching { api(SearchPath, mapOf("q" to "test", "limit" to "1")) }.getOrNull()
        return if (response?.ok == true) {
            host.storage.set("unreachable", null)
            AccountState.SignedIn("$NO_ACCOUNT_LABEL · server reachable")
        } else {
            val reason = "SoundCloud didn't answer (${response?.code ?: "offline"})"
            markUnreachable(reason)
            AccountState.Expired(reason)
        }
    }

    fun reactivate() {
        host.storage.set("unreachable", null)
        host.storage.set("unreachable_at", null)
        misses.clear()
    }

    override fun signOut() {
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
                misses[key] = System.currentTimeMillis()
                report(DiagnosticStep("Find the song", false, "No matching upload of “${track.title}”"))
                return ResolveResult.NotFound("No matching upload on SoundCloud")
            }
            matches[key] = best
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

    private fun api(path: String, query: Map<String, String>): HttpResponse? =
        requestWithClientId { id -> ApiBase + path + "?" + queryString(query + ("client_id" to id)) }

    private fun requestWithClientId(urlFor: (String) -> String): HttpResponse? {
        var activeClientId = clientId(forceRefresh = false) ?: return null
        var refreshed = false
        var retry = 0
        while (true) {
            val response = runCatching {
                host.http(HttpRequest(urlFor(activeClientId), headers = apiHeaders))
            }.getOrNull()
            if (response?.code == 401 && !refreshed) {
                host.storage.set(ClientIdKey, null)
                host.storage.set(ClientIdAtKey, null)
                activeClientId = clientId(forceRefresh = true) ?: return response
                refreshed = true
                continue
            }
            // A 401 after key refresh is terminal; it can indicate auth/access restrictions,
            // and must not be disguised as a different-key retry.
            if (response?.code == 401) return response
            if (retry < MaxApiRetries && (response == null || response.code == 429 || response.code in 500..599)) {
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

    private fun catalogApi(path: String, query: Map<String, String>): HttpResponse {
        val response = api(path, query) ?: throw SoundCloudApiException(null)
        if (!response.ok) throw SoundCloudApiException(response.code)
        return response
    }

    private fun discoverClientId(): String? {
        val page = runCatching { host.http(HttpRequest(HomePage, headers = browserHeaders)) }.getOrNull() ?: return null
        if (!page.ok) return null
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
    )

    data class Comment(
        val id: String,
        val body: String,
        val author: String,
        val timestampMs: Long?,
        val avatarUrl: String?,
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
        (objectsIn(text, "collection") ?: emptyList()).mapNotNull { entry ->
            val id = entry.long("id") ?: return@mapNotNull null
            val body = entry.string("body")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val user = entry["user"] as? JsonObject
            Comment(
                id.toString(), body, user?.string("username").orEmpty(),
                entry.long("timestamp")?.takeIf { it >= 0 }, user?.string("avatar_url"),
            )
        }.distinctBy { it.id }

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
    )

    /** Searches the full catalog. Blocking: call it off the main thread. */
    fun search(text: String, limit: Int = SearchPage): List<Upload> = searchTracks(text, limit).map(::uploadOf)

    private fun searchTracks(text: String, limit: Int): List<ScTrack> {
        if (text.isBlank()) return emptyList()
        val response = catalogApi(SearchPath, mapOf("q" to text.trim(), "limit" to limit.coerceIn(1, MaxCatalogLimit).toString()))
        return requireObjectsIn(response.text, "collection").mapNotNull(::trackOf)
    }

    /** Searches playlists and sets. Blocking, as [search]. */
    fun searchPlaylists(text: String, limit: Int = SearchPage): List<Playlist> {
        if (text.isBlank()) return emptyList()
        val response = catalogApi(PlaylistsPath, mapOf("q" to text.trim(), "limit" to limit.coerceIn(1, MaxCatalogLimit).toString()))
        val collection = requireObjectsIn(response.text, "collection")
        return collection.mapNotNull(::playlistOf)
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
        private const val UnreachableRetryMs = 10L * 60 * 1000

        private val BundlePattern = Regex("""(?i)(?:https?:)?//a-v2\.sndcdn\.com/assets/[A-Za-z0-9._-]+\.js(?:\?[^\s\"'<>]*)?""")
        private val ClientIdPattern = Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{32})["']""")

        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private val browserHeaders = mapOf("User-Agent" to UserAgent, "Accept-Language" to "en")
        private val apiHeaders = mapOf("Accept" to "application/json", "User-Agent" to UserAgent)
    }
}
