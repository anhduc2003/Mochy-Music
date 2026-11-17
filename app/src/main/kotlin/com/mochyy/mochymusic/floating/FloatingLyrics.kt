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
import com.mochyy.mochymusic.ui.player.LineReading
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
import kotlinx.coroutines.flow.first
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
    private val mixing: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val appVisible = MutableStateFlow(true)
    // Include the saved lyrics in the key so edits cannot reuse an older translation.
    private var translationMemo: Pair<TranslationKey, List<String?>>? = null
    private val state = PillState { player.value }
    private var job: Job? = null
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
        val prefs = context.dataStore.data.first()
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
        if (!bar.show { StatusPanel(state, bar, host) }) return
        window = bar
        follow = scope.launch {
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

    private fun close() {
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
                state.offsetMs = found.shift
                val raw = found.saved?.lyrics
                if (raw == null) {
                    // nothing saved yet: fetched once and saved, and the saved copy comes back through here
                    source.fetch(metadata)
                    return@collectLatest
                }
                showLyrics(metadata.id, raw, metadata.title, artist, appRtl, found)
            }
    }

    // the plain lines go up first; the romanisation and then the translation swap in as each is ready
    private suspend fun showLyrics(songId: String, raw: String, title: String, artist: String, appRtl: Boolean, found: SongLyrics) {
        val lines = withContext(Dispatchers.Default) { PillScript.lines(raw) }
        val plain = withContext(Dispatchers.Default) { PillScript.build(lines, title, artist, appRtl) }
        // after a song's first lines, its rows stay up until the new ones are ready, so a setting change doesn't shrink the bubble and grow it back
        if (state.script == null || !plain.sung) state.script = plain
        if (!plain.sung) return
        val settings = found.settings
        val withReadings: suspend (List<LineReading?>, List<String?>) -> PillScript = { romanized, translated ->
            withContext(Dispatchers.Default) {
                PillScript.build(lines, title, artist, appRtl, PillReadings(romanized, translated, settings.romanizedAsMain))
            }
        }
        val romanized = romanizedLines(lines, settings)
        val key = TranslationKey(songId, raw, found.saved, settings.language, settings.sourceLanguage, settings.mode)
        val remembered = translationMemo?.takeIf { found.translate && it.first == key }?.second
        if (remembered == null) state.script = if (romanized == null) plain else withReadings(romanized, emptyList())
        if (!found.translate) return
        val translated = remembered
            ?: translatedLines(lines, found.saved, settings)?.also { translationMemo = key to it }
            ?: return
        state.script = withReadings(romanized.orEmpty(), translated)
    }

    private fun nextMetadata(current: Player): MediaMetadata? {
        val index = if (current.repeatMode == Player.REPEAT_MODE_ONE) {
            current.currentMediaItemIndex
        } else {
            current.nextMediaItemIndex
        }
        if (index !in 0 until current.mediaItemCount) return null
        return current.getMediaItemAt(index).metadata
    }

    // a crossfade hands over to a new player, so the listener moves with it
    private suspend fun followPlayer() {
        player.collectLatest { current ->
            state.updatePlayback(current)
            if (current == null) return@collectLatest
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    state.updatePlayback(player)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state.playing = isPlaying
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


private data class TranslationKey(
    val songId: String,
    val raw: String,
    val saved: LyricsEntity?,
    val language: String,
    val sourceLanguage: String,
    val mode: String,
)

// everything the capsule's lyrics are built from; a change to any of it lays them out again
private data class SongLyrics(
    val saved: LyricsEntity?,
    val shift: Int,
    val settings: ReadingSettings,
    val translate: Boolean,
)
