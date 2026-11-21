package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.modules.ModuleEntry
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackRow
import com.mochyy.mochymusic.ui.screens.playlist.GeneratedPlaylistPage
import com.mochyy.mochymusic.ui.screens.playlist.generatedStats
import com.mochyy.mochymusic.ui.screens.settings.modules.moduleRoute
import com.mochyy.mochymusic.ui.screens.settings.modules.showModuleSignInSheet
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.viewmodels.ModulesViewModel
import com.mochyy.mochymusic.viewmodels.SoundCloudViewModel

private val Muted = Color(0xFF9A9AA1)
private val CardSurface = Color(0xFF161618)
private val ChipSurface = Color(0xFF232327)

/**
 * SoundCloud in the app's own clothes: the same page, row and search capsule as a playlist, so it
 * reads as one more part of Mochy rather than a screen bolted onto it. It opens on Vietnamese
 * discovery, searches the catalog as you type, and opens a playlist's tracks in place. The card at
 * the top is the module's face and its account: signing in here is the same sheet the module page
 * opens, and it is what makes the player's comment box write instead of asking.
 */
@Composable
fun SoundCloudScreen(
    navController: NavController,
    viewModel: SoundCloudViewModel = hiltViewModel(),
    modulesViewModel: ModulesViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val overlay = LocalZoomOutOverlayState.current
    val state by viewModel.state.collectAsState()
    val modules by modulesViewModel.entries.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val lazyListState = rememberLazyListState()
    BackHandler(enabled = state.open != null || state.relatedTo != null) {
        if (state.relatedTo != null) viewModel.closeRelated() else viewModel.closePlaylist()
    }

    val entry = modules.firstOrNull { it.id == SoundCloudModule.Id }

    // the page's capsule is the catalog's search box, so what is typed there is what is searched
    var query by remember { mutableStateOf(TextFieldValue(state.query)) }

    val uploads = state.uploads
    val totalSeconds = remember(uploads) { (uploads.sumOf { it.durationMs ?: 0L } / 1000L).toInt() }
    val title = state.open?.title ?: state.relatedTo?.title ?: "SoundCloud"
    val active = mediaMetadata?.id
    val recommendationSeed = mediaMetadata?.let { current ->
        if (current.id.startsWith(SC)) current.id.removePrefix(SC)
        else listOf(current.title, current.artists.joinToString(" ") { it.name }).filter { it.isNotBlank() }.joinToString(" ")
    }
    val root = state.open == null && state.relatedTo == null
    val empty = !state.loading && state.error == null && uploads.isEmpty() && state.playlists.isEmpty()

    fun play(from: Int, shuffled: Boolean = false) {
        val ordered = if (shuffled) uploads.shuffled() else uploads
        val start = if (shuffled) 0 else from.coerceIn(0, (ordered.size - 1).coerceAtLeast(0))
        if (ordered.isEmpty()) return
        playerConnection.playQueue(
            ListQueue(
                title = title,
                items = ordered.map { it.toMediaItem() },
                startIndex = start,
            ),
        )
    }

    fun signIn() {
        val module = entry?.module ?: return
        overlay.showModuleSignInSheet(module, modulesViewModel) { }
    }

    GeneratedPlaylistPage(
        title = title,
        coverUrl = uploads.firstOrNull()?.artworkUrl,
        stats = if (state.playlists.isNotEmpty()) {
            "${state.playlists.size} playlists"
        } else {
            generatedStats(uploads.size, totalSeconds)
        },
        lazyListState = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
            .union(WindowInsets.ime).asPaddingValues(),
        isPlaying = uploads.any { "$SC${it.id}" == active } && isPlaying,
        onPlayClick = { play(0) },
        onShuffleClick = { play(0, shuffled = true) },
        query = query,
        onQueryChange = {
            query = it
            viewModel.onQueryChange(it.text)
        },
        searchPlaceholder = "Tìm bài hoặc playlist trên SoundCloud",
        onBackClick = {
            when {
                state.open != null -> viewModel.closePlaylist()
                state.relatedTo != null -> viewModel.closeRelated()
                else -> navController.navigateUp()
            }
        },
        onBackLongClick = { navController.backToMain() },
        creator = when {
            state.open != null -> state.open?.artist
            state.relatedTo != null -> "Tương tự ${state.relatedTo?.title.orEmpty()}"
            state.tab == SoundCloudViewModel.Tab.Recommendations -> "Gợi ý nhạc Việt"
            state.query.isNotBlank() -> "Kết quả tìm kiếm"
            else -> "Khám phá nhạc Việt"
        },
    ) {
        if (root) {
            item(key = "soundcloud-brand", contentType = "brand") {
                SoundCloudBrandCard(
                    entry = entry,
                    enabled = state.enabled,
                    trackCount = uploads.size,
                    onSignIn = ::signIn,
                    onOpenModule = { navController.navigate(moduleRoute(SoundCloudModule.Id)) },
                )
            }
        }

        state.error?.let { error ->
            item(key = "soundcloud-error", contentType = "status") {
                StatusCard(
                    title = "Chưa tải được SoundCloud",
                    message = error,
                    action = "Thử lại",
                    onAction = viewModel::retry,
                )
            }
        }

        if (root && state.error == null) {
            item(key = "browse-filters", contentType = "filters") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundCloudViewModel.Tab.entries.forEach { tab ->
                            TabPill(
                                text = when (tab) {
                                    SoundCloudViewModel.Tab.Tracks -> "Nhạc Việt"
                                    SoundCloudViewModel.Tab.Playlists -> "Playlist Việt"
                                    SoundCloudViewModel.Tab.Recommendations -> "Gợi ý"
                                },
                                selected = state.tab == tab,
                                onClick = {
                                    viewModel.onTabChange(
                                        tab,
                                        currentTrackSeed = if (tab == SoundCloudViewModel.Tab.Recommendations) recommendationSeed else null,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (state.loading) {
            items(6, key = { "skeleton-$it" }, contentType = { "skeleton" }) { index ->
                SkeletonRow(index)
            }
        } else if (empty) {
            item(key = "soundcloud-empty", contentType = "status") {
                EmptyCard(
                    enabled = state.enabled,
                    hasQuery = state.query.isNotBlank(),
                    onRetry = viewModel::retry,
                    onOpenModule = { navController.navigate(moduleRoute(SoundCloudModule.Id)) },
                )
            }
        }

        items(state.playlists, key = { "pl-${it.id}" }, contentType = { "playlist" }) { playlist ->
            PlaylistTrackRow(
                thumbnailUrl = playlist.artworkUrl,
                title = playlist.title,
                subtitle = "${playlist.artist} • ${playlist.trackCount} bài",
                isActive = false,
                isPlaying = false,
                onClick = { viewModel.openPlaylist(playlist) },
                onLongClick = { viewModel.openPlaylist(playlist) },
                onMenuClick = { viewModel.openPlaylist(playlist) },
            )
        }

        items(uploads.size, key = { index -> "${index}-${uploads[index].id}" }, contentType = { "track" }) { index ->
            val upload = uploads[index]
            val isThis = "$SC${upload.id}" == active
            PlaylistTrackRow(
                thumbnailUrl = upload.artworkUrl,
                title = upload.title,
                subtitle = listOfNotNull(
                    upload.artist.takeIf { it.isNotBlank() },
                    upload.durationMs?.let { makeTimeString(it) },
                    upload.likesCount?.let { "$it thích" },
                    upload.commentCount?.let { "$it bình luận" },
                ).joinToString(" • "),
                isActive = isThis,
                isPlaying = isThis && isPlaying,
                onClick = {
                    if (isThis) playerConnection.togglePlayPause() else play(index)
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.openRelated(upload)
                },
                onMenuClick = { viewModel.openRelated(upload) },
            )
        }
    }
}

/** the module's face at the top of its page: what it is, how much is here, and who it is signed in as */
@Composable
private fun SoundCloudBrandCard(
    entry: ModuleEntry?,
    enabled: Boolean,
    trackCount: Int,
    onSignIn: () -> Unit,
    onOpenModule: () -> Unit,
) {
    val account = entry?.account ?: AccountState.SignedOut
    val signedIn = account is AccountState.SignedIn
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(CardSurface)
            .clickable(onClick = onOpenModule)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.provider_soundcloud),
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SoundCloud", color = Color.White, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    if (!enabled) "Module đang tắt" else if (trackCount > 0) "Nhạc Việt · $trackCount bài trong mục này" else "Nhạc Việt và playlist cộng đồng",
                    color = Muted,
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (entry != null) AccountPill(account = account, onSignIn = onSignIn)
        }
        if (account is AccountState.Expired) {
            Spacer(Modifier.height(8.dp))
            Text(
                account.reason,
                color = Color(0xFFFFB340),
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        } else if (!signedIn) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Đăng nhập để bình luận bằng tài khoản của bạn. Nghe nhạc không cần đăng nhập.",
                color = Muted,
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun AccountPill(account: AccountState, onSignIn: () -> Unit) {
    when (account) {
        is AccountState.SignedIn -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ChipSurface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(painterResource(R.drawable.account), null, tint = Color.White, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                account.label.substringAfterLast("·").trim().ifEmpty { "Đã đăng nhập" },
                color = Color.White,
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
        else -> Text(
            if (account is AccountState.Expired) "Đăng nhập lại" else "Đăng nhập",
            color = Color.Black,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .clickable(onClick = onSignIn)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.8f),
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else ChipSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** a loading row in the shape of a track, so the page keeps its rhythm instead of flashing a spinner */
@Composable
private fun SkeletonRow(index: Int) {
    val alpha = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f * alpha.value)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(if (index % 2 == 0) 0.62f else 0.48f)
                    .height(13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.10f * alpha.value)),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.34f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.07f * alpha.value)),
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, message: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x33FF453A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(message, color = Color.White.copy(alpha = 0.75f), fontFamily = InterFontFamily, fontSize = 12.sp, lineHeight = 17.sp)
        Text(
            action,
            color = Color.Black,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyCard(enabled: Boolean, hasQuery: Boolean, onRetry: () -> Unit, onOpenModule: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.chat_msg), null, tint = Muted, modifier = Modifier.size(26.dp))
        Text(
            when {
                !enabled -> "SoundCloud đang tắt"
                hasQuery -> "Không tìm thấy kết quả"
                else -> "Chưa có nội dung để hiển thị"
            },
            color = Color.White,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            when {
                !enabled -> "Bật module để tìm nhạc, nghe và bình luận."
                hasQuery -> "Thử từ khóa khác, hoặc tìm bằng tên nghệ sĩ."
                else -> "Kiểm tra kết nối mạng rồi tải lại."
            },
            color = Muted,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (enabled) "Tải lại" else "Mở cài đặt module",
            color = Color.Black,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .clickable(onClick = if (enabled) onRetry else onOpenModule)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** the media id prefix the app gives a SoundCloud upload, kept next to the screen that builds them */
private const val SC = SoundCloudModule.MediaIdPrefix
