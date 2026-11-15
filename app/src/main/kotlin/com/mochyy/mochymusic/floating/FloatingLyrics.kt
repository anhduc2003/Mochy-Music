// FloatingLyrics.kt

package com.mochyy.mochymusic.floating

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.mochyy.mochymusic.car.CarLyricsSource
import com.mochyy.mochymusic.constants.FloatingLyricsKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the line being sung in the status bar's own row: while it's turned on and allowed, a black capsule sits where the phone puts its island and
// shows the line with the song's progress ring, so it reads as part of the system bar instead of a window floating over everything
class FloatingLyrics(
    private val context: Context,
    private val database: MusicDatabase,
    private val source: CarLyricsSource,
    private val song: StateFlow<MediaMetadata?>,
    private val player: StateFlow<Player?>,
    private val scope: CoroutineScope,
) {
    private val appVisible = MutableStateFlow(true)
    private val state = PillState { player.value }
    private var job: Job? = null
    private var window: StatusWindow? = null
    private var follow: Job? = null

    private val visibility = LifecycleEventObserver { _, _ ->
        appVisible.value = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    // call on the main thread, the app's lifecycle is watched from there
    fun start() {
        if (job != null) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibility)
        job = scope.launch {
            val wanted = context.dataStore.data.map { it[FloatingLyricsKey] ?: false }.distinctUntilChanged()
            val hasSong = song.map { it != null }.distinctUntilChanged()
            combine(wanted, appVisible, hasSong) { on, visible, playing -> on && !visible && playing }
                .distinctUntilChanged()
                .collect { show -> if (show && Settings.canDrawOverlays(context)) open() else close() }
        }
    }

    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(visibility)
        job?.cancel()
        job = null
        close()
    }

    private suspend fun open() {
        if (window != null) return
        val bar = StatusWindow(context)
        if (!bar.show { StatusLyrics(state, bar.metrics) }) return
        window = bar
        follow = scope.launch { followSong() }
    }

    private fun close() {
        follow?.cancel()
        follow = null
        window?.remove()
        window = null
    }

    // keeps the capsule's song, lyrics and play state current for as long as it's on screen; nothing runs while it's hidden
    private suspend fun followSong() = coroutineScope {
        launch { followPlayer() }
        song.distinctUntilChangedBy { it?.id }.collectLatest { metadata ->
            state.song = metadata
            state.script = null
            if (metadata == null) return@collectLatest
            val artist = metadata.artists.joinToString { it.name }
            val appRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val offset = database.song(metadata.id).map { it?.song?.lyricsOffset ?: 0 }
            // follows the saved lyrics, so a fix or a new source in the app reaches the capsule straight away
            combine(database.lyrics(metadata.id), offset) { lyrics, shift -> lyrics?.lyrics to shift }
                .distinctUntilChanged()
                .collectLatest { (raw, shift) ->
                    state.offsetMs = shift
                    if (raw == null) {
                        // nothing saved yet: fetched once and saved, and the saved copy comes back through here
                        source.fetch(metadata)
                        return@collectLatest
                    }
                    state.script = withContext(Dispatchers.Default) { PillScript.build(raw, metadata.title, artist, appRtl) }
                }
        }
    }

    // a crossfade hands over to a new player, so the listener moves with it
    private suspend fun followPlayer() {
        player.collectLatest { current ->
            if (current == null) return@collectLatest
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state.playing = isPlaying
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    state.seekTick++
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    state.seekTick++
                }
            }
            state.playing = current.isPlaying
            state.seekTick++
            current.addListener(listener)
            try {
                awaitCancellation()
            } finally {
                current.removeListener(listener)
            }
        }
    }
}
