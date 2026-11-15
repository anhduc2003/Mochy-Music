// FloatingLyricsSwitch.kt

package com.mochyy.mochymusic.floating

import androidx.compose.runtime.Composable
import com.mochyy.mochymusic.constants.FloatingLyricsKey
import com.mochyy.mochymusic.utils.rememberPreference

// the lyrics switch the player menu, customisation and app behaviour share; the line rides on the player card itself, so nothing has to be
// allowed over other apps for it and a plain preference is all it takes
@Composable
fun rememberFloatingLyricsSwitch(): Pair<Boolean, (Boolean) -> Unit> {
    val (wanted, setWanted) = rememberPreference(FloatingLyricsKey, defaultValue = false)
    return wanted to setWanted
}
