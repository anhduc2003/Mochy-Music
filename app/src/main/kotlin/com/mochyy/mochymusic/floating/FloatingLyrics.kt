// FloatingLyrics.kt

package com.mochyy.mochymusic.floating

import android.content.Context
import androidx.media3.common.Player
import com.mochyy.mochymusic.car.CarLyricsPlayer
import com.mochyy.mochymusic.car.CarLyricsSource
import com.mochyy.mochymusic.constants.FloatingLyricsKey
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// the line being sung, in the player card itself rather than in a window of its own: while it's on, the notification, the lock screen and
// anything else reading the session put the line where the artist goes, so there's still one player instead of a second one floating about
class FloatingLyrics(
    private val context: Context,
    private val source: CarLyricsSource,
    private val song: StateFlow<MediaMetadata?>,
    private val player: StateFlow<Player?>,
    private val carInCharge: StateFlow<Boolean>,
    private var card: CarLyricsPlayer,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var shown: CarLyricsPlayer.Overlay? = null

    // call on the main thread, the session's player is read from there
    fun start() {
        if (job != null) return
        job = scope.launch {
            val wanted = context.dataStore.data.map { it[FloatingLyricsKey] ?: false }.distinctUntilChanged()
            // a car showing its own line keeps the card, so the two never trade it back and forth
            combine(wanted, carInCharge) { on, car -> on && !car }
                .distinctUntilChanged()
                .collectLatest { on -> if (on) follow() else show(null) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        show(null)
    }

    // a crossfade hands the session a new player, and the line on the card moves over to it instead of vanishing until the next one
    fun attach(next: CarLyricsPlayer) {
        card = next
        next.show(shown)
    }

    private fun show(next: CarLyricsPlayer.Overlay?) {
        shown = next
        card.show(next)
    }

    // follows each song for as long as the lyrics are wanted, and hands the artist back the moment they aren't
    private suspend fun follow() {
        try {
            song.distinctUntilChangedBy { it?.id }.collectLatest { metadata ->
                show(null)
                if (metadata != null) sing(metadata)
            }
        } finally {
            show(null)
        }
    }

    // only a new line goes out, a plain text change the card shows at once without any picture going over
    private suspend fun sing(metadata: MediaMetadata) {
        val lyrics = source.load(metadata)?.takeIf { it.synced } ?: return
        var shownLine = Int.MIN_VALUE
        while (true) {
            // a player that isn't there yet is waited for rather than given up on, the song is still the one that's loaded
            val current = player.value
            if (current == null) {
                delay(PausedTickMs)
                continue
            }
            val position = current.currentPosition
            val index = lyrics.lineAt(position)
            // long instrumental stretches hand the artist back until singing starts again
            val line = lyrics.lines.getOrNull(index)?.takeIf {
                it.text.isNotBlank() && current.currentMediaItem?.mediaId == metadata.id && !lyrics.isBreak(index, position)
            }
            if (line == null) {
                if (shownLine != Int.MIN_VALUE) show(null)
                shownLine = Int.MIN_VALUE
            } else if (index != shownLine) {
                // the song keeps its own title here, only the artist line gives way
                show(CarLyricsPlayer.Overlay(metadata.id, line.text.trim()))
                shownLine = index
            }
            delay(if (current.isPlaying) PlayingTickMs else PausedTickMs)
        }
    }

    private companion object {
        const val PlayingTickMs = 90L
        const val PausedTickMs = 500L
    }
}
