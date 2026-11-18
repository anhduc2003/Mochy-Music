package com.mochyy.mochymusic.ui.player

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mochyy.mochymusic.lyrics.WordTimestamp

@Immutable
internal data class NeonLyricPalette(
    val primary: Color,
    val secondary: Color,
    val hotCore: Color,
    val unlitTube: Color,
)

@Immutable
internal data class MaskLyricPalette(
    val primary: Color,
    val secondary: Color,
    val prismEdge: Color,
    val ribbonFill: Color,
    val ribbonStroke: Color,
    val stencilDim: Color,
)

internal object SmartLyricPalette {

    fun neonOf(accent: Color): NeonLyricPalette {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(accent.toArgb(), hsv)
        val sat = hsv[1]
        val primary: Color
        val secondary: Color
        if (sat < 0.22f) {
            // Monochrome or white cover: signature electric cyan -> neon magenta plasma
            primary = Color(0xFF00F0FF)
            secondary = Color(0xFFFF3DF0)
        } else {
            val h1 = hsv[0]
            val s1 = (sat * 1.35f).coerceIn(0.76f, 1f)
            primary = Color(AndroidColor.HSVToColor(floatArrayOf(h1, s1, 1f)))
            val h2 = (h1 + 48f) % 360f
            secondary = Color(AndroidColor.HSVToColor(floatArrayOf(h2, 0.88f, 1f)))
        }
        val hotCore = mixColor(primary, Color.White, 0.72f)
        val unlitTube = mixColor(primary, Color.White, 0.45f).copy(alpha = 0.30f)
        return NeonLyricPalette(
            primary = primary,
            secondary = secondary,
            hotCore = hotCore,
            unlitTube = unlitTube,
        )
    }

    fun maskOf(accent: Color): MaskLyricPalette {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(accent.toArgb(), hsv)
        val sat = hsv[1]
        val primary: Color
        val secondary: Color
        if (sat < 0.22f) {
            // Monochrome or white cover: luminous ice-cyan -> warm rose-coral prism
            primary = Color(0xFF5CE1FF)
            secondary = Color(0xFFFF7EB3)
        } else {
            val h1 = hsv[0]
            val s1 = sat.coerceIn(0.62f, 0.92f)
            primary = Color(AndroidColor.HSVToColor(floatArrayOf(h1, s1, 0.98f)))
            val h2 = (h1 + 36f) % 360f
            secondary = Color(AndroidColor.HSVToColor(floatArrayOf(h2, 0.76f, 1f)))
        }
        val prismEdge = mixColor(secondary, Color.White, 0.80f)
        return MaskLyricPalette(
            primary = primary,
            secondary = secondary,
            prismEdge = prismEdge,
            ribbonFill = primary.copy(alpha = 0.16f),
            ribbonStroke = primary.copy(alpha = 0.38f),
            stencilDim = Color.White.copy(alpha = 0.26f),
        )
    }

    private fun mixColor(a: Color, b: Color, t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * f,
            green = a.green + (b.green - a.green) * f,
            blue = a.blue + (b.blue - a.blue) * f,
            alpha = a.alpha + (b.alpha - a.alpha) * f,
        )
    }

    /**
     * Synthesizes natural syllable- and punctuation-weighted word timings for line-synced LRC tracks
     * so Neon and Mask animate smoothly even when word-level RichSync metadata is absent.
     */
    fun smartSynthesizedWords(
        text: String,
        startMs: Long,
        endMs: Long,
    ): List<WordTimestamp> {
        val trimmedLine = text.trim()
        if (trimmedLine.isEmpty()) return emptyList()
        val rawSpanMs = (endMs - startMs).coerceAtLeast(400L)
        // Singers typically finish articulating slightly before the next line's timestamp; cap long gaps.
        val maxVocalMs = (sungLength(trimmedLine) * 185L).coerceIn(900L, 6_500L)
        val vocalSpanMs = minOf((rawSpanMs * 0.88f).toLong().coerceAtLeast(360L), maxVocalMs)

        val (starts, ends) = visualWords(text)
        if (starts.isEmpty()) return emptyList()

        val weights = FloatArray(starts.size)
        var totalWeight = 0f
        for (i in starts.indices) {
            val token = text.substring(starts[i], ends[i])
            val base = sungLength(token).toFloat().coerceAtLeast(1f)
            val lastChar = token.lastOrNull()
            val pauseBonus = when (lastChar) {
                ',', ';', '、', '，' -> 1.4f
                '.', '!', '?', '。', '！', '？', '…' -> 2.1f
                '-', '—' -> 1.1f
                else -> 0f
            }
            val w = base + pauseBonus
            weights[i] = w
            totalWeight += w
        }
        if (totalWeight <= 0f) return emptyList()

        val result = ArrayList<WordTimestamp>(starts.size)
        var cursorSec = startMs / 1000.0
        val spanSec = vocalSpanMs / 1000.0
        for (i in starts.indices) {
            val nextStart = if (i < starts.lastIndex) starts[i + 1] else text.length
            val piece = text.substring(starts[i], nextStart)
            val durSec = (weights[i] / totalWeight) * spanSec
            val endSec = cursorSec + durSec
            result += WordTimestamp(
                text = piece,
                startTime = cursorSec,
                endTime = endSec,
            )
            cursorSec = endSec
        }
        return result
    }
}
