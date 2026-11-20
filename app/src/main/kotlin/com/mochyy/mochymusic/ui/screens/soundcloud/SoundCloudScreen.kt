package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackRow
import com.mochyy.mochymusic.ui.screens.playlist.GeneratedPlaylistPage
import com.mochyy.mochymusic.ui.screens.playlist.generatedStats
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.viewmodels.SoundCloudViewModel

/**
 * SoundCloud in the app's own clothes: the same page, row and search capsule as a playlist, so it
 * reads as one more part of Mochy rather than a screen bolted onto it. It opens on what SoundCloud
 * is playing this week, searches the catalog as you type, and opens a playlist's tracks in place.
 * Playback is by the upload's own id, so the upload that was tapped is the one that plays.
 */
@Composable
fun SoundCloudScreen(
    navController: NavController,
    viewModel: SoundCloudViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val state by viewModel.state.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val lazyListState = rememberLazyListState()
    BackHandler(enabled = state.open != null || state.relatedTo != null) {
        if (state.relatedTo != null) viewModel.closeRelated() else viewModel.closePlaylist()
    }

    // the page's capsule is the catalog's search box, so what is typed there is what is searched
    var query by remember { mutableStateOf(TextFieldValue(state.query)) }
    val uriHandler = LocalUriHandler.current

    val uploads = state.uploads
    val totalSeconds = remember(uploads) { (uploads.sumOf { it.durationMs ?: 0L } / 1000L).toInt() }
    val title = state.open?.title ?: state.relatedTo?.title ?: "SoundCloud"
    val active = mediaMetadata?.id
    val recommendationSeed = mediaMetadata?.let { current ->
        if (current.id.startsWith(SC)) current.id.removePrefix(SC)
        else listOf(current.title, current.artists.joinToString(" ") { it.name }).filter { it.isNotBlank() }.joinToString(" ")
    }

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
            state.tab == SoundCloudViewModel.Tab.Recommendations -> "Gợi ý từ SoundCloud"
            state.query.isNotBlank() -> "Kết quả tìm kiếm"
            state.region == SoundCloudModule.ChartRegion.VN -> "Khám phá nhạc Việt theo từ khóa"
            else -> "Thịnh hành toàn cầu"
        },
    ) {
        state.error?.let { error ->
            item(key = "soundcloud-error", contentType = "status") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(error)
                    Button(onClick = viewModel::retry) { Text("Thử lại") }
                }
            }
        }
        if (state.open == null && state.relatedTo == null) {
            item(key = "browse-filters", contentType = "filters") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundCloudViewModel.Tab.entries.forEach { tab ->
                            FilterChip(
                                selected = state.tab == tab,
                                onClick = {
                                    viewModel.onTabChange(
                                        tab,
                                        currentTrackSeed = if (tab == SoundCloudViewModel.Tab.Recommendations) recommendationSeed else null,
                                    )
                                },
                                label = {
                                    Text(
                                        when (tab) {
                                            SoundCloudViewModel.Tab.Tracks -> "Bài hát"
                                            SoundCloudViewModel.Tab.Playlists -> "Playlist"
                                            SoundCloudViewModel.Tab.Recommendations -> "Gợi ý"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (state.query.isBlank() && (state.tab == SoundCloudViewModel.Tab.Tracks ||
                            (state.tab == SoundCloudViewModel.Tab.Recommendations && state.recommendationSeed.isNullOrBlank()))) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            SoundCloudModule.ChartRegion.entries.forEach { region ->
                                FilterChip(
                                    selected = state.region == region,
                                    onClick = { viewModel.onRegionChange(region) },
                                    label = { Text(region.label) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.loading) {
            item(key = "soundcloud-loading", contentType = "status") {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    CircularProgressIndicator()
                }
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

        itemsIndexed(uploads, key = { index, upload -> "$index-${upload.id}" }, contentType = { _, _ -> "track" }) { index, upload ->
            val isThis = "$SC${upload.id}" == active
            PlaylistTrackRow(
                thumbnailUrl = upload.artworkUrl,
                title = upload.title,
                subtitle = listOfNotNull(
                    upload.artist.takeIf { it.isNotBlank() },
                    upload.durationMs?.let { makeTimeString(it) },
                    upload.likesCount?.let { "$it lượt thích" },
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
                onMenuClick = { viewModel.openComments(upload) },
            )
        }
    }

    state.commentsFor?.let { upload ->
        AlertDialog(
            onDismissRequest = viewModel::closeComments,
            title = { Text(upload.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(listOfNotNull(
                        upload.likesCount?.let { "$it lượt thích" },
                        upload.commentCount?.let { "$it bình luận" },
                    ).joinToString(" • "))
                    Text("Bình luận công khai. Đăng nhập và tương tác tài khoản hiện thực hiện trên SoundCloud.")
                    when {
                        state.commentsLoading -> CircularProgressIndicator()
                        state.commentsError != null -> {
                            Text(state.commentsError.orEmpty())
                            TextButton(onClick = { viewModel.openComments(upload) }) { Text("Thử lại") }
                        }
                        state.comments.isEmpty() -> Text("Chưa có bình luận công khai.")
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.comments, key = { it.id }) { comment ->
                                Column {
                                    Text(
                                        listOfNotNull(comment.author.takeIf { it.isNotBlank() }, comment.timestampMs?.let(::makeTimeString)).joinToString(" • "),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(comment.body)
                                }
                            }
                        }
                    }
                    state.commentsMoreError?.let { Text(it) }
                    if (state.commentsLoadingMore) CircularProgressIndicator()
                    else if (state.commentsNextOffset != null && !state.commentsLoading && state.commentsError == null) {
                        TextButton(onClick = viewModel::loadMoreComments) {
                            Text(if (state.commentsMoreError != null) "Thử tải thêm lần nữa" else "Tải thêm bình luận")
                        }
                    }
                    TextButton(onClick = {
                        viewModel.closeComments()
                        viewModel.openRelated(upload)
                    }) { Text("Nghe bài tương tự") }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeComments) { Text("Đóng") }
            },
            dismissButton = {
                val link = upload.permalinkUrl?.takeIf {
                    val parsed = Uri.parse(it)
                    parsed.scheme == "https" && parsed.host in setOf("soundcloud.com", "www.soundcloud.com")
                }
                if (link != null) TextButton(onClick = { uriHandler.openUri(link) }) { Text("Mở trên SoundCloud") }
            },
        )
    }
}

/** the media id prefix the app gives a SoundCloud upload, kept next to the screen that builds them */
private const val SC = SoundCloudModule.MediaIdPrefix
