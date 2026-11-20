// FloatingLyrics.kt

package com.mochyy.mochymusic.floating

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.mochyy.mochymusic.car.CarLyricsSource
import com.mochyy.mochymusic.extensions.metadata
import com.mochyy.mochymusic.constants.FloatingLyricsKey
import com.mochyy.mochymusic.constants.FloatingLyricsAnchorKey
import com.mochyy.mochymusic.constants.FloatingLyricsVerticalFractionKey
import com.mochyy.mochymusic.constants.FloatingLyricsWidthKey
import com.mochyy.mochymusic.constants.FloatingLyricsXKey
import com.mochyy.mochymusic.constants.FloatingLyricsYKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.LyricsEntity
import com.mochyy.mochymusic.lyrics.LyricsTranslationHelper
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the line being sung in the status bar's own row: while it's turned on and allowed, a black capsule sits where the phone puts its island and
// shows the line with the song's progress ring, so it reads as part of the system bar instead of a window floating over everything
/**
 * How long [FloatingLyrics] waits for the "draw over other apps" grant before giving up for this
 * song. Long enough to walk into Settings and flip it; short enough never to become a timer that
 * runs for the whole song because the user declined.
 */
private const val OverlayWaitMs = 30_000L

class FloatingLyrics(
    private val context: Context,
    private val database: MusicDatabase,
    private val source: CarLyricsSource,
    private val song: StateFlow<MediaMetadata?>,
    private val player: StateFlow<Player?>,
    private val mixing: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val appVisible = MutableStateFlow(true)
    private val state = PillState { player.value }
    private var job: Job? = null
    private var openJob: Job? = null
    private var window: StatusWindow? = null
    private var follow: Job? = null

    private val visibility = LifecycleEventObserver { _, _ ->
        appVisible.value = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    // where it was left and how wide it was pinned, so it comes back the same way next time
    private val windowListener = object : StatusWindowListener {
        override fun onMoved(x: Int, y: Int, anchor: StatusGeometry.Anchor, verticalFraction: Int) {
            scope.launch {
                context.dataStore.edit {
                    it[FloatingLyricsXKey] = x
                    it[FloatingLyricsYKey] = y
                    it[FloatingLyricsAnchorKey] = anchor.name
                    it[FloatingLyricsVerticalFractionKey] = verticalFraction
                }
            }
        }

        override fun onResized(widthDp: Int) {
            scope.launch {
                context.dataStore.edit { it[FloatingLyricsWidthKey] = widthDp }
            }
        }
    }

    // call on the main thread, the app's lifecycle is watched from there
    fun start() {
        if (job != null) return
        appVisible.value = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibility)
        job = scope.launch {
            val wanted = context.dataStore.data.map { it[FloatingLyricsKey] ?: false }.distinctUntilChanged()
            val hasSong = song.map { it != null }.distinctUntilChanged()
            combine(wanted, appVisible, hasSong) { on, visible, playing -> on && !visible && playing }
                .distinctUntilChanged()
                .collectLatest { show ->
                    if (!show) {
                        close()
                        return@collectLatest
                    }
                    // Wait for the overlay grant, but only for a bounded while. Polling every second
                    // for as long as a song played kept a timer alive forever for anyone who had no
                    // intention of granting it; and once the user was in Settings the screen was gone,
                    // so nothing changed. A fresh foreground or a new song starts this over.
                    var waited = 0L
                    while (!Settings.canDrawOverlays(context) && waited < OverlayWaitMs) {
                        val step = if (waited < 5_000L) 500L else 2_000L
                        delay(step)
                        waited += step
                    }
                    if (Settings.canDrawOverlays(context)) open()
                }
        }
    }

    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(visibility)
        job?.cancel()
        job = null
        close()
    }

    private fun open() {
        if (window != null || openJob?.isActive == true) return
        openJob = scope.launch {
            val prefs = context.dataStore.data.first()
            if (!isActive || window != null) return@launch
            val bar = StatusWindow(
                context,
                prefs[FloatingLyricsXKey],
                prefs[FloatingLyricsYKey],
                prefs[FloatingLyricsWidthKey],
                windowListener,
                prefs[FloatingLyricsAnchorKey],
                prefs[FloatingLyricsVerticalFractionKey],
                onDiscTap = { state.discOnly = !state.discOnly },
            )
            val host = object : StatusPlaybackHost {
                override fun command(command: Int) {
                    val current = player.value ?: return
                    if (!current.isCommandAvailable(command)) return
                    when (command) {
                        Player.COMMAND_PLAY_PAUSE -> if (current.playWhenReady) current.pause() else {
                            if (current.playbackState == Player.STATE_IDLE && current.isCommandAvailable(Player.COMMAND_PREPARE)) current.prepare()
                            if (current.playbackState == Player.STATE_ENDED && current.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) current.seekToDefaultPosition()
                            current.play()
                        }
                        Player.COMMAND_SEEK_TO_PREVIOUS -> current.seekToPrevious()
                        Player.COMMAND_SEEK_TO_NEXT -> current.seekToNext()
                        Player.COMMAND_SET_REPEAT_MODE -> current.repeatMode = when (current.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        Player.COMMAND_SET_SHUFFLE_MODE -> current.shuffleModeEnabled = !current.shuffleModeEnabled
                    }
                }
            }
            if (!bar.show { StatusPanel(state, bar, host) }) return@launch
            if (!isActive) {
                bar.remove()
                return@launch
            }
            window = bar
            follow = launch {
                launch {
                    combine(
                        context.dataStore.data.map { it[com.mochyy.mochymusic.constants.EnableHighRefreshRateKey] ?: true }.distinctUntilChanged(),
                        androidx.compose.runtime.snapshotFlow { state.playing },
                    ) { enabled, playing -> enabled && playing }
                        .distinctUntilChanged()
                        .collect { bar.setHighRefreshRate(it) }
                }
                followSong()
            }
        }
    }

    private fun close() {
        openJob?.cancel()
        openJob = null
        follow?.cancel()
        follow = null
        window?.remove()
        window = null
    }

    // keeps the capsule's song, lyrics and play state current for as long as it's on screen; nothing runs while it's hidden
    private suspend fun followSong() = coroutineScope {
        launch { followPlayer() }
        launch { mixing.collect { state.mixing = it } }
        song.distinctUntilChangedBy { it?.id }.collectLatest { metadata ->
            if (metadata?.id != state.song?.id) state.previousSong = state.song
            state.song = metadata
            state.script = null
            state.lyricsLoading = false
            state.lyricsClock.reset()
            state.seekTick++
            if (metadata != null) followLyrics(metadata)
        }
    }

    // Follow lyrics and reading settings only while this song's capsule is visible.
    private suspend fun followLyrics(metadata: MediaMetadata) = coroutineScope {
        val artist = metadata.artists.joinToString { it.name }
        val appRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val offset = database.song(metadata.id).map { it?.song?.lyricsOffset ?: 0 }
        // follows the saved lyrics, so a fix, a new source or a saved translation in the app reaches the pill straight away
        combine(
            database.lyrics(metadata.id),
            offset,
            readingSettings(context),
            LyricsTranslationHelper.translationOn,
        ) { saved, shift, settings, translate -> SongLyrics(saved, shift, settings, translate) }
            .distinctUntilChanged()
            .collectLatest { found ->
                if (state.offsetMs != found.shift) {
                    state.offsetMs = found.shift
                    state.seekTick++
                }
                val raw = found.saved?.lyrics
                if (raw == null) {
                    // Retry transient fetch failures so the capsule doesn't stay blank for the whole song.
                    state.lyricsLoading = true
                    try {
                        var attempt = 0
                        while (isActive && attempt < 3) {
                            val fetched = runCatching { source.fetch(metadata) }.getOrNull()
                            if (fetched != null) break
                            attempt++
                            if (attempt < 3) delay(attempt * 3_000L)
                        }
                    } finally {
                        state.lyricsLoading = false
                    }
                    if (state.script == null) {
                        state.script = PillScript.info(metadata.title, artist)
                    }
                    return@collectLatest
                }
                state.lyricsLoading = false
                showLyrics(raw, metadata.title, artist, appRtl, found)
            }
    }

    // Builds the pill script; only romanization as main line affects the single-line capsule, avoiding mid-song flashes.
    private suspend fun showLyrics(raw: String, title: String, artist: String, appRtl: Boolean, found: SongLyrics) {
        val lines = withContext(Dispatchers.Default) { PillScript.lines(raw) }
        val plain = withContext(Dispatchers.Default) { PillScript.build(lines, title, artist, appRtl) }
        if (!plain.sung || !found.settings.romanizedAsMain) {
            state.script = plain
            return
        }
        if (state.script == null) state.script = plain
        val romanized = romanizedLines(lines, found.settings) ?: return
        state.script = withContext(Dispatchers.Default) {
            PillScript.build(lines, title, artist, appRtl, PillReadings(romanized, emptyList(), romanizedAsMain = true))
        }
    }

    private fun nextMetadata(current: Player): MediaMetadata? {
        val index = if (current.repeatMode == Player.REPEAT_MODE_ONE) {
            current.currentMediaItemIndex
        } else {
            current.nextMediaItemIndex
        }
        if (index !in 0 until current.mediaItemCount) return null
        return runCatching { current.getMediaItemAt(index).metadata }.getOrNull()
    }

    // a crossfade hands over to a new player, so the listener moves with it
    private suspend fun followPlayer() {
        player.collectLatest { current ->
            state.lyricsClock.reset()
            state.updatePlayback(current)
            if (current == null) return@collectLatest
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    state.updatePlayback(player)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state.playing = isPlaying
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        state.seekTick++
                    }
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    state.seekTick++
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    state.nextSong = nextMetadata(current)
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    state.nextSong = nextMetadata(current)
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    state.seekTick++
                }
            }
            state.playing = current.isPlaying
            state.nextSong = nextMetadata(current)
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


// everything the capsule's lyrics are built from; a change to any of it lays them out again
private data class SongLyrics(
    val saved: LyricsEntity?,
    val shift: Int,
    val settings: ReadingSettings,
    val translate: Boolean,
)
