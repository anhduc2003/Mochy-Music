// StatusLyrics.kt

package com.mochyy.mochymusic.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.LyricsMotionStyle
import com.mochyy.mochymusic.lyrics.WordTimestamp
import com.mochyy.mochymusic.ui.player.KaraokeSweepText
import kotlinx.coroutines.delay

// The capsule is black like the phone's island and a touch shorter than the bar, so it sits
// in the row without touching its edges.
private val CapsuleColor = Color(0xF2000000)
private val RingTrack = Color(0x33FFFFFF)
private val CoverPlaceholder = Color(0xFF2A2B2D)
private val MetaInk = Color.White.copy(alpha = 0.66f)
// The part of the line that hasn't been sung yet, so the karaoke sweep has somewhere to go.
private val UnsungInk = Color.White.copy(alpha = 0.58f)
private val RingStroke = 1.5.dp
private val RingInset = 3.dp

// One size for every line: long and short lyrics read the same, and overflow scrolls inside
// the user's fixed capsule instead of shrinking only the long ones.
private const val LyricTextSp = 14f
private const val MetaTextSp = 10.5f

// Song above, the line being sung below, with the cover and its progress ring at the left.
// The window's width is the user's own setting and never changes because of a lyric.
@Composable
internal fun StatusLyrics(state: PillState) {
    val script = state.script
    var rowIndex by remember(script) {
        mutableIntStateOf(script?.let { it.stops[it.stopAt(state.lyricsClock())].top } ?: 0)
    }
    var dots by remember(script) { mutableIntStateOf(-1) }

    // Wakes only when the next line or dot is due, so nothing runs between lines.
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
            delay(((next - position) / state.speed()).toLong().coerceAtLeast(1L))
        }
    }

    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.playing, state.seekTick, state.song?.id) {
        while (true) {
            val duration = state.durationMs()
            val position = state.lyricsClock() - state.offsetMs
            progress.floatValue = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            if (!state.playing) break
            withFrameNanos { }
        }
    }

    val row = script?.rows?.getOrNull(rowIndex)
    val lyrics = when {
        row == null -> ""
        row.kind == PillRowKind.INTRO || dots >= 0 -> "• ".repeat(dots.coerceAtLeast(1)).trim()
        else -> row.text
    }
    val title = state.song?.title.orEmpty()
    val artist = state.song?.artists?.joinToString { it.name }.orEmpty()
    val meta = when {
        title.isBlank() -> ""
        artist.isBlank() -> title
        else -> "$title  ·  $artist"
    }

    // Keep the clock identity stable: the shared renderer reads playback time on each frame.
    val positionProvider = remember(state) { { state.lyricsClock() } }

    // Repeated text in different entries still gets its own entrance and karaoke state.
    val appear = remember(script, rowIndex) { Animatable(0f) }
    LaunchedEffect(appear) { appear.animateTo(1f, tween(durationMillis = 220)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(percent = 50))
            .background(CapsuleColor),
    ) {
        val cover = (maxHeight - 16.dp).coerceAtLeast(24.dp)
        val edge = 6.dp
        val gap = 10.dp
        val handleWidth = StatusGeometry.HandleWidthDp.dp
        Row(
            Modifier
                .fillMaxSize()
                .absolutePadding(right = handleWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(edge))
            CoverDisc(state, cover, progress)
            Spacer(Modifier.width(gap))
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MetaInk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = MetaTextSp.sp,
                        lineHeight = TextUnit.Unspecified,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(3.dp))
                key(script, rowIndex) {
                    // The line is centred in the user's fixed room. Marquee only scrolls
                    // when it really overflows, so its size stays identical for every line.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 400,
                                repeatDelayMillis = 800,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        LyricLine(
                            text = lyrics,
                            words = row?.words?.takeIf { dots < 0 },
                            positionProvider = positionProvider,
                            playing = state.playing,
                            seekTick = state.seekTick,
                            appear = { appear.value },
                        )
                    }
                }
            }
        }
        val resizeDescription = stringResource(R.string.floating_lyrics_resize)
        Spacer(
            Modifier
                .align(AbsoluteAlignment.BottomRight)
                .size(handleWidth, maxHeight)
                .semantics { contentDescription = resizeDescription }
                .drawBehind {
                    // A visible bottom-right grip; the window owns its entire touch strip.
                    val right = size.width - 9.dp.toPx()
                    val bottom = size.height - 9.dp.toPx()
                    for (length in listOf(5.dp.toPx(), 10.dp.toPx())) {
                        drawLine(
                            Color.White.copy(alpha = 0.8f),
                            Offset(right - length, bottom),
                            Offset(right, bottom - length),
                            1.5.dp.toPx(),
                            StrokeCap.Round,
                        )
                    }
                },
        )
    }
}

// One fixed size, centred. The part already sung is drawn over the part that isn't.
@Composable
private fun LyricLine(
    text: String,
    words: List<WordTimestamp>?,
    positionProvider: () -> Long,
    playing: Boolean,
    seekTick: Int,
    appear: () -> Float,
) {
    val fontSize = LyricTextSp.sp
    val baseStyle = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
        lineHeight = TextUnit.Unspecified,
    )
    Box(
        Modifier.graphicsLayer {
            val fraction = appear()
            alpha = fraction
            translationY = (1f - fraction) * 6.dp.toPx()
        },
    ) {
        if (!words.isNullOrEmpty() && text.isNotEmpty()) {
            KaraokeSweepText(
                text = text,
                words = words,
                positionProvider = positionProvider,
                waveAmplitudeProvider = { 1f },
                baseColor = { UnsungInk },
                highlightColor = Color.White,
                fontSize = fontSize,
                lineHeight = fontSize,
                textAlign = TextAlign.Center,
                motionStyle = LyricsMotionStyle.LEGACY,
                singleLine = true,
                animationRunning = playing,
                animationSeekTick = seekTick,
            )
        } else {
            BasicText(
                text = text,
                style = baseStyle.copy(color = Color.White, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// The song's cover in a circle, with how far along it is drawn around it.
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
