// UpdateInstallActivity.kt

package com.mochyy.mochymusic.core.updater

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mochyy.mochymusic.R
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
        val apk = intent.getStringExtra(EXTRA_APK)?.let(::File)
        val version = intent.getStringExtra(EXTRA_VERSION)
        if (apk == null || !apk.exists()) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                when (val start = UpdateInstaller.install(this@UpdateInstallActivity, apk, version)) {
                    // the system's confirmation takes over from here
                    is UpdateInstaller.Start.Started -> Unit
                    is UpdateInstaller.Start.NeedsPermission -> Unit
                    is UpdateInstaller.Start.Refused -> {
                        Toast.makeText(
                            this@UpdateInstallActivity,
                            UpdateInstaller.describe(this@UpdateInstallActivity, start.verdict)
                                ?: getString(R.string.update_install_incompatible),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
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
