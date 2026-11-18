// StatusLyrics.kt

package com.mochyy.mochymusic.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.mutableStateOf
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
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.LyricsMotionStyle
import com.mochyy.mochymusic.constants.LyricsMotionStyleKey
import com.mochyy.mochymusic.lyrics.WordTimestamp
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.player.KaraokeSweepText
import com.mochyy.mochymusic.ui.player.SmartLyricPalette
import com.mochyy.mochymusic.ui.theme.extractThemeColor
import com.mochyy.mochymusic.utils.rememberEnumPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

// The badge in front of the queued track, and the one that marks a crossmix already running.
private val NextBadge = Color.White.copy(alpha = 0.10f)
private val NextInk = Color.White.copy(alpha = 0.52f)
private val MixBadge = Color(0xFFB6E3FF).copy(alpha = 0.18f)
private val MixInk = Color(0xFFB6E3FF)
private val ReadyRing = Color(0xFFB6E3FF)

// One size for every line: long and short lyrics read the same, and overflow scrolls inside
// the user's fixed capsule instead of shrinking only the long ones.
private const val LyricTextSp = 14f
private const val MetaTextSp = 10f
private const val NextTextSp = 9f

// The disc keeps room beside it for the outgoing one to slide into during a crossmix. The
// slot never changes size, so a blend costs no layout pass on the capsule.
private const val DiscSlotFactor = 1.30f

// How long the two discs take to dissolve into one, and one breath of the ready ring.
private const val MixBlendMs = 1500
private const val ReadyRingMs = 1600

// Where the capsule puts its disc from its own left edge. The disc-only view starts there, so
// the window narrowing around it reads as the capsule collapsing instead of the disc jumping.
private val DiscLeadDp = 2.dp + (StatusGeometry.CapsuleHeightDp.dp - 16.dp) * (DiscSlotFactor - 1f) / 2f

// Song above, the line being sung below, the queued track underneath. The window's width is
// the user's own setting and never changes because of a lyric.
@Composable
internal fun StatusLyrics(state: PillState, discOnly: Boolean) {
    // The ring around the cover is the only thing that needs the position every frame.
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
    if (discOnly) DiscOnly(state, progress) else Capsule(state, progress)
}

// Tapping the cover leaves just the cover: no capsule behind it, nothing else drawn.
@Composable
private fun DiscOnly(state: PillState, progress: MutableFloatState) {
    val restore = stringResource(R.string.floating_lyrics_show_capsule)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = restore },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The same inset the capsule leaves around its cover, so nothing moves on the swap.
        val cover = (minOf(maxWidth, maxHeight) - 16.dp).coerceAtLeast(24.dp)
        val lead = minOf(DiscLeadDp, ((maxWidth - cover) / 2).coerceAtLeast(0.dp))
        Box(Modifier.padding(start = lead)) { DiscStage(state, progress, cover) }
    }
}

@Composable
private fun Capsule(state: PillState, progress: MutableFloatState) {
    val script = state.script
    var rowIndex by remember(script) {
        mutableIntStateOf(script?.let { it.stops.getOrNull(it.stopAt(state.lyricsClock()))?.top } ?: 0)
    }
    var dots by remember(script) { mutableIntStateOf(-1) }

    // Wakes only when the next line or dot is due, so nothing runs between lines.
    LaunchedEffect(script, state.playing, state.seekTick) {
        val script = script ?: return@LaunchedEffect
        while (true) {
            val position = state.lyricsClock()
            val stop = script.stopAt(position)
            script.stops.getOrNull(stop)?.let { rowIndex = it.top }
            dots = script.dotsLit(stop, position)
            if (!state.playing) break
            val next = script.nextChangeMs(stop, position)
            if (next == Long.MAX_VALUE) break
            val wait = ((next - position) / state.speed()).toLong().coerceIn(16L, 500L)
            delay(wait)
        }
    }

    val row = script?.rows?.getOrNull(rowIndex)
    val lyrics = when {
        row == null -> if (state.lyricsLoading) "…" else ""
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
    val (motionStyle) = rememberEnumPreference(LyricsMotionStyleKey, LyricsMotionStyle.LEGACY)
    val context = LocalContext.current
    var coverAccent by remember { mutableStateOf(Color.White) }
    LaunchedEffect(state.song?.thumbnailUrl) {
        val url = state.song?.thumbnailUrl
        if (url.isNullOrBlank()) {
            coverAccent = Color.White
            return@LaunchedEffect
        }
        coverAccent = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(url).size(96, 96).allowHardware(false).build()
                context.imageLoader.execute(req).image?.toBitmap()?.extractThemeColor() ?: Color.White
            }.getOrDefault(Color.White)
        }
    }

    val effectiveWords = remember(row, dots, motionStyle) {
        if (dots >= 0 || row == null || row.kind != PillRowKind.LEAD) {
            null
        } else if (!row.words.isNullOrEmpty()) {
            row.words
        } else if ((motionStyle.isNeon || motionStyle.isMask) && row.text.isNotBlank() && row.endMs > row.startMs) {
            SmartLyricPalette.smartSynthesizedWords(row.text, row.startMs, row.endMs)
        } else {
            null
        }
    }

    // Key by line identity rather than script instance so background updates don't flash or reset karaoke sweep.
    val rowKey = Triple(row?.line ?: -1, row?.kind, row?.text.orEmpty())
    val appear = remember(rowKey) { Animatable(0f) }
    LaunchedEffect(appear) { appear.animateTo(1f, tween(durationMillis = 220)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(percent = 50))
            .background(CapsuleColor),
    ) {
        val cover = (maxHeight - 16.dp).coerceAtLeast(24.dp)
        val slot = cover * DiscSlotFactor
        val handleWidth = StatusGeometry.HandleWidthDp.dp
        Row(
            Modifier
                .fillMaxSize()
                .absolutePadding(right = handleWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(2.dp))
            Box(Modifier.width(slot), contentAlignment = Alignment.Center) {
                DiscStage(state, progress, cover)
            }
            Spacer(Modifier.width(4.dp))
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
                Spacer(Modifier.height(2.dp))
                key(rowKey) {
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
                            words = effectiveWords,
                            positionProvider = positionProvider,
                            playing = state.playing,
                            seekTick = state.seekTick,
                            motionStyle = if (motionStyle.isNeon || motionStyle.isMask) motionStyle else LyricsMotionStyle.LEGACY,
                            accentColor = coverAccent,
                            appear = { appear.value },
                        )
                    }
                }
                UpNext(state)
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

