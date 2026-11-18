// UpdateInstallActivity.kt

package com.mochyy.mochymusic.core.updater

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * The update a notification's tap starts, from a real foreground lifetime.
 *
 * A broadcast receiver cannot do this job: starting the unknown-sources page from one is exactly the
 * notification trampoline Android 12 refuses, and the copy of a 90 MB APK into an install session
 * must not outlive the component that began it. An activity has both — a window to launch settings
 * from, and a lifetime the system keeps until it finishes — and it shows nothing of its own, so the
 * only thing the listener sees is the system's install confirmation.
 */
class UpdateInstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the manifest's configChanges covers every rebuild a person can trigger here (rotation,
        // theme, font size and weight, locale, density), so what reaches this line is a restore after
        // the process was killed: the install it was in the middle of is gone, and staging the same
        // APK from scratch would raise a second confirmation for nothing.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val apk = intent.getStringExtra(EXTRA_APK)?.let(::File)
        val version = intent.getStringExtra(EXTRA_VERSION)
        if (apk == null || !apk.exists()) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val start = UpdateInstaller.install(this@UpdateInstallActivity, apk, version)
                // Started and NeedsPermission both carry on outside this screen; anything else is
                // the only thing the listener will learn about this tap, so it has to be said here
                UpdateInstaller.messageFor(this@UpdateInstallActivity, start)?.let { message ->
                    Toast.makeText(this@UpdateInstallActivity, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_APK = "apk"
        const val EXTRA_VERSION = "version"
    }
}
