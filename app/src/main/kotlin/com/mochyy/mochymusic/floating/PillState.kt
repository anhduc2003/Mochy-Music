// PillState.kt

package com.mochyy.mochymusic.floating

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.player.PlaybackClock

// what the status bar capsule shows, written by the service on the main thread and read by the capsule's compose
@Stable
internal class PillState(private val player: () -> Player?) {
    var song by mutableStateOf<MediaMetadata?>(null)

    // What was playing until a moment ago. A crossmix promotes the incoming song straight away,
    // so this is the only way the capsule still knows which disc to fade out.
    var previousSong by mutableStateOf<MediaMetadata?>(null)

    // The queued track after this one, and whether crossmix is blending the two right now.
    var nextSong by mutableStateOf<MediaMetadata?>(null)
    var mixing by mutableStateOf(false)

    // Tapping the cover collapses the capsule down to the disc alone.
    var discOnly by mutableStateOf(false)

    // null while the song's lyrics are still being looked up, the capsule shows its title meanwhile
    var script by mutableStateOf<PillScript?>(null)

    var playing by mutableStateOf(false)
    var playWhenReady by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var shuffle by mutableStateOf(false)
    var canPlay by mutableStateOf(false)
    var canPrevious by mutableStateOf(false)
    var canNext by mutableStateOf(false)
    var canRepeat by mutableStateOf(false)
    var canShuffle by mutableStateOf(false)

    fun updatePlayback(current: Player?) {
        playing = current?.isPlaying == true
        playWhenReady = current?.playWhenReady == true
        repeatMode = current?.repeatMode ?: Player.REPEAT_MODE_OFF
        shuffle = current?.shuffleModeEnabled == true
        canPlay = current?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true
        canPrevious = current?.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS) == true
        canNext = current?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) == true
        canRepeat = current?.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) == true
        canShuffle = current?.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE) == true
    }

    // bumped on a seek or a speed change, so everything timed works out again where the song is
    var seekTick by mutableIntStateOf(0)

    // the song's own lyrics shift, read every frame by the clock so it isn't state
    var offsetMs = 0

    // where the lyrics are for the frame on screen, with the shift
    val lyricsClock = PlaybackClock(player, { offsetMs.toLong() })

    // where the song itself is, for the ring
    fun songPositionMs(): Long = player()?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun durationMs(): Long {
        val known = player()?.duration ?: C.TIME_UNSET
        if (known != C.TIME_UNSET && known > 0L) return known
        return (song?.duration ?: 0) * 1000L
    }

    fun speed(): Float = player()?.playbackParameters?.speed?.takeIf { it > 0f } ?: 1f
}
