// settings → storage: how much of the phone mochy takes, the cache limit, clearing downloads or the cache, and manage downloads

package com.mochyy.mochymusic.ui.screens.settings.storage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.MaxSongCacheSizeKey
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.screens.library.showConfirm
import com.mochyy.mochymusic.utils.rememberPreference
import com.mochyy.mochymusic.viewmodels.StorageUsage
import com.mochyy.mochymusic.viewmodels.StorageViewModel

const val StorageRoute = "storage_settings"

/** song cache limits on offer, in mb, -1 is no limit, the player's default is 1 gb */
private val CacheLimitSteps = listOf(0, 256, 512, 1024, 2048, 4096, 8192, -1)

@Composable
fun StorageScreen(navController: NavController, viewModel: StorageViewModel = hiltViewModel()) {
    val usage by viewModel.usage.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val (cacheLimit, setCacheLimit) = rememberPreference(MaxSongCacheSizeKey, 1024)
    // the player picked its limit when the app started; a new one only applies from the next start
    val startingLimit = remember { cacheLimit }
    val menuState = LocalMenuState.current
    val context = LocalContext.current

    // songs removed from the Manage downloads sheet change the numbers here too
    LaunchedEffect(downloads?.size) { viewModel.measure() }

    SubSettingsScaffold(title = stringResource(R.string.storage), onBack = { navController.navigateUp() }) {
        OverallCard(usage)

        Spacer(Modifier.height(22.dp))
        Text(
            usage?.let { stringResource(R.string.storage_usage, percentOf(it.mochy, it.deviceTotal), formatSize(it.mochy)) }
                ?: stringResource(R.string.storage_calculating),
            style = storageText(17f, FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        Spacer(Modifier.height(14.dp))
        BreakdownCard(usage)

        Spacer(Modifier.height(26.dp))
        Text(
            stringResource(R.string.storage_cleanup_title),
            style = storageText(17f, FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        usage?.let { SmartHint(it, downloadCount = downloads?.size ?: 0) }

        Spacer(Modifier.height(14.dp))
        CacheLimitCard(
            limit = cacheLimit,
            onLimitChange = setCacheLimit,
            appliesLater = cacheLimit != startingLimit,
        )

        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val downloadCount = downloads?.size ?: 0
            StorageActionButton(
                label = stringResource(R.string.storage_remove_downloads),
                detail = if (downloadCount > 0) "$downloadCount ${if (downloadCount == 1) "song" else "songs"} · ${formatSize(usage?.downloads ?: 0)}" else "Chưa tải nội dung nào",
                danger = true,
                enabled = !busy && downloadCount > 0,
                onClick = {
                    menuState.showConfirm(
                        title = context.getString(R.string.storage_remove_downloads_confirm),
                        message = context.getString(R.string.storage_remove_downloads_message),
                        action = context.getString(R.string.storage_remove_downloads),
                        onConfirm = viewModel::removeAllDownloads,
                    )
                },
            )
            StorageActionButton(
                label = stringResource(R.string.storage_clear_cache),
                detail = usage?.let { formatSize(it.cache) },
                danger = true,
                enabled = !busy,
                onClick = {
                    menuState.showConfirm(
                        title = context.getString(R.string.storage_clear_cache_confirm),
                        message = context.getString(R.string.storage_clear_cache_message),
                        action = context.getString(R.string.storage_clear_cache),
                        onConfirm = viewModel::clearCaches,
                    )
                },
            )
            StorageActionButton(
                label = stringResource(R.string.storage_manage_downloads),
                detail = if (downloadCount > 0) "Xem nội dung chiếm nhiều dung lượng nhất và ít được phát" else null,
                enabled = downloadCount > 0,
                onClick = { menuState.showManageDownloadsSheet(viewModel) },
            )
        }

        Spacer(Modifier.height(26.dp))
        TransferSection()
    }
}

/** like 1.4%, or <1% for a sliver, so a small app never reads as using nothing */
private fun percentOf(part: Long, total: Long): String {
    if (total <= 0L) return "0%"
    val p = part * 100.0 / total
    return when {
        p < 0.1 -> "<0.1%"
        p < 10 -> String.format("%.1f%%", p)
        else -> "${p.toInt()}%"
    }
}

@Composable
private fun OverallCard(usage: StorageUsage?) {
    StorageCardColumn {
        Text(stringResource(R.string.storage_overall), style = storageText(17f))
        Spacer(Modifier.height(14.dp))
        UsageBar(
            parts = listOf(
                BarPart(MochyGreen, usage?.mochy ?: 0),
                BarPart(OthersGrey, usage?.othersUsed ?: 0),
            ),
            total = usage?.deviceTotal ?: 0,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LegendSwatch(MochyGreen)
            Spacer(Modifier.width(8.dp))
            Text("Mochy", style = storageText(16f, FontWeight.SemiBold))
            Spacer(Modifier.width(16.dp))
            LegendSwatch(OthersGrey)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.storage_your_storage), style = storageText(16f, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1)
            if (usage != null) {
                Text(stringResource(R.string.storage_free, formatSize(usage.deviceFree)), style = storageText(12f, FontWeight.Medium, StorageMuted, 0f))
            }
        }
    }
}

@Composable
private fun BreakdownCard(usage: StorageUsage?) {
    StorageCardColumn {
        Text(stringResource(R.string.storage_app_data_usage), style = storageText(17f))
        Spacer(Modifier.height(14.dp))
        UsageBar(
            parts = listOf(
                BarPart(CacheOrange, usage?.cache ?: 0),
                BarPart(MochyGreen, usage?.downloads ?: 0),
                BarPart(DatabaseRed, usage?.database ?: 0),
                BarPart(AppDataBlue, usage?.appData ?: 0),
            ),
            total = usage?.mochy ?: 0,
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendRow(CacheOrange, stringResource(R.string.storage_cache), usage?.let { formatSize(it.cache) })
            LegendRow(MochyGreen, stringResource(R.string.storage_downloaded_music), usage?.let { formatSize(it.downloads) })
            LegendRow(DatabaseRed, stringResource(R.string.storage_database), usage?.let { formatSize(it.database) })
            LegendRow(AppDataBlue, stringResource(R.string.storage_app_data), usage?.let { formatSize(it.appData) })
        }
    }
}

/** one line pointing at the biggest easy win: the cache, the downloads sheet, or a nudge when the phone is nearly full */
@Composable
private fun SmartHint(usage: StorageUsage, downloadCount: Int) {
    val nearlyFull = usage.deviceTotal > 0 && usage.deviceFree * 10 < usage.deviceTotal
    val hint = when {
        usage.cache >= usage.downloads && usage.cache > 50_000_000L ->
            "Có thể dễ dàng xóa bộ nhớ đệm: thao tác này giải phóng ${formatSize(usage.cache)} và bài hát sẽ được phát trực tuyến khi cần."
        downloadCount > 0 && usage.downloads > 50_000_000L ->
            "Nội dung tải xuống chiếm nhiều dung lượng nhất. Mục Quản lý nội dung tải xuống giúp tìm nội dung lớn nhất và ít nghe nhất."
        else -> null
    }
    val text = listOfNotNull(if (nearlyFull) "Điện thoại của bạn sắp hết dung lượng." else null, hint).joinToString(" ")
    if (text.isEmpty()) return
    Text(
        text,
        style = storageText(13f, FontWeight.Medium, StorageMuted, -0.2f),
        modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
    )
}

@Composable
private fun CacheLimitCard(limit: Int, onLimitChange: (Int) -> Unit, appliesLater: Boolean) {
    StorageCardColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFDADADA)),
            ) {
                Icon(painterResource(R.drawable.cached), contentDescription = null, tint = StorageCard, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.storage_cache_limit), style = storageText(15f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text(cacheLimitLabel(limit), style = storageText(15f, FontWeight.Bold))
        }
        Spacer(Modifier.height(10.dp))
        StepSlider(steps = CacheLimitSteps, value = limit, onValueChange = onLimitChange)
        Row {
            Text("Không có bộ nhớ đệm", style = storageText(10f, FontWeight.Medium, StorageMuted, 0f), modifier = Modifier.weight(1f))
            Text("Không giới hạn", style = storageText(10f, FontWeight.Medium, StorageMuted, 0f))
        }
        AnimatedVisibility(
            visible = appliesLater,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                "Giới hạn mới có hiệu lực khi bạn mở Mochy lần tới.",
                style = storageText(12f, FontWeight.Medium, StorageMuted, -0.1f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun cacheLimitLabel(megabytes: Int): String = when {
    megabytes < 0 -> "Không giới hạn"
    megabytes == 0 -> "Tắt"
    megabytes < 1024 -> "$megabytes MB"
    else -> "${megabytes / 1024} GB"
}
