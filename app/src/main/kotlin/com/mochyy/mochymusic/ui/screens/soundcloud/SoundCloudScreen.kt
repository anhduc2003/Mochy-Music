package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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

    // the page's capsule is the catalog's search box, so what is typed there is what is searched
    var query by remember { mutableStateOf(TextFieldValue()) }

    val uploads = state.uploads
    val totalSeconds = remember(uploads) { (uploads.sumOf { it.durationMs ?: 0L } / 1000L).toInt() }
    val title = state.open?.title ?: state.relatedTo?.title ?: "SoundCloud"
    val active = mediaMetadata?.id

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
            else -> "Thịnh hành tuần này"
        },
    ) {
        if (state.open == null && state.relatedTo == null) {
            item(key = "tabs", contentType = "tabs") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = state.tab == SoundCloudViewModel.Tab.Tracks,
                        onClick = { viewModel.onTabChange(SoundCloudViewModel.Tab.Tracks) },
                        label = { Text("Bài hát") },
                    )
                    FilterChip(
                        selected = state.tab == SoundCloudViewModel.Tab.Playlists,
                        onClick = { viewModel.onTabChange(SoundCloudViewModel.Tab.Playlists) },
                        label = { Text("Playlist") },
                    )
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

        itemsIndexed(uploads, key = { _, upload -> upload.id }, contentType = { _, _ -> "track" }) { index, upload ->
            val isThis = "$SC${upload.id}" == active
            PlaylistTrackRow(
                thumbnailUrl = upload.artworkUrl,
                title = upload.title,
                subtitle = listOfNotNull(
                    upload.artist.takeIf { it.isNotBlank() },
                    upload.durationMs?.let { makeTimeString(it) },
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

/** the media id prefix the app gives a SoundCloud upload, kept next to the screen that builds them */
private const val SC = SoundCloudModule.MediaIdPrefix
