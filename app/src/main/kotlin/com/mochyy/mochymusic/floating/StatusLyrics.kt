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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.style.TextAlign
import com.mochyy.mochymusic.constants.LyricsMotionStyle
import com.mochyy.mochymusic.lyrics.WordTimestamp
import com.mochyy.mochymusic.ui.player.KaraokeSweepText
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
import com.mochyy.mochymusic.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

// the capsule is black like the phone's island and a touch shorter than the bar, so it sits in the row without touching its edges
private val CapsuleColor = Color(0xF2000000)
private val RingTrack = Color(0x33FFFFFF)
private val CoverPlaceholder = Color(0xFF2A2B2D)
private val RingStroke = 1.5.dp
private val RingInset = 3.dp

// the part of the line that hasn't been sung yet, so the karaoke sweep has somewhere to go
private val UnsungInk = Color.White.copy(alpha = 0.58f)

// the line is laid out as big as it fits: a long one steps down until it sits inside the capsule instead of running out of it
private const val MaxTextSp = 13f
private const val MinTextSp = 8f
private const val SizeStepSp = 0.5f

// the line being sung in a black capsule: the cover with how far along the song is as a ring, then the line itself sweeping as it's sung
@Composable
internal fun StatusLyrics(
    state: PillState,
    fittingWidthDp: Float,
    onContentWidth: (Float) -> Unit,
    // Which side stays put while the capsule animates: the content is laid out at the
    // full width and simply revealed from that side instead of being squeezed.
    anchor: StatusGeometry.Anchor = StatusGeometry.Anchor.CENTER,
) {
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
    val text = when {
        row == null -> state.song?.title.orEmpty()
        // the intro and the long breaks read as dots lighting up one by one
        row.kind == PillRowKind.INTRO -> "• ".repeat(dots.coerceAtLeast(1)).trim()
        else -> row.text
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
        val density = LocalDensity.current
        val cover = (maxHeight - 10.dp).coerceAtLeast(16.dp)
        val edge = 5.dp
        val gap = 7.dp
        val handleWidth = StatusGeometry.HandleWidthDp.dp
        val measurer = rememberTextMeasurer()
        val preferredStyle = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = MaxTextSp.sp,
        )
        // Keep the lyric's measured width stable for the whole resize animation, but size
        // it to this line's target capsule width instead of the user's saved maximum.
        // Otherwise short lyrics leave a wide requiredWidth child centered in a narrow
        // adaptive capsule and get clipped at both ends.
        val contentWidth = remember(text, preferredStyle, measurer, density.density, density.fontScale, cover) {
            val measured = measurer.measure(
                text = AnnotatedString(text),
                style = preferredStyle,
                softWrap = false,
                maxLines = 1,
            ).size.width
            StatusGeometry.contentWidthDp(
                measured.toFloat(), density.density, cover.value, edge.value,
                gap.value, handleWidth.value, paddingDp = 4f,
            )
        }
        // This target is independent of the animated WindowManager width. The font and
        // text child therefore stay stable while the capsule grows or shrinks.
        val targetWidthDp = StatusGeometry.adaptiveWidth(contentWidth, fittingWidthDp, fittingWidthDp)
        val room = with(density) {
            (targetWidthDp.dp - edge - cover - gap - handleWidth).toPx().coerceAtLeast(1f)
        }
        LaunchedEffect(contentWidth, onContentWidth) { onContentWidth(contentWidth) }

        val contentAlignment = when (anchor) {
            StatusGeometry.Anchor.LEFT -> Alignment.CenterStart
            StatusGeometry.Anchor.RIGHT -> Alignment.CenterEnd
            StatusGeometry.Anchor.CENTER -> Alignment.Center
        }
        Row(
            Modifier
                .align(contentAlignment)
                .fillMaxWidth()
                .absolutePadding(right = handleWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(edge))
            CoverDisc(state, cover, progress)
            Spacer(Modifier.width(gap))
            key(script, rowIndex) {
                // The line is laid out once at the stable room width so the animating
                // capsule never re-shapes the glyphs or the karaoke layer; the box around
                // it is what the window shrinks, and it clips the overhang on the right
                // instead of letting the row overflow the capsule on both sides.
                Box(Modifier.weight(1f)) {
                    LyricLine(
                        text = text,
                        words = row?.words,
                        positionProvider = positionProvider,
                        playing = state.playing,
                        seekTick = state.seekTick,
                        appear = { appear.value },
                        room = room,
                        modifier = Modifier.requiredWidth(with(density) { room.toDp() }),
                    )
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

// the line itself: as large as it fits the room it's given, with the part already sung drawn over the part that isn't
@Composable
private fun LyricLine(
    text: String,
    words: List<WordTimestamp>?,
    positionProvider: () -> Long,
    playing: Boolean,
    seekTick: Int,
    appear: () -> Float,
    room: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    val fontSize = remember(text, room, baseStyle, density.density, density.fontScale, measurer) {
        var size = MaxTextSp
        while (size > MinTextSp) {
            val width = measurer.measure(
                text = AnnotatedString(text),
                style = baseStyle.copy(fontSize = size.sp),
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
                lineHeight = (fontSize.value * 1.25f).sp,
                textAlign = TextAlign.Start,
                motionStyle = LyricsMotionStyle.LEGACY,
                singleLine = true,
                animationRunning = playing,
                animationSeekTick = seekTick,
            )
        } else {
            BasicText(
                text = text,
                style = baseStyle.copy(fontSize = fontSize, color = Color.White),
                maxLines = 1,
                softWrap = false,
            )
        }
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
