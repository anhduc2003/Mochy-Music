// StatusLyrics.kt

package com.mochyy.mochymusic.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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

// the part of the line that hasn't been sung yet, so the karaoke sweep has somewhere to go
private val UnsungInk = Color(0x59FFFFFF)

// the line is laid out as big as it fits: a long one steps down until it sits inside the capsule instead of running out of it
private const val MaxTextSp = 13f
private const val MinTextSp = 8f
private const val SizeStepSp = 0.5f

// how often the sweep moves while a word-timed line is being sung, and how often the ring is redrawn
private const val SweepTickMs = 60L
private const val RingTickMs = 500L

private val LineStyle = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.SemiBold,
    color = Color.White,
)

// the line being sung in a black capsule: the cover with how far along the song is as a ring, then the line itself sweeping as it's sung
@Composable
internal fun StatusLyrics(state: PillState) {
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

    // a word-timed line is sung across its own span, so the sweep lands on the words as they come
    var sweep by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(rowIndex, script, state.playing, state.seekTick) {
        val words = script?.rows?.getOrNull(rowIndex)?.words
        if (words.isNullOrEmpty()) {
            sweep = 1f
            return@LaunchedEffect
        }
        val from = (words.first().startTime * 1000).toLong()
        val to = (words.last().endTime * 1000).toLong()
        while (true) {
            val position = state.lyricsClock()
            sweep = if (to > from) ((position - from).toFloat() / (to - from)).coerceIn(0f, 1f) else 1f
            if (!state.playing || sweep >= 1f) break
            delay(SweepTickMs)
        }
    }

    // each new line arrives rather than snapping in
    val appear = remember(text) { Animatable(0f) }
    LaunchedEffect(text) { appear.animateTo(1f, tween(durationMillis = 220)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(percent = 50))
            .background(CapsuleColor),
    ) {
        val density = LocalDensity.current
        val cover = (maxHeight - 10.dp).coerceAtLeast(16.dp)
        val edge = 5.dp
        val gap = 7.dp
        val room = with(density) { (maxWidth - edge * 2 - cover - gap).toPx() }

        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(edge))
            CoverDisc(state, cover, progress)
            Spacer(Modifier.width(gap))
            LyricLine(
                text = text,
                sweep = sweep,
                appear = appear.value,
                room = room,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = edge),
            )
        }
    }
}

// the line itself: as large as it fits the room it's given, with the part already sung drawn over the part that isn't
@Composable
private fun LyricLine(text: String, sweep: Float, appear: Float, room: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val fontSize = remember(text, room) {
        var size = MaxTextSp
        while (size > MinTextSp) {
            val width = measurer.measure(
                text = AnnotatedString(text),
                style = LineStyle.copy(fontSize = size.sp),
                softWrap = false,
                maxLines = 1,
            ).size.width
            if (width <= room) break
            size -= SizeStepSp
        }
        size.sp
    }

    Box(
        modifier.graphicsLayer {
            alpha = appear
            translationY = (1f - appear) * 6.dp.toPx()
        },
    ) {
        BasicText(
            text = text,
            style = LineStyle.copy(fontSize = fontSize, color = UnsungInk),
            maxLines = 1,
            softWrap = false,
        )
        BasicText(
            text = text,
            style = LineStyle.copy(fontSize = fontSize),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.drawWithContent {
                if (sweep <= 0f) return@drawWithContent
                clipRect(right = size.width * sweep.coerceIn(0f, 1f)) { this@drawWithContent.drawContent() }
            },
        )
    }
}

// the song's cover in a circle, with how far along it is drawn around it
@Composable
private fun CoverDisc(state: PillState, cover: Dp, progress: MutableFloatState) {
    val context = LocalContext.current
    val coverPx = with(LocalDensity.current) { cover.roundToPx() }
    val url = state.song?.thumbnailUrl
    val request = remember(url, coverPx) { ImageRequest.Builder(context).data(url).size(coverPx).build() }
    Box(
        Modifier
            .size(cover)
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
