// StatusLyrics.kt

package com.mochyy.mochymusic.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.delay

// the capsule is black like the phone's island and a touch shorter than the bar, so it sits in the row without touching its edges
private val CapsuleColor = Color(0xF2000000)
private val RingTrack = Color(0x33FFFFFF)
private val CoverPlaceholder = Color(0xFF2A2B2D)
private val RingStroke = 1.5.dp
private val RingInset = 3.dp

// how often the ring is redrawn; the line itself only wakes when it changes
private const val RingTickMs = 500L

// the capsule's sizes: as wide as the bar can spare, and as tall as the bar itself
@Immutable
internal class StatusMetrics(val width: Dp, val height: Dp, val cover: Dp, val gap: Dp, val edge: Dp)

internal fun statusMetrics(screenWidth: Dp, barHeight: Dp): StatusMetrics {
    val height = barHeight.coerceIn(26.dp, 38.dp)
    val width = (screenWidth - 40.dp).coerceAtMost(300.dp)
    return StatusMetrics(
        width = width,
        height = height,
        cover = (height - 10.dp).coerceAtLeast(16.dp),
        gap = 7.dp,
        edge = 5.dp,
    )
}

// the line being sung in a black capsule: the cover with how far along the song is as a ring, then the line itself
@Composable
internal fun StatusLyrics(state: PillState, metrics: StatusMetrics) {
    val script = state.script
    var rowIndex by remember(script) {
        mutableIntStateOf(script?.let { it.stops[it.stopAt(state.lyricsClock())].top } ?: 0)
    }
    var dots by remember(script) { mutableIntStateOf(-1) }

    // wakes only when the next line or dot is due, so nothing runs between lines
    LaunchedEffect(script, state.playing, state.seekTick) {
        val script = script ?: return@LaunchedEffect
        while (true) {
            val position = state.lyricsClock()
            val stop = script.stopAt(position)
            rowIndex = script.stops[stop].top
            dots = script.dotsLit(stop, position)
            if (!state.playing) break
            val next = script.nextChangeMs(stop, position)
            if (next == Long.MAX_VALUE) break
            delay(((next - position) / state.speed()).toLong().coerceAtLeast(40L))
        }
    }

    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.playing, state.seekTick, state.song?.id) {
        while (true) {
            val duration = state.durationMs()
            progress.floatValue = if (duration > 0L) (state.songPositionMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
            if (!state.playing) break
            delay(RingTickMs)
        }
    }

    val row = script?.rows?.getOrNull(rowIndex)
    val text = when {
        row == null -> state.song?.title.orEmpty()
        // the intro and the long breaks read as dots lighting up one by one
        row.kind == PillRowKind.INTRO -> "• ".repeat(dots.coerceAtLeast(1)).trim()
        else -> row.text
    }

    Row(
        modifier = Modifier
            .width(metrics.width)
            .height(metrics.height)
            .clip(RoundedCornerShape(percent = 50))
            .background(CapsuleColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(metrics.edge))
        CoverDisc(state, metrics, progress)
        Spacer(Modifier.width(metrics.gap))
        BasicText(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = Color.White,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(end = metrics.edge),
        )
    }
}

// the song's cover in a circle, with how far along it is drawn around it
@Composable
private fun CoverDisc(state: PillState, metrics: StatusMetrics, progress: MutableFloatState) {
    val context = LocalContext.current
    val coverPx = with(LocalDensity.current) { metrics.cover.roundToPx() }
    val url = state.song?.thumbnailUrl
    val request = remember(url, coverPx) { ImageRequest.Builder(context).data(url).size(coverPx).build() }
    Box(
        Modifier
            .size(metrics.cover)
            .drawBehind {
                val stroke = RingStroke.toPx()
                val radius = size.minDimension / 2f - stroke / 2f
                drawCircle(color = RingTrack, radius = radius, style = Stroke(stroke))
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.floatValue,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(RingInset)
                .clip(CircleShape)
                .background(CoverPlaceholder),
        )
    }
}
