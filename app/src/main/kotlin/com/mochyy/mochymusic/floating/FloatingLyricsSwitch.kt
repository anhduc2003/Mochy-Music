// FloatingLyricsSwitch.kt

package com.mochyy.mochymusic.floating

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.FloatingLyricsKey
import com.mochyy.mochymusic.utils.rememberPreference

// the lyrics switch the player menu, customisation and app behaviour share; the line in the player card needs nothing from android, the
// bubble over other apps needs the permission to draw there, so turning it on asks for that once
@Composable
fun rememberFloatingLyricsSwitch(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val (wanted, setWanted) = rememberPreference(FloatingLyricsKey, defaultValue = false)
    val onChange: (Boolean) -> Unit = { on ->
        setWanted(on)
        if (on && !Settings.canDrawOverlays(context)) askForOverlay(context)
    }
    // Card lyrics do not require overlay permission; denial must not switch them off.
    return wanted to onChange
}

private fun askForOverlay(context: Context) {
    Toast.makeText(context, R.string.floating_lyrics_permission, Toast.LENGTH_LONG).show()
    val ask = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // a few phones hide that screen, the app's own settings page has the same switch
    runCatching { context.startActivity(ask) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
