package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.viewmodels.SoundCloudViewModel

/**
 * SoundCloud, as the app shows it: what's trending before a search, search by track or playlist,
 * tap a playlist to see what's in it, tap a track to hear it. The stream is resolved by the
 * upload's own id, so the app plays the upload that was tapped rather than a same-titled one.
 */
@Composable
fun SoundCloudScreen(
    navController: NavController,
    viewModel: SoundCloudViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text(
            text = state.open?.title
                ?: state.relatedTo?.let { "Tương tự: ${it.title}" }
                ?: "SoundCloud",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (state.open != null) {
            TextButton(onClick = { viewModel.closePlaylist() }) { Text("← Về trang SoundCloud") }
        } else if (state.relatedTo != null) {
            TextButton(onClick = { viewModel.closeRelated() }) { Text("← Về danh sách trước") }
        } else {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Tìm bài hoặc playlist trên SoundCloud") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                trailingIcon = {
                    TextButton(onClick = { viewModel.search() }) { Text("Tìm") }
                },
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.uploads.isNotEmpty()) {
            TextButton(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = state.open?.title ?: "SoundCloud",
                            items = state.uploads.map { it.toMediaItem() },
                        ),
                    )
                },
            ) { Text("Phát tất cả (${state.uploads.size})") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.playlists, key = { "pl-${it.id}" }) { playlist ->
                PlaylistRow(playlist = playlist, onClick = { viewModel.openPlaylist(playlist) })
            }
            itemsIndexed(state.uploads, key = { _, upload -> upload.id }) { index, upload ->
                UploadRow(
                    upload = upload,
                    onRelated = { viewModel.openRelated(upload) },
                    onClick = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = state.open?.title ?: "SoundCloud",
                                items = state.uploads.map { it.toMediaItem() },
                                startIndex = index,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UploadRow(
    upload: SoundCloudModule.Upload,
    onRelated: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(upload.artworkUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = upload.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = upload.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRelated) { Text("Tương tự") }
    }
}

@Composable
private fun PlaylistRow(playlist: SoundCloudModule.Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(playlist.artworkUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.artist} · ${playlist.trackCount} bài",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Artwork(url: String?) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
    )
}
