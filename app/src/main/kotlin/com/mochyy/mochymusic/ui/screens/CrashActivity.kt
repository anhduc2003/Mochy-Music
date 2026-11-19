// CrashActivity.kt

package com.mochyy.mochymusic.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.theme.MochyTheme
import com.mochyy.mochymusic.utils.CrashHandler

/**
 * What a crash shows instead of the app just disappearing.
 *
 * Declared in the manifest under its own `:crash` process, so the report survives the process the
 * crash just killed. [CrashHandler] launches it with the report attached and then ends the app.
 */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent?.getStringExtra(CrashHandler.EXTRA_CRASH_LOG)
            ?: CrashHandler.readLastCrash(this)
            ?: ""

        setContent {
            MochyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CrashScreen(
                        report = report,
                        onShare = { share(report) },
                        onCopy = { copy(report) },
                        onClose = { leave() },
                    )
                }
            }
        }
    }

    private fun share(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_report_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.crash_share_title))) }
            .onFailure { Toast.makeText(this, it.message ?: "…", Toast.LENGTH_LONG).show() }
    }

    private fun copy(report: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_report_subject), report))
        Toast.makeText(this, R.string.copy_logs, Toast.LENGTH_SHORT).show()
    }

    /** The app is already gone; closing this screen closes the last of it. */
    private fun leave() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        /** [report] travels in the intent because the process that produced it is about to end. */
        fun intent(context: Context, report: String): Intent =
            Intent(context, CrashActivity::class.java)
                .putExtra(CrashHandler.EXTRA_CRASH_LOG, report)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

@Composable
private fun CrashScreen(
    report: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.crash_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.crash_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            val shown = report.takeIf { it.isNotBlank() } ?: stringResource(R.string.crash_no_log)
            Text(
                text = shown,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.copy_logs))
            }
            TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.crash_close))
            }
        }
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.crash_share_logs))
        }
        Spacer(Modifier.height(2.dp))
    }
}
