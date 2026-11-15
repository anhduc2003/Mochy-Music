package com.mochyy.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import com.mochyy.desktop.audio.DesktopPlayer
import com.mochyy.desktop.db.LibraryDb
import com.mochyy.desktop.lyrics.EmbeddedLyricsSource
import com.mochyy.desktop.model.Track
import com.mochyy.desktop.net.InnerTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything the window talks to, in one place.
 *
 * One object rather than the Hilt graph the Android build uses: there is no Android here to need
 * a graph, and the PC build has a single window with a single player, so a plain holder is both
 * simpler and easier to reason about.
 */
class AppState {

    val player = DesktopPlayer()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** the library on disk; a failure to open it is not fatal, the session just isn't saved */
    private val db: LibraryDb? = runCatching { LibraryDb() }.getOrNull()

    /** the queue as the user sees it; the player keeps its own copy for advancing */
    val queue = mutableStateListOf<Track>()

    private val _library = MutableStateFlow<List<Track>>(emptyList())
    val library: StateFlow<List<Track>> = _library.asStateFlow()

    private val _status = MutableStateFlow("Chưa có nhạc — hãy thêm thư mục hoặc tệp")
    val status: StateFlow<String> = _status.asStateFlow()

    /** the lyric lines of whatever is playing, and which one is lit */
    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    init {
        // whatever was saved last time, minus entries whose file has since gone
        scope.launch {
            val saved = runCatching { db?.pruneMissing(); db?.all() }.getOrNull().orEmpty()
            if (saved.isNotEmpty()) {
                _library.value = saved
                queue.addAll(saved)
                player.setQueue(saved)
                _status.value = "Đã khôi phục ${saved.size} bài"
            }
        }
    }

    var cover: ImageBitmap? = null

    /** loads lyrics for [track]: the file's own tags first, since that needs no network */
    fun loadLyrics(track: Track?) {
        if (track == null) {
            _lyrics.value = emptyList()
            return
        }
        scope.launch {
            val text = runCatching { EmbeddedLyricsSource().lyricsFor(track) }.getOrNull()
            _lyrics.value = LyricLine.parse(text)
        }
    }

    /** adds a file or a whole folder, reading what it can and saying what it skipped */
    fun addPath(path: String) {
        scope.launch {
            val target = File(path)
            if (!target.exists()) {
                _status.value = "Không tìm thấy: $path"
                return@launch
            }
            val files = if (target.isDirectory) {
                target.walkTopDown().filter { it.isFile && it.extension.lowercase() in SupportedExtensions }.toList()
            } else {
                listOf(target)
            }
            if (files.isEmpty()) {
                _status.value = "Không có tệp nhạc nào trong: ${target.name}"
                return@launch
            }
            val read = files.mapNotNull { TrackReader.read(it) }
            if (read.isEmpty()) {
                _status.value = "Không đọc được tệp nào (định dạng chưa hỗ trợ)"
                return@launch
            }
            val existing = _library.value.map { it.id }.toSet()
            val fresh = read.filter { it.id !in existing }
            _library.value = _library.value + fresh
            if (queue.isEmpty()) queue.addAll(_library.value)
            player.setQueue(_library.value)
            // saved before the status line, so a crash later doesn't lose what was just added
            val saved = runCatching { db?.addAll(fresh) }.getOrNull() ?: 0
            _status.value = buildString {
                append("Đã thêm ${fresh.size} bài")
                if (read.size > fresh.size) append(" (${read.size - fresh.size} bài đã có)")
                if (db == null) append(" — không lưu được vào đĩa")
                else if (saved > 0) append(" · đã lưu")
            }
        }
    }

    fun playFrom(index: Int) {
        val tracks = _library.value
        val track = tracks.getOrNull(index) ?: return
        queue.clear()
        queue.addAll(tracks)
        player.setQueue(tracks, index)
        player.play(track)
        loadLyrics(track)
    }

    /** resolves an online result to a stream URL and starts it, without adding it to the library */
    fun playOnline(videoId: String, title: String, artist: String, durationMs: Long, thumbnail: String?) {
        scope.launch {
            _status.value = "Đang lấy luồng phát cho “$title”…"
            val url = runCatching { InnerTube().streamUrl(videoId) }.getOrNull()
            if (url == null) {
                _status.value = "Không lấy được luồng phát cho “$title”"
                return@launch
            }
            val track = Track(
                id = "yt:$videoId",
                title = title,
                artist = artist,
                durationMs = durationMs,
                uri = url,
                thumbnailUrl = thumbnail,
            )
            queue.clear()
            queue.add(track)
            player.setQueue(listOf(track), 0)
            player.play(track)
            // an online track carries no embedded lyrics
            _lyrics.value = emptyList()
            _status.value = "Đang phát: $title"
        }
    }

    fun clear() {
        player.stop()
        queue.clear()
        _library.value = emptyList()
        _lyrics.value = emptyList()
        runCatching { db?.clear() }
        _status.value = "Đã xoá thư viện"
    }

    companion object {
        /**
         * What the JDK's mixer can decode on its own is WAV, AIFF and AU. Anything else needs a
         * provider registered with `javax.sound.sampled`; those files are still listed so the
         * reason a song won't play is visible, rather than the file quietly missing.
         */
        val SupportedExtensions = setOf("wav", "aiff", "aif", "au", "mp3", "flac", "ogg", "m4a", "opus")
        val DecodableExtensions = setOf("wav", "aiff", "aif", "au")
    }
}
