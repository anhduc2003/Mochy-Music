package com.mochyy.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.desktop.AppState
import com.mochyy.desktop.LyricLine
import com.mochyy.desktop.model.RepeatMode
import com.mochyy.desktop.model.Track
import java.io.File
import javax.swing.JFileChooser

/**
 * The window.
 *
 * Layout: the song's own colour as a backdrop, a now-playing block with the artwork, the library
 * or the online search on the left, lyrics on the right, and the transport along the bottom.
 */
@Composable
fun MochyWindow(state: AppState) {
    val library by state.library.collectAsState()
    val status by state.status.collectAsState()
    val playerState by state.player.state.collectAsState()
    val lyrics by state.lyrics.collectAsState()

    var selectedIndex by remember { mutableStateOf(-1) }
    var tab by remember { mutableStateOf(0) }
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }

    // the artwork changes with the song, so the backdrop and the card follow it
    LaunchedEffect(playerState.track?.id) {
        val track = playerState.track
        cover = if (track == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CoverArt.load(track)
        }
    }

    Box(Modifier.fillMaxSize()) {
        SongBackdrop(playerState.track, cover, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Header(state, library.size, status, tab) { tab = it }
            NowPlayingHeader(playerState.track, cover)

            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        tab == 1 -> SearchPane(
                            onPlay = { videoId, title, artist, durationMs, thumb ->
                                state.playOnline(videoId, title, artist, durationMs, thumb)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        library.isEmpty() -> EmptyState()
                        else -> LibraryList(library, playerState.track?.id, playerState.playing) { index ->
                            selectedIndex = index
                            state.playFrom(index)
                        }
                    }
                }
                LyricsPane(
                    lyrics = lyrics,
                    positionMs = playerState.positionMs,
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                )
            }

            PlayerBar(state, playerState)
        }
    }
}

@Composable
private fun LibraryList(
    library: List<Track>,
    currentId: String?,
    playing: Boolean,
    onPick: (Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        itemsIndexed(library, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                index = index,
                isCurrent = currentId == track.id,
                isPlaying = playing && currentId == track.id,
                onClick = { onPick(index) },
            )
        }
    }
}

@Composable
private fun Header(state: AppState, count: Int, status: String, tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mochy", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Text("PC", color = Color(0xFF8A8A98), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(22.dp))
            TextButton(onClick = { onTab(0) }) {
                Text("Thư viện", color = if (tab == 0) Color(0xFFB4BEFE) else Color(0xFF8A8A95), fontSize = 13.sp)
            }
            TextButton(onClick = { onTab(1) }) {
                Text("Tìm online", color = if (tab == 1) Color(0xFFB4BEFE) else Color(0xFF8A8A95), fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { pickFolder()?.let(state::addPath) }) { Text("Thêm thư mục", fontSize = 13.sp) }
            TextButton(onClick = { pickFiles()?.forEach(state::addPath) }) { Text("Thêm tệp", fontSize = 13.sp) }
            if (count > 0) TextButton(onClick = state::clear) { Text("Xoá", color = Color(0xFF9A9AA5), fontSize = 13.sp) }
        }
        Spacer(Modifier.height(2.dp))
        Text(status, color = Color(0xFF9A9AA8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Chưa có nhạc", color = Color(0xFF6A6A78), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Bấm “Thêm thư mục” để chọn nơi chứa nhạc", color = Color(0xFF55555F), fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrackRow(track: Track, index: Int, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    val bg = if (isCurrent) Color(0x33FFFFFF) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            if (isCurrent && isPlaying) "▶" else "%02d".format(index + 1),
            color = if (isCurrent) Color(0xFFB4BEFE) else Color(0xFF5A5A68),
            fontSize = 11.sp,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isCurrent) Color(0xFFB4BEFE) else Color(0xFFEDEDF2),
                fontSize = 13.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.album).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, color = Color(0xFF8A8A98), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatTime(track.durationMs), color = Color(0xFF6A6A76), fontSize = 11.sp)
    }
}

@Composable
private fun LyricsPane(lyrics: List<LyricLine>, positionMs: Long, modifier: Modifier = Modifier) {
    val active = LyricLine.indexAt(lyrics, positionMs)
    Column(modifier.background(Color(0x33101018)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("LỜI BÀI HÁT", color = Color(0xFF6A6A78), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (lyrics.isEmpty()) {
            Text("Không có lời trong tệp", color = Color(0xFF4E4E58), fontSize = 12.sp)
        } else {
            LazyColumn {
                itemsIndexed(lyrics) { index, line ->
                    val isActive = index == active
                    Text(
                        line.text,
                        color = if (isActive) Color(0xFFB4BEFE) else Color(0xFF7A7A8A),
                        fontSize = if (isActive) 15.sp else 12.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerBar(state: AppState, s: com.mochyy.desktop.model.PlayerState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xCC14141C))
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.player.setShuffle(!s.shuffle) }) {
                Icon(
                    Icons.Default.Shuffle,
                    "Ngẫu nhiên",
                    tint = if (s.shuffle) Color(0xFFB4BEFE) else Color(0xFF7A7A88),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { state.player.playPrevious() }) {
                Icon(Icons.Default.SkipPrevious, "Bài trước", tint = Color(0xFFD0D0DA))
            }
            IconButton(onClick = { state.player.toggle() }, modifier = Modifier.size(46.dp)) {
                Icon(
                    if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (s.playing) "Tạm dừng" else "Phát",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = { state.player.playNext() }) {
                Icon(Icons.Default.SkipNext, "Bài sau", tint = Color(0xFFD0D0DA))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                state.player.setRepeat(
                    when (s.repeat) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    },
                )
            }) {
                Icon(
                    if (s.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Lặp lại",
                    tint = if (s.repeat == RepeatMode.OFF) Color(0xFF7A7A88) else Color(0xFFB4BEFE),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(s.positionMs), color = Color(0xFF7A7A88), fontSize = 10.sp, modifier = Modifier.width(42.dp))
            Slider(
                value = s.progress,
                onValueChange = { if (s.durationMs > 0) state.player.seekTo((it * s.durationMs).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFB4BEFE),
                    activeTrackColor = Color(0xFFB4BEFE),
                    inactiveTrackColor = Color(0x33FFFFFF),
                ),
                modifier = Modifier.weight(1f),
            )
            Text(formatTime(s.durationMs), color = Color(0xFF7A7A88), fontSize = 10.sp, modifier = Modifier.width(42.dp))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.VolumeUp, "Âm lượng", tint = Color(0xFF7A7A88), modifier = Modifier.size(16.dp))
            Slider(
                value = s.volume,
                onValueChange = { state.player.setVolume(it) },
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF9A9AA8),
                    activeTrackColor = Color(0xFF9A9AA8),
                    inactiveTrackColor = Color(0x33FFFFFF),
                ),
                modifier = Modifier.width(90.dp),
            )
        }

        s.error?.let {
            Text(it, color = Color(0xFFF38BA8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun pickFolder(): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Chọn thư mục nhạc"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}

private fun pickFiles(): List<String>? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = true
        dialogTitle = "Chọn tệp nhạc"
        fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: File) = f.isDirectory || f.extension.lowercase() in AppState.SupportedExtensions
            override fun getDescription() = "Tệp âm thanh"
        }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles?.map { it.absolutePath } else null
}
