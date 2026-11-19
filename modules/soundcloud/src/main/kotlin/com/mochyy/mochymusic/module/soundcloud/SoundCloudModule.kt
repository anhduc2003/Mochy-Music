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
import java.util.concurrent.ConcurrentHashMap

/**
 * SoundCloud's catalog, played the way the web player plays it: an HLS stream behind a signed URL.
 *
 * No account is needed, because none of the public catalog is. SoundCloud's own API requires a
 * `client_id`, and there is no way to register for one that still works — the web player gets one
 * handed to it in its javascript. That key rotates, so it is read out of the player's own bundles
 * when SoundCloud answers 401, and kept in the module's storage in between; the same shape as the
 * player-js lookup Mochy already does for YouTube.
 *
 * What it is good at is what the other sources are not: independent artists, DJ sets, remixes, and
 * uploads 40 minutes long.
 */
class SoundCloudModule : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "SoundCloud",
        version = "1.0",
        description = "SoundCloud's catalog: independent artists, DJ sets and long mixes. No account needed",
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
            report(DiagnosticStep("Stream", false, "SoundCloud gave no playable stream"))
            return ResolveResult.Unavailable("SoundCloud gave no playable stream")
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

    private fun api(path: String, query: Map<String, String>, refreshed: Boolean = false): HttpResponse? {
        val clientId = clientId(forceRefresh = refreshed) ?: return null
        val url = ApiBase + path + "?" + queryString(query + ("client_id" to clientId))
        val response = runCatching { host.http(HttpRequest(url, headers = apiHeaders)) }.getOrNull() ?: return null
        // the key the web player handed out last week has been replaced: read it again, once
        if (response.code == 401 && !refreshed) return api(path, query, refreshed = true)
        return response
    }

    /** SoundCloud's shared key, read out of the web player's own javascript and kept until it stops working. */
    private fun clientId(forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            val saved = host.storage.get(ClientIdKey)?.takeIf { it.isNotBlank() }
            val savedAt = host.storage.get(ClientIdAtKey)?.toLongOrNull() ?: 0L
            if (saved != null && System.currentTimeMillis() - savedAt < ClientIdLifeMs) return saved
        }
        val found = discoverClientId() ?: return host.storage.get(ClientIdKey)?.takeIf { it.isNotBlank() }
        host.storage.set(ClientIdKey, found)
        host.storage.set(ClientIdAtKey, System.currentTimeMillis().toString())
        return found
    }

    private fun discoverClientId(): String? {
        val page = runCatching { host.http(HttpRequest(HomePage, headers = browserHeaders)) }.getOrNull() ?: return null
        if (!page.ok) return null
        val scripts = BundlePattern.findAll(page.text).map { it.value }.distinct().take(MaxBundles).toList()
        for (script in scripts) {
            val bundle = runCatching { host.http(HttpRequest(script, headers = browserHeaders)) }.getOrNull() ?: continue
            if (!bundle.ok) continue
            clientIdIn(bundle.text)?.let { return it }
        }
        return null
    }

    private fun openStream(track: ScTrack, quality: String): ScStream? {
        for (transcoding in ordered(track.transcodings, quality)) {
            val clientId = clientId(forceRefresh = false) ?: return null
            val url = transcoding.url + "?" + queryString(
                mapOf("client_id" to clientId, "track_authorization" to track.authorization),
            )
            val body = runCatching { host.http(HttpRequest(url, headers = apiHeaders)) }.getOrNull() ?: continue
            if (!body.ok) continue
            val resolved = field(body.text, "url")?.takeIf { it.startsWith("http") } ?: continue
            return ScStream(
                url = resolved,
                format = formatOf(resolved),
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
        return ScTrack(
            catalog = CatalogTrack(
                id = id.toString(),
                title = title,
                artist = (upload["user"] as? JsonObject)?.string("username").orEmpty(),
                durationMs = upload.long("full_duration")?.takeIf { it > 0 } ?: upload.long("duration"),
            ),
            transcodings = transcodings,
            authorization = upload.string("track_authorization").orEmpty(),
            streamable = upload.boolean("streamable") != false,
            artworkUrl = upload.string("artwork_url")?.replace("-large", "-t500x500"),
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
    )

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

    /** Searches the catalog. Blocking: call it off the main thread. */
    fun search(text: String, limit: Int = SearchPage): List<Upload> {
        if (text.isBlank()) return emptyList()
        val response = api(SearchPath, mapOf("q" to text.trim(), "limit" to limit.toString())) ?: return emptyList()
        if (!response.ok) return emptyList()
        return tracksIn(response.text).mapNotNull(::uploadOf)
    }

    /** Searches playlists and sets. Blocking, as [search]. */
    fun searchPlaylists(text: String, limit: Int = SearchPage): List<Playlist> {
        if (text.isBlank()) return emptyList()
        val response = api(PlaylistsPath, mapOf("q" to text.trim(), "limit" to limit.toString())) ?: return emptyList()
        if (!response.ok) return emptyList()
        val collection = objectsIn(response.text, "collection") ?: return emptyList()
        return collection.mapNotNull(::playlistOf)
    }

    /** the uploads of a playlist, in its own order. Blocking, as [search]. */
    fun playlistTracks(id: String): List<Upload> {
        val response = api("/playlists/$id", emptyMap()) ?: return emptyList()
        if (!response.ok) return emptyList()
        val root = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull() ?: return emptyList()
        val tracks = root["tracks"] as? JsonArray ?: return emptyList()
        return tracks.mapNotNull { (it as? JsonObject)?.let(::trackOf) }.mapNotNull(::uploadOf)
    }

    /** what SoundCloud is playing this week, so the screen has something before a search. Blocking. */
    fun charts(limit: Int = ChartsPage): List<Upload> {
        val response = api(
            ChartsPath,
            mapOf("kind" to ChartsKind, "genre" to ChartsGenre, "limit" to limit.toString()),
        ) ?: return emptyList()
        if (!response.ok) return emptyList()
        return chartTracks(response.text).mapNotNull(::uploadOf)
    }

    /** the uploads a charts answer lists: each entry wraps its track, the way the web player's own answer does */
    internal fun chartTracks(text: String): List<ScTrack> {
        val collection = objectsIn(text, "collection") ?: return emptyList()
        return collection.mapNotNull { it["track"] as? JsonObject }.mapNotNull(::trackOf)
    }

    private fun uploadOf(upload: ScTrack) = Upload(
        id = upload.catalog.id,
        title = upload.catalog.title,
        artist = upload.catalog.artist,
        durationMs = upload.catalog.durationMs,
        artworkUrl = upload.artworkUrl,
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

    private fun transcodingOf(element: JsonObject): ScTranscoding? {
        val preset = element.string("preset") ?: return null
        val url = element.string("url") ?: return null
        return ScTranscoding(preset = preset, url = url)
    }

    /**
     * The transcodings to try, best first. `abr_sq` is the adaptive one SoundCloud gives the web
     * player; not every upload carries it, so the 96k one is next and any other preset last.
     */
    internal fun ordered(transcodings: List<ScTranscoding>, quality: String): List<ScTranscoding> {
        val (wanted, rest) = transcodings.partition { it.preset in Preferred }
        val order = if (quality == OptionDataSaver) listOf(DataSaverPreset, BestPreset) else listOf(BestPreset, DataSaverPreset)
        val ranked = order.flatMap { preset -> wanted.filter { it.preset == preset } }
        return (ranked + rest).distinctBy { it.preset }
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
    )

    internal class ScTranscoding(val preset: String, val url: String)

    internal class ScStream(val url: String, val format: StreamFormat, val quality: String, val codec: String?)

    companion object {
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
        private const val ChartsPath = "/charts"
        private const val ChartsKind = "trending"
        private const val ChartsGenre = "soundcloud:genres:all-music"
        private const val ChartsPage = 20
        private const val HomePage = "https://soundcloud.com/"
        private const val MaxBundles = 12
        private const val MaxMatches = 10
        private const val SearchPage = 30
        private const val ClientIdKey = "client_id"
        private const val ClientIdAtKey = "client_id_at"
        /** how long a key is trusted before it is read again even if it still answers */
        private const val ClientIdLifeMs = 30L * 24 * 60 * 60 * 1000
        private const val MissMemoryMs = 30L * 60 * 1000
        private const val UnreachableRetryMs = 10L * 60 * 1000

        private val BundlePattern = Regex("""https://a-v2\.sndcdn\.com/assets/[A-Za-z0-9._-]+\.js""")
        private val ClientIdPattern = Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{32})["']""")

        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private val browserHeaders = mapOf("User-Agent" to UserAgent, "Accept-Language" to "en")
        private val apiHeaders = mapOf("Accept" to "application/json", "User-Agent" to UserAgent)
    }
}
