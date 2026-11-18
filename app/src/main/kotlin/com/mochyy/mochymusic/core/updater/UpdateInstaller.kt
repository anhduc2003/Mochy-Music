// UpdateInstaller.kt

package com.mochyy.mochymusic.core.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.TopToaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Installs a downloaded update **over** the copy already on the phone, keeping its data.
 *
 * The system installer already does that for an APK of the same package signed by the same key: an
 * app is only wiped when it is uninstalled, and installing a newer build of itself replaces the code
 * and leaves `files/`, the database and the settings alone. What makes people uninstall first is a
 * signature that does not match — every release built by CI was signed with a keystore generated for
 * that one run, so the next one was a different app as far as Android is concerned, and the only way
 * forward was uninstall.
 *
 * So this checks the download against what is installed *before* handing it over, and reports what
 * the installer actually said instead of leaving a bare "App not installed".
 */
object UpdateInstaller {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where an update stands against the copy on this phone. */
    sealed interface Verdict {
        /** Same package, same key, not older: installing this replaces the code and keeps every byte of data. */
        data object Ready : Verdict

        /** Same package and key, but a lower build number, which Android refuses unless the app is removed. */
        data class Older(val installed: Long, val incoming: Long) : Verdict

        /** Signed by another key: Android refuses outright, and the copy would have to be uninstalled first. */
        data class DifferentKey(val installed: String, val incoming: String) : Verdict

        /** Not this app at all — a different package name in the archive. */
        data class OtherApp(val packageName: String) : Verdict

        /** The archive could not be read as an APK at all, usually a half-finished download. */
        data class Unreadable(val reason: String?) : Verdict
    }

    /**
     * What [apk] is, and whether it can go over the installed app. Reads the archive only; nothing
     * is installed and nothing is changed.
     */
    fun check(context: Context, apk: File): Verdict {
        val archive = runCatching {
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_SIGNATURES or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }.getOrNull() ?: return Verdict.Unreadable(context.getString(R.string.update_apk_unreadable))

        val name = archive.packageName ?: return Verdict.Unreadable(context.getString(R.string.update_apk_unreadable))
        val installed = installedInfo(context) ?: return Verdict.OtherApp(name)
        if (installed.packageName != name) return Verdict.OtherApp(name)

        val installedKey = signingDigests(installed).firstOrNull()
        val incomingKey = signingDigests(archive).firstOrNull()
        if (installedKey != null && incomingKey != null && installedKey != incomingKey) {
            return Verdict.DifferentKey(installedKey, incomingKey)
        }

        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        val incomingCode = PackageInfoCompat.getLongVersionCode(archive)
        if (incomingCode < installedCode) return Verdict.Older(installedCode, incomingCode)

        return Verdict.Ready
    }

    /** What came of asking for an install. */
    sealed interface Start {
        /** The system's own confirmation is on its way. */
        data object Started : Start

        /** Mochy may not install packages yet: the permission page has just been opened. */
        data object NeedsPermission : Start

        /** The download cannot replace what is installed; [verdict] says why. */
        data class Refused(val verdict: Verdict) : Start
    }

    /**
     * Hands [apk] to the system installer as an update. The verdict is checked first: a download that
     * cannot replace what is installed says so instead of failing silently behind the system's dialog.
     *
     * [version] only labels the failure message.
     */
    fun install(context: Context, apk: File, version: String? = null): Start {
        val verdict = check(context, apk)
        if (verdict !is Verdict.Ready) {
            explain(context, verdict)
            return Start.Refused(verdict)
        }
        if (!canInstallPackages(context)) {
            requestInstallPermission(context)
            return Start.NeedsPermission
        }
        scope.launch {
            runCatching { startSession(context, apk, version) }
                .onFailure { error ->
                    Timber.tag(TAG).e(error, "Install session failed to start")
                    TopToaster.error(context.getString(R.string.update_install_failed, error.message.orEmpty()))
                }
        }
        return Start.Started
    }

    /** The verdict in the listener's own words, or null when there is nothing to say. */
    fun describe(context: Context, verdict: Verdict): String? = when (verdict) {
        is Verdict.Ready -> null
        is Verdict.Unreadable -> context.getString(R.string.update_apk_unreadable) + " " + verdict.reason.orEmpty()
        is Verdict.OtherApp -> context.getString(R.string.update_apk_other_app, verdict.packageName)
        is Verdict.Older -> context.getString(R.string.update_apk_older, verdict.incoming, verdict.installed)
        is Verdict.DifferentKey -> context.getString(R.string.update_apk_other_key)
    }

    fun explain(context: Context, verdict: Verdict) {
        describe(context, verdict)?.let { TopToaster.error(it, durationMs = 6_000L) }
    }

    private fun startSession(context: Context, apk: File, version: String?) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // a full install of a package that is already here replaces it in place; the data
            // directory, the database and the settings are untouched
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // the install dialog is the system's, not ours: Play's policy and every OEM's
                // expectation, and the only path that works without a privileged permission
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(STREAM_NAME, 0, apk.length()).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        session.fsync(output)
                    }
                }
                session.commit(statusIntent(context, version, sessionId).intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun statusIntent(context: Context, version: String?, sessionId: Int) =
        android.app.PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_STATUS
                putExtra(UpdateInstallReceiver.EXTRA_VERSION, version)
            },
            // mutable: the installer fills the confirmation dialog's intent into this one
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0,
        )

    private fun installedInfo(context: Context): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        context.packageManager.getPackageInfo(context.packageName, flags)
    }.getOrNull()

    /** SHA-256 of each signing certificate, so two builds of the same key compare equal. */
    private fun signingDigests(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signing ->
                signing.apkContentsSigners.takeIf { it.isNotEmpty() }
                    ?: signing.signingCertificateHistory.takeLast(1).toTypedArray()
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }.orEmpty()
        return signatures.mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }
    }

    private const val TAG = "UpdateInstaller"
    private const val STREAM_NAME = "mochy.apk"
}
