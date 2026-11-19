// CrashHandler.kt

package com.mochyy.mochymusic.utils

import android.content.Context
import android.util.Log
import com.mochyy.mochymusic.BuildConfig
import com.mochyy.mochymusic.ui.screens.CrashActivity
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class CrashHandler private constructor(
    private val applicationContext: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val crashLog = runCatching { buildCrashLog(throwable) }.getOrElse {
            // the crash report must never be the thing that crashes the crash handler
            "mochy Crash Report\n\n${throwable.javaClass.name}: ${throwable.message}"
        }
        Timber.e(throwable, "App crashed")
        // Timber isn't planted in release builds, and the process is killed right after: without
        // this a release crash left nothing behind at all. Log.e is kept by R8.
        Log.e("MochyCrash", crashLog)
        // and on the phone, because logcat has long moved on by the time a crash gets looked at.
        // Read it back with `adb shell run-as <package> cat files/last_crash.txt`.
        runCatching { File(applicationContext.filesDir, LAST_CRASH_FILE).writeText(crashLog) }

        // the report screen runs in its own process, so it outlives the one this crash is ending.
        // Start it before the kill: the activity manager has the request by the time startActivity
        // returns, so the screen still comes up after this process is gone.
        val shown = runCatching {
            applicationContext.startActivity(CrashActivity.intent(applicationContext, crashLog))
        }.isSuccess

        if (!shown) {
            // no screen to show (background start refused, no permission to draw): hand the crash
            // back to the system so its own dialog carries the news
            defaultHandler?.uncaughtException(thread, throwable)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    private fun buildCrashLog(throwable: Throwable): String {
        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()

        return buildString {
            appendLine("mochy Crash Report")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
            appendLine("Device: ${android.os.Build.MODEL}")
            appendLine("Android version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("=".repeat(50))
            appendLine("Stacktrace:")
            appendLine("=".repeat(50))
            appendLine()
            append(stackTrace)
        }
    }

    companion object {
        const val EXTRA_CRASH_LOG = "crash_log"
        const val LAST_CRASH_FILE = "last_crash.txt"

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Timber.d("CrashHandler installed")
        }

        /** The report from the last crash, for the report screen when no intent carried one. */
        fun readLastCrash(context: Context): String? = runCatching {
            File(context.filesDir, LAST_CRASH_FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