// The track queued after this one, or — while a crossmix runs — the one it is fading out.
@Composable
private fun UpNext(state: PillState) {
    val mixing = state.mixing && state.previousSong != null && state.previousSong?.id != state.song?.id
    val track = if (mixing) state.previousSong else state.nextSong
    if (track == null || track.id == state.song?.id) return
    val label = stringResource(if (mixing) R.string.floating_lyrics_mixing else R.string.floating_lyrics_up_next)
    Spacer(Modifier.height(2.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (mixing) MixBadge else NextBadge)
                .padding(horizontal = 5.dp, vertical = 0.5.dp),
        ) {
            BasicText(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (mixing) MixInk else NextInk,
                    fontSize = 7.5.sp,
                    lineHeight = TextUnit.Unspecified,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(4.dp))
        BasicText(
            text = track.title,
            style = MaterialTheme.typography.bodySmall.copy(
                color = Color.White.copy(alpha = if (mixing) 0.86f else 0.50f),
                fontSize = NextTextSp.sp,
                lineHeight = TextUnit.Unspecified,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// The cover with its progress ring. During a crossmix the outgoing disc sits on top of it and
// slides away as the two dissolve, then a breathing ring says the new one is ready to play.
@Composable
private fun DiscStage(state: PillState, progress: MutableFloatState, cover: Dp) {
    val blend = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val ringLevel = remember { Animatable(0f) }

    // The service promotes the new song before it reports the mix, so waiting for both means
    // the disc being faded out is always the one that was actually on screen.
    val previous = state.previousSong
    val mixingNow = state.mixing && previous != null && previous.id != state.song?.id

    var ghost by remember { mutableStateOf<MediaMetadata?>(null) }
    LaunchedEffect(mixingNow) {
        if (mixingNow) {
            ghost = previous
            blend.snapTo(0f)
            ringLevel.snapTo(0f)
            blend.animateTo(1f, tween(MixBlendMs, easing = FastOutSlowInEasing))
            // The mix is still running, so the disc that took over is only queued to play.
            ringLevel.animateTo(1f, tween(320, easing = LinearEasing))
            while (true) {
                ring.snapTo(0f)
                ring.animateTo(1f, tween(ReadyRingMs, easing = LinearEasing))
            }
        } else {
            // A mix cut short still lets the outgoing disc finish leaving.
            if (ghost != null) {
                if (blend.value < 1f) blend.animateTo(1f, tween(300, easing = LinearEasing))
                ghost = null
            }
            ringLevel.animateTo(0f, tween(420, easing = LinearEasing))
            ring.snapTo(0f)
        }
    }

    Box(Modifier.size(cover), contentAlignment = Alignment.Center) {
        CoverDisc(url = state.song?.thumbnailUrl, cover = cover, progress = progress)
        val leaving = ghost
        if (leaving != null) {
            CoverDisc(
                url = leaving.thumbnailUrl,
                cover = cover,
                progress = null,
                modifier = Modifier.graphicsLayer {
                    val fraction = blend.value
                    val shrink = 1f - 0.26f * fraction
                    scaleX = shrink
                    scaleY = shrink
                    translationX = -cover.toPx() * 0.24f * fraction
                    val fade = 1f - fraction
                    alpha = fade * fade
                },
            )
        }
        Box(
            Modifier
                .size(cover)
                .drawBehind {
                    val level = ringLevel.value
                    if (level <= 0.01f) return@drawBehind
                    val breath = ring.value
                    val stroke = 2.dp.toPx()
                    drawCircle(
                        color = ReadyRing.copy(alpha = level * (1f - breath) * 0.9f),
                        radius = size.minDimension / 2f - stroke / 2f + 6.dp.toPx() * breath,
                        style = Stroke(stroke),
                    )
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
    motionStyle: LyricsMotionStyle,
    accentColor: Color,
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
                accentColor = accentColor,
                fontSize = fontSize,
                lineHeight = fontSize,
                textAlign = TextAlign.Center,
                motionStyle = motionStyle,
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

// The song's cover in a circle, with how far along it is drawn around it. The arc is skipped
// when there is no progress to show, which is what the disc being faded out asks for.
@Composable
private fun CoverDisc(
    url: String?,
    cover: Dp,
    progress: MutableFloatState?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coverPx = with(LocalDensity.current) { cover.roundToPx() }
    val request = remember(url, coverPx) { ImageRequest.Builder(context).data(url).size(coverPx).build() }
    Box(
        modifier
            .size(cover)
            .drawBehind {
                val stroke = RingStroke.toPx()
                val radius = size.minDimension / 2f - stroke / 2f
                drawCircle(color = RingTrack, radius = radius, style = Stroke(stroke))
                val fraction = progress?.floatValue ?: 0f
                if (fraction <= 0f) return@drawBehind
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
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
