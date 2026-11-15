package com.mochyy.mochymusic.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.mochyy.mochymusic.R

internal interface StatusPlaybackHost {
    fun command(command: Int)
}

@Composable
internal fun StatusPanel(state: PillState, window: StatusWindow, onContentWidth: (Float) -> Unit, host: StatusPlaybackHost) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(19.dp)).background(Color(0xF2000000))) {
        val action = stringResource(if (window.expanded) R.string.floating_lyrics_collapse else R.string.floating_lyrics_expand)
        Box(Modifier.fillMaxWidth().height(38.dp).semantics {
            onClick(label = action) { window.changeExpanded(!window.expanded); true }
        }) {
            StatusLyrics(state, if (window.expanded) minOf(280f, window.fittingWidthDp) else window.fittingWidthDp, onContentWidth)
        }
        if (window.expanded) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val buttons: @Composable (Boolean) -> Unit = { transport ->
                    if (transport) {
                        PlaybackButton(R.drawable.skip_previous, R.string.previous, state.canPrevious) { host.command(Player.COMMAND_SEEK_TO_PREVIOUS) }
                        PlaybackButton(if (state.playWhenReady) R.drawable.pause else R.drawable.play, if (state.playWhenReady) R.string.pause else R.string.play, state.canPlay) { host.command(Player.COMMAND_PLAY_PAUSE) }
                        PlaybackButton(R.drawable.skip_next, R.string.next, state.canNext) { host.command(Player.COMMAND_SEEK_TO_NEXT) }
                    } else {
                        val repeatLabel = when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                            Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                            else -> R.string.repeat_mode_off
                        }
                        PlaybackButton(if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat, repeatLabel, state.canRepeat, state.repeatMode != Player.REPEAT_MODE_OFF) { host.command(Player.COMMAND_SET_REPEAT_MODE) }
                        PlaybackButton(R.drawable.shuffle, if (state.shuffle) R.string.action_shuffle_on else R.string.action_shuffle_off, state.canShuffle, state.shuffle) { host.command(Player.COMMAND_SET_SHUFFLE_MODE) }
                    }
                }
                if (maxWidth < 240.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { buttons(true) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { buttons(false) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        buttons(true)
                        buttons(false)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackButton(icon: Int, description: Int, enabled: Boolean, active: Boolean? = null, onClick: () -> Unit) {
    val label = stringResource(description)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics {
        if (active != null) {
            selected = active
            stateDescription = label
        }
    }) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp),
            tint = when { !enabled -> Color.White.copy(alpha = 0.3f); active == true -> Color(0xFFB6E3FF); else -> Color.White })
    }
}
