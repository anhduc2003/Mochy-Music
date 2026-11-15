package com.mochyy.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.desktop.model.Track
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Cover art and the colours that come out of it.
 *
 * The Android player paints a blurred, warped backdrop behind everything and derives its accent
 * from the artwork; that is 128k lines of custom drawing. What carries the visual identity at a
 * fraction of the cost is the part that matters: the cover itself, and a two-stop gradient pulled
 * from its dominant colour, so the window takes on the song's tint instead of staying flat grey.
 */
object CoverArt {

    private val cache = ConcurrentHashMap<String, ImageBitmap?>()
    private val colorCache = ConcurrentHashMap<String, Color>()

    /** the artwork for a track: its own cover tag first, then a http thumbnail */
    fun load(track: Track?): ImageBitmap? {
        val key = track?.id ?: return null
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null
        val image = runCatching { readArtwork(track) }.getOrNull()
        cache[key] = image
        if (image != null) {
            runCatching { colorCache[key] = dominantColor(image) }
        }
        return image
    }

    private fun readArtwork(track: Track): ImageBitmap? {
        // a local file: ask the tag reader for the embedded picture
        if (!track.uri.startsWith("http")) {
            val tags = runCatching { org.jaudiotagger.audio.AudioFileIO.read(java.io.File(track.uri)) }.getOrNull()
            val art = tags?.tag?.firstArtwork?.binaryData
            if (art != null && art.isNotEmpty()) {
                return ImageIO.read(art.inputStream())?.toComposeImageBitmap()
            }
        }
        val url = track.thumbnailUrl ?: return null
        if (!url.startsWith("http")) return null
        val connection = runCatching { URI(url).toURL().openConnection() }.getOrNull() ?: return null
        runCatching {
            connection.connectTimeout = 8_000
            connection.connect()
            ImageIO.read(connection.getInputStream())?.toComposeImageBitmap()
        }.getOrNull()?.let { return it }
        return null
    }

    /**
     * A colour that reads well as a backdrop: the average of the artwork's more vivid pixels,
     * dimmed so white text stays legible on top of it.
     */
    private fun dominantColor(image: ImageBitmap): Color {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val pixels = image.readPixels()
        val step = 16 // sample rather than walk every pixel; the average is just as representative
        val buffer = IntArray(4)
        var index = 0
        while (index * 4 + 3 < pixels.size) {
            val argb = pixels[index * 4 + 3]
            buffer[0] = argb
            val a = (argb shr 24) and 0xFF
            if (a > 32) {
                val red = (argb shr 16) and 0xFF
                val green = (argb shr 8) and 0xFF
                val blue = argb and 0xFF
                // weigh by how saturated the pixel is, so a grey sleeve doesn't muddy the tint
                val max = maxOf(red, green, blue)
                val min = minOf(red, green, blue)
                val weight = 1 + (max - min) / 32
                r += red.toLong() * weight
                g += green.toLong() * weight
                b += blue.toLong() * weight
                n += weight
            }
            index += step
        }
        if (n == 0L) return Color(0xFF232330)
        // pulled toward black so the backdrop stays a backdrop
        return Color(
            red = ((r / n) * 0.55f).toInt().coerceIn(0, 255),
            green = ((g / n) * 0.55f).toInt().coerceIn(0, 255),
            blue = ((b / n) * 0.55f).toInt().coerceIn(0, 255),
        )
    }

    private fun ImageBitmap.readPixels(): IntArray {
        val bitmap = this
        val buffer = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(buffer)
        // repack as one int per pixel so the walk above stays simple
        val out = IntArray(buffer.size * 4)
        for (i in buffer.indices) {
            out[i * 4] = 0
            out[i * 4 + 3] = buffer[i]
        }
        return out
    }

    fun accent(trackId: String?): Color =
        trackId?.let { colorCache[it] } ?: Color(0xFF232330)

    fun clear() {
        cache.clear()
        colorCache.clear()
    }
}

/** the backdrop: the cover's own colour at the top, fading into the window colour */
@Composable
fun SongBackdrop(track: Track?, cover: ImageBitmap?, modifier: Modifier = Modifier) {
    val tint = remember(track?.id, cover) { CoverArt.accent(track?.id) }
    Box(
        modifier.background(
            Brush.verticalGradient(
                0f to tint,
                0.45f to Color(0xFF191920),
                1f to Color(0xFF121216),
            ),
        ),
    )
}

/** the big square artwork with a soft shadow, falling back to the title when a song has none */
@Composable
fun CoverCard(cover: ImageBitmap?, title: String, size: Int = 168, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2A2A36), Color(0xFF1C1C24)),
                    start = Offset.Zero,
                    end = Offset(size.toFloat(), size.toFloat()),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            androidx.compose.foundation.Image(
                bitmap = cover,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                title.take(2).uppercase(),
                color = Color(0xFF4A4A58),
                fontSize = (size / 4).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** the now-playing block that sits above the transport: cover, title, artist, album */
@Composable
fun NowPlayingHeader(track: Track?, cover: ImageBitmap?, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverCard(cover, track?.title ?: "Mochy", size = 92)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(
                track?.title ?: "Chưa phát bài nào",
                color = if (track == null) Color(0xFF5A5A68) else Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!track?.artist.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    track.artist,
                    color = Color(0xFFA0A0B0),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            track?.album?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = Color(0xFF6A6A78), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
