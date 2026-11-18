// UpdateInstallActivity.kt

package com.mochyy.mochymusic.core.updater

import android.os.Bundle
import androidx.activity.ComponentActivity
import java.io.File

/**
 * The update a notification's tap starts, from a foreground lifetime.
 *
 * A broadcast receiver cannot do this job: opening the unknown-sources page from one is exactly the
 * notification trampoline Android 12 refuses. This screen draws nothing — the only thing a person
 * sees is the system's own install confirmation — and it does not own the install itself:
 * [UpdateInstaller.start] keeps one job per APK at process level, so a rebuild of this screen
 * (rotation, theme, font size, a SIM change) neither cancels the copy nor starts a second one. It
 * stays up only while that job runs, which is what keeps the process important enough to finish it.
 */
class UpdateInstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apk = intent.getStringExtra(EXTRA_APK)?.let(::File)
        if (apk == null || !apk.exists()) {
            finish()
            return
        }
        val job = UpdateInstaller.start(this, apk, intent.getStringExtra(EXTRA_VERSION)) { finish() }
        // already done (a rebuilt screen picking up a finished job): no window to keep
        if (job.isCompleted) finish()
    }

    companion object {
        const val EXTRA_APK = "apk"
        const val EXTRA_VERSION = "version"
    }
}
