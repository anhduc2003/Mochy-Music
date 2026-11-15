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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.desktop.AppState
import com.mochyy.desktop.LyricLine
import com.mochyy.desktop.model.Track
import java.io.File
import javax.swing.JFileChooser

/**
 * The window: a library list on the left of the content area and the player bar across the bottom.
 *
 * Kept to plain Material 3 on purpose. The Android build's player is 128k lines of custom drawing
 * — turntables, morphing covers, glow lyrics — and none of it is a prerequisite for a usable PC
 * build. This is the floor to build on, not a port of that artwork.
 */
@Composable
fun MochyWindow(state: AppState) {
    val library by state.library.collectAsState()
    val status by state.status.collectAsState()
    val playerState by state.player.state.collectAsState()
    val lyrics by state.lyrics.collectAsState()

    var selectedIndex by remember { mutableStateOf(-1) }
    var tab by remember { mutableStateOf(0) }

    Surface(color = Color(0xFF121216), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(state, library.size, status, tab) { tab = it }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (tab == 1) {
                        SearchPane(
                            onPlay = { videoId, title, artist, durationMs, thumb ->
                                state.playOnline(videoId, title, artist, durationMs, thumb)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (library.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                            itemsIndexed(library, key = { _, t -> t.id }) { index, track ->
                                TrackRow(
                                    track = track,
                                    index = index,
                                    isCurrent = playerState.track?.id == track.id,
                                    isPlaying = playerState.playing && playerState.track?.id == track.id,
                                    onClick = {
                                        selectedIndex = index
                                        state.playFrom(index)
                                    },
                                )
                            }
                        }
                    }
                }
                LyricsPane(
                    lyrics = lyrics,
                    positionMs = playerState.positionMs,
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )
            }

            PlayerBar(state, selectedIndex)
        }
    }
}

/** the line being sung, lit, with the ones around it for context */
@Composable
private fun LyricsPane(lyrics: List<LyricLine>, positionMs: Long, modifier: Modifier = Modifier) {
    val active = LyricLine.indexAt(lyrics, positionMs)
    Column(
        modifier
            .background(Color(0xFF16161C))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text("Lời bài hát", color = Color(0xFF6A6A76), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        when {
            lyrics.isEmpty() -> Text(
                "Không có lời trong tệp",
                color = Color(0xFF4E4E58),
                fontSize = 12.sp,
            )
            else -> LazyColumn {
                itemsIndexed(lyrics) { index, line ->
                    val isActive = index == active
                    Text(
                        line.text,
                        color = if (isActive) Color(0xFFB4BEFE) else Color(0xFF7A7A88),
                        fontSize = if (isActive) 15.sp else 13.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: AppState, count: Int, status: String, tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Mochy",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(10.dp))
            Text("PC", color = Color(0xFF7A7A85), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(26.dp))
            TextButton(onClick = { onTab(0) }) {
                Text("Thư viện", color = if (tab == 0) Color(0xFFB4BEFE) else Color(0xFF8A8A95), fontSize = 13.sp)
            }
            TextButton(onClick = { onTab(1) }) {
                Text("Tìm online", color = if (tab == 1) Color(0xFFB4BEFE) else Color(0xFF8A8A95), fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { pickFolder()?.let(state::addPath) }) { Text("Thêm thư mục") }
            TextButton(onClick = { pickFiles()?.forEach(state::addPath) }) { Text("Thêm tệp") }
            if (count > 0) {
                TextButton(onClick = state::clear) { Text("Xoá", color = Color(0xFF9A9AA5)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(status, color = Color(0xFF8A8A95), fontSize = 12.sp)
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Chưa có nhạc", color = Color(0xFF6A6A75), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Bấm “Thêm thư mục” để chọn nơi chứa nhạc của bạn",
                color = Color(0xFF55555F),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isCurrent) Color(0xFF23232B) else Color.Transparent
    val titleColor = if (isCurrent) Color(0xFFB4BEFE) else Color(0xFFEDEDF2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            "%02d".format(index + 1),
            color = Color(0xFF5A5A66),
            fontSize = 12.sp,
            modifier = Modifier.width(30.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = titleColor,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.album).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, color = Color(0xFF7A7A88), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatTime(track.durationMs), color = Color(0xFF60606C), fontSize = 12.sp)
    }
}

@Composable
private fun PlayerBar(state: AppState, selectedIndex: Int) {
    val s by state.player.state.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(Color(0xFF181820))
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.track?.title ?: "Chưa phát bài nào",
                    color = if (s.track == null) Color(0xFF6A6A75) else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    s.track?.artist?.takeIf { it.isNotBlank() } ?: "",
                    color = Color(0xFF7A7A88),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
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
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(s.positionMs), color = Color(0xFF6A6A76), fontSize = 11.sp, modifier = Modifier.width(44.dp))
            Slider(
                value = s.progress,
                onValueChange = { fraction ->
                    if (s.durationMs > 0) state.player.seekTo((fraction * s.durationMs).toLong())
                },
                modifier = Modifier.weight(1f),
            )
            Text(formatTime(s.durationMs), color = Color(0xFF6A6A76), fontSize = 11.sp, modifier = Modifier.width(44.dp))
        }

        s.error?.let {
            Text(it, color = Color(0xFFF38BA8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else null
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
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFiles?.map { it.absolutePath }
    } else null
}
