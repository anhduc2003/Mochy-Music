package com.mochyy.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import com.mochyy.desktop.audio.DesktopPlayer
import com.mochyy.desktop.model.Track
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

    /** the queue as the user sees it; the player keeps its own copy for advancing */
    val queue = mutableStateListOf<Track>()

    private val _library = MutableStateFlow<List<Track>>(emptyList())
    val library: StateFlow<List<Track>> = _library.asStateFlow()

    private val _status = MutableStateFlow("Chưa có nhạc — hãy thêm thư mục hoặc tệp")
    val status: StateFlow<String> = _status.asStateFlow()

    var cover: ImageBitmap? = null

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
            if (queue.isEmpty()) queue.addAll(fresh)
            player.setQueue(_library.value)
            _status.value = "Đã thêm ${fresh.size} bài" + if (read.size > fresh.size) " (${read.size - fresh.size} bài đã có)" else ""
        }
    }

    fun playFrom(index: Int) {
        val tracks = _library.value
        val track = tracks.getOrNull(index) ?: return
        queue.clear()
        queue.addAll(tracks)
        player.setQueue(tracks, index)
        player.play(track)
    }

    fun clear() {
        player.stop()
        queue.clear()
        _library.value = emptyList()
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
