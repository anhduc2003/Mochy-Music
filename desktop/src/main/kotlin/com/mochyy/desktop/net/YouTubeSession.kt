package com.mochyy.desktop.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The part of YouTube's stream handshake that a plain HTTP client can actually perform.
 *
 * Verified against the live service while writing this: asking the player endpoint with no session
 * gets `LOGIN_REQUIRED` / `UNPLAYABLE` ("Cần tải lại trang này") from every client tried —
 * WEB, MWEB, TVHTML5, ANDROID_MUSIC, ANDROID_VR, ANDROID_TESTSUITE, ANDROID_CREATOR, VISIONOS.
 * YouTube now wants a proof-of-origin token for those.
 *
 * What *does* work, and is what this class reproduces, is the sequence yt-dlp uses:
 *
 *  1. GET the watch page with a browser user agent. That response sets the cookies the player
 *     endpoint wants (`VISITOR_INFO1_LIVE`, `__Secure-YNID`, `YSC`, …) and carries the page's
 *     `STS` value — the signature timestamp the JS player is currently built for.
 *  2. POST the player endpoint as the VISIONOS client, with those cookies, the visitor id from
 *     them, and `signatureTimestamp` in `playbackContext`. That client is not asked for a
 *     proof-of-origin token, and it answers with `streamingData` holding direct `url` fields —
 *     no `signatureCipher`, so no JS deciphering is needed either.
 *
 * The timestamp matters: a stale `STS` is what turns a playable answer into `UNPLAYABLE`, which is
 * exactly the failure this was first built with.
 *
 * This is inherently fragile. YouTube changes client requirements regularly; when this breaks, the
 * symptom is `streamUrl` returning null and the UI saying so, not silent failure.
 */
class YouTubeSession(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .cookieHandler(java.net.CookieManager())
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val browserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

    /** the VISIONOS client, which the browser agent of a Mac Safari is what its own headers claim */
    private val visionOsAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    data class Stream(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val itag: Int,
    )

    /** the visitor id the watch page handed out, reused for the player call */
    private var visitorId: String? = null

    /**
     * Resolves a playable audio stream for [videoId].
     *
     * Returns null when YouTube refuses, which is the honest outcome: there is no fallback that
     * invents a URL.
     */
    fun stream(videoId: String): Stream? {
        val sts = warmUp(videoId) ?: return null
        val body = """
            {"context":{"client":{"clientName":"VISIONOS","clientVersion":"1.02",
            "deviceMake":"Apple","deviceModel":"RealityDevice17,1",
            "userAgent":"$visionOsAgent","osName":"visionOS","osVersion":"26.5.23O471",
            "hl":"en","timeZone":"UTC","utcOffsetMinutes":0}},
            "videoId":"$videoId",
            "playbackContext":{"contentPlaybackContext":{"html5Preference":"HTML5_PREF_WANTS",
            "signatureTimestamp":$sts}},
            "contentCheckOk":true,"racyCheckOk":true}
        """.trimIndent().replace("\n", "")

        val request = HttpRequest.newBuilder(URI.create("https://www.youtube.com/youtubei/v1/player?prettyPrint=false"))
            .timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/json")
            .header("User-Agent", visionOsAgent)
            .header("X-Youtube-Client-Name", "101")
            .header("X-Youtube-Client-Version", "1.02")
            .header("Origin", "https://www.youtube.com")
            .header("Accept-Language", "en-us,en;q=0.5")
            .apply { visitorId?.let { header("X-Goog-Visitor-Id", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val raw = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()).body() }.getOrNull()
            ?: return null
        return parseStream(raw)
    }

    /**
     * Loads the watch page so the cookie jar fills and the signature timestamp is read.
     * A null return means the page did not carry an `STS`, so nothing can be asked for.
     */
    private fun warmUp(videoId: String): Long? {
        val request = HttpRequest.newBuilder(
            URI.create("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"),
        )
            .timeout(Duration.ofSeconds(25))
            .header("User-Agent", browserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-us,en;q=0.5")
            .GET()
            .build()
        val html = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()).body() }.getOrNull()
            ?: return null

        val sts = Regex(""""STS":(\d+)""").find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""signatureTimestamp["\s:]+(\d+)""").find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null

        visitorId = Regex(""""(?:VISITOR_DATA|visitorData)":"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""VISITOR_INFO1_LIVE=([^;]+)""").find(html)?.groupValues?.get(1)

        return sts
    }

    private fun parseStream(raw: String): Stream? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull()
        if (status != null && status != "OK") return null
        val streaming = root["streamingData"]?.jsonObject ?: return null

        // audio-only renditions, then any muxed one as a fallback
        val candidates = (streaming["adaptiveFormats"]?.jsonArray?.toList().orEmpty() +
            streaming["formats"]?.jsonArray?.toList().orEmpty())
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val url = obj["url"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
                Stream(
                    url = url,
                    mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull().orEmpty(),
                    bitrate = obj["bitrate"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0,
                    itag = obj["itag"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0,
                )
            }

        return candidates.filter { it.mimeType.startsWith("audio/") }.maxByOrNull { it.bitrate }
            ?: candidates.firstOrNull()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()
}
