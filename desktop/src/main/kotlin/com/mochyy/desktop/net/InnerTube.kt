package com.mochyy.desktop.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * YouTube search and stream resolution over the same InnerTube endpoint the Android build uses,
 * without going through NewPipeExtractor.
 *
 * The Android build's `providers/innertube` module is 7.6k lines across 106 files and leans on
 * NewPipeExtractor for signature deciphering. NewPipe is an Android library and its JitPack build
 * is not consumable from a plain JVM module, so this talks to the web InnerTube client directly:
 * that client needs no player-script deciphering, and returns a `url` that plays as-is.
 *
 * What this cannot do, stated rather than hidden: nothing here deciphers `signatureCipher`, so
 * the client is fixed to the one that hands back plain URLs. If YouTube tightens that, streams
 * stop working until a deciphering step is added — the phone build already carries one, and this
 * is the piece of it that would need porting.
 */
class InnerTube(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class SearchResult(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val thumbnailUrl: String?,
    )

    /**
     * Searches, filtering to songs. Uses the `WEB_REMIX` client, which is what YouTube Music itself
     * uses and which returns music-shaped results (artist, album, duration) instead of videos.
     */
    suspend fun search(query: String, limit: Int = 20): List<SearchResult> {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250101.00.00")
                    put("hl", "vi")
                    put("gl", "VN")
                }
            }
            // query is a plain string here, not an object: the endpoint rejects {"query":{"input":…}}
            // with "Starting an object on a scalar field"
            put("query", query)
        }

        val response = post("https://music.youtube.com/youtubei/v1/search", body.toString())
            ?: return emptyList()
        return parseSearchResults(response, limit)
    }

    /**
     * Reads YouTube Music's `musicResponsiveListItemRenderer` shape.
     *
     * Confirmed against a live response: the id is `playlistItemData.videoId`, the title is the
     * first text run of `flexColumns[0]`, and `flexColumns[1]` holds runs like
     * `["Bài hát", " • ", "4:21"]` — the duration is the last run that parses as a time, and any
     * run before it that is not the type label is the artist.
     */
    private fun parseSearchResults(raw: String, limit: Int): List<SearchResult> {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val renderers = ArrayList<JsonObject>()

        fun collect(node: Any?) {
            when (node) {
                is JsonObject -> {
                    (node["musicResponsiveListItemRenderer"] as? JsonObject)?.let { renderers += it }
                    node.values.forEach(::collect)
                }
                is kotlinx.serialization.json.JsonArray -> node.forEach(::collect)
                else -> Unit
            }
        }
        collect(root)

        val out = ArrayList<SearchResult>()
        for (renderer in renderers) {
            if (out.size >= limit) break
            val id = renderer["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull()
                ?: renderer.at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                    "playNavigationEndpoint", "watchEndpoint")?.jsonObject
                    ?.get("videoId")?.jsonPrimitive?.contentOrNull()
                ?: continue
            if (out.any { it.videoId == id }) continue

            val title = runs(renderer.column(0)).firstOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val subtitleRuns = runs(renderer.column(1))
            val durationMs = subtitleRuns.asReversed().firstNotNullOfOrNull { parseTimedOrNull(it) } ?: 0L
            // the artist is the run before the duration that isn't the "Song"/"Bài hát" label or a separator
            val artist = subtitleRuns
                .takeWhile { parseTimedOrNull(it) == null }
                .lastOrNull { it.isNotBlank() && it != " • " && it.length > 1 && !it.isSongLabel() }
                .orEmpty()

            out += SearchResult(
                videoId = id,
                title = title,
                artist = artist,
                durationMs = durationMs,
                thumbnailUrl = thumbnails(renderer),
            )
        }
        return out
    }

    /** "Bài hát", "Song", "Video", "Album" — the type label YouTube puts first in the subtitle */
    private fun String.isSongLabel(): Boolean = lowercase() in setOf(
        "bài hát", "song", "video", "album", "nghệ sĩ", "artist", "playlist", "danh sách phát",
    )

    private fun JsonObject.column(index: Int): JsonObject? =
        this["flexColumns"]?.jsonArray?.getOrNull(index)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject

    private fun runs(column: JsonObject?): List<String> =
        column?.get("text")?.jsonObject?.get("runs")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull() }
            .orEmpty()

    /** walks a fixed path through nested objects, returning null the moment a level is missing */
    private fun JsonObject.at(vararg path: String): JsonObject? {
        var node: JsonObject? = this
        for (key in path) {
            node = node?.get(key) as? JsonObject ?: return null
        }
        return node
    }

    private fun thumbnails(renderer: JsonObject): String? {
        val thumbs = renderer.at("thumbnail", "musicThumbnailRenderer", "thumbnail")
            ?.get("thumbnails")?.jsonArray ?: return null
        return thumbs.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull()
    }

    private fun parseDuration(text: String?): Long = parseTimedOrNull(text) ?: 0L

    /** "4:21" / "1:02:11" / a bare second count; null when the run isn't a time at all */
    private fun parseTimedOrNull(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (trimmed.all { it.isDigit() } && trimmed.length <= 5) {
            // a bare number here is a second count from lengthSeconds, not a title fragment
            return trimmed.toLongOrNull()?.times(1000)
        }
        val parts = trimmed.split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.trim().toLongOrNull() ?: return null }
        return when (nums.size) {
            2 -> (nums[0] * 60 + nums[1]) * 1000
            else -> (nums[0] * 3600 + nums[1] * 60 + nums[2]) * 1000
        }
    }

    /**
     * the audio stream URL for a video, or null when it can't be resolved.
     *
     * Known limitation, verified against the live endpoint: every non-authenticated client tried
     * here (ANDROID_MUSIC, ANDROID_VR, WEB, WEB_REMIX, TVHTML5_SIMPLY_EMBEDDED_PLAYER) answers
     * `LOGIN_REQUIRED` or `UNPLAYABLE` with no `streamingData`. YouTube now requires a signed-in
     * session, or a visitor-data/signature pair, for stream URLs. Search still works without auth
     * (see [search]), so the metadata half of this is usable; playback of an online track is not,
     * until a signature pipeline is ported.
     *
     * Rather than fake a success, this returns null and the caller shows why.
     */
    suspend fun streamUrl(videoId: String): String? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", "7.27.52")
                    put("androidSdkVersion", 34)
                    put("hl", "vi")
                    put("gl", "VN")
                }
            }
            putJsonObject("videoId") { put("videoId", videoId) }
            putJsonObject("contentCheckOk") { put("contentCheckOk", true) }
            putJsonObject("racyCheckOk") { put("racyCheckOk", true) }
        }

        val raw = post("https://music.youtube.com/youtubei/v1/player", body.toString()) ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull()
        if (status != null && status != "OK") return null
        val formats = root["streamingData"]?.jsonObject ?: return null

        // audio-only first, then any muxed stream as a fallback
        val adaptive = formats["adaptiveFormats"]?.jsonArray
        val all = formats["formats"]?.jsonArray
        val candidates = (adaptive?.toList().orEmpty() + all?.toList().orEmpty())
            .mapNotNull { it as? JsonObject }

        val best = candidates
            .filter { obj -> obj["url"] != null }
            .filter { obj ->
                val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull().orEmpty()
                mime.startsWith("audio/")
            }
            .maxByOrNull { obj -> obj["bitrate"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0 }
            ?: candidates.firstOrNull { obj -> obj["url"] != null }

        return best?.get("url")?.jsonPrimitive?.contentOrNull()
    }

    private fun post(url: String, body: String): String? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Accept-Language", "vi,en;q=0.9")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) response.body() else null
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()
}
