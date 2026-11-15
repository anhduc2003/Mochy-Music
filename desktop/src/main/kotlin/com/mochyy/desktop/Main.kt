package com.mochyy.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mochyy.desktop.ui.MochyWindow

fun main() = application {
    val state = remember { AppState() }
    val windowState = rememberWindowState(
        size = DpSize(1080.dp, 720.dp),
        position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
    )

    // the track ends by itself: move on, the way the phone build's queue does
    LaunchedEffect(state) {
        state.player.onEnded { state.player.playNext() }
    }

    Window(
        onCloseRequest = {
            state.player.dispose()
            exitApplication()
        },
        title = "Mochy Music",
        state = windowState,
    ) {
        MochyWindow(state)
    }
}
