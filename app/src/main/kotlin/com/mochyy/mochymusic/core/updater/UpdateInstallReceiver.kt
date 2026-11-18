// UpdateInstallReceiver.kt

package com.mochyy.mochymusic.core.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.core.updater.downloadmanager.DownloadNotificationManager
import com.mochyy.mochymusic.ui.component.TopToaster
import timber.log.Timber

/**
 * What the system installer made of an update session started by [UpdateInstaller].
 *
 * Without this the failure was invisible: the confirmation dialog closed, the old build stayed, and
 * nothing said whether the key was wrong, the file was broken, or the phone was full. Declared in the
 * manifest because the answer usually lands while this app is being replaced.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STATUS) report(context, intent)
    }

    private fun report(context: Context, intent: Intent) {
        val version = intent.getStringExtra(EXTRA_VERSION)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // the system's own "Do you want to update this app?" step
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                runCatching { context.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Timber.tag(TAG).e(it, "Could not open the install confirmation") }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Timber.tag(TAG).i("Update${version?.let { " to $it" }.orEmpty()} installed")
                TopToaster.show(context.getString(R.string.update_install_done))
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Timber.tag(TAG).w("Install of $version failed: status=$status message=$message")
                val reason = reasonFor(context, status, message)
                TopToaster.error(reason, durationMs = 8_000L)
                notifyBlocked(context, reason)
            }
        }
    }

    /** Says it in the shade too: the toast is only seen when the app happens to be open. */
    private fun notifyBlocked(context: Context, reason: String) {
        runCatching {
            DownloadNotificationManager.initialize(context)
            val notification = NotificationCompat.Builder(context, DownloadNotificationManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.updated)
                .setContentTitle(context.getString(R.string.update_install_title))
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            NotificationManagerCompat.from(context)
                .notify(BLOCKED_NOTIFICATION_ID, notification)
        }.onFailure { Timber.tag(TAG).e(it, "Could not post the install notice") }
    }

    private fun reasonFor(context: Context, status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(R.string.update_install_aborted)
        PackageInstaller.STATUS_FAILURE_BLOCKED -> context.getString(R.string.update_install_blocked)
        PackageInstaller.STATUS_FAILURE_CONFLICT -> context.getString(R.string.update_apk_other_key)
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> context.getString(R.string.update_install_incompatible)
        PackageInstaller.STATUS_FAILURE_INVALID ->
            context.getString(R.string.update_apk_unreadable) + " " + message.orEmpty()
        PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.update_install_storage)
        else -> context.getString(R.string.update_install_failed, message.orEmpty())
    }

    companion object {
        private const val TAG = "UpdateInstall"
        private const val BLOCKED_NOTIFICATION_ID = 7391

        /** Explicit component, so neither action can reach anything but this app's own receiver. */
        const val ACTION_STATUS = "com.mochyy.mochymusic.UPDATE_INSTALL_STATUS"

        const val EXTRA_VERSION = "version"
    }
}
