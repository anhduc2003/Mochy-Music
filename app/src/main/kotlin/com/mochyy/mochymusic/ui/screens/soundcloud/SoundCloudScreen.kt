package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mochyy.mochymusic.models.MediaMetadata
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.viewmodels.SoundCloudViewModel

/**
 * SoundCloud, as the app shows it: search the catalog, tap an upload, hear it. The stream is
 * resolved by its own upload id, so the app plays the upload that was tapped rather than a
 * same-titled one.
 */
@Composable
fun SoundCloudScreen(
    navController: NavController,
    viewModel: SoundCloudViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val loading by viewModel.loading.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "SoundCloud",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Tìm bài trên SoundCloud") },
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
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(results, key = { _, upload -> upload.id }) { index, upload ->
                UploadRow(
                    upload = upload,
                    onClick = {
                        val items = results.map { it.toMediaItem() }
                        playerConnection.playQueue(
                            ListQueue(title = "SoundCloud", items = items, startIndex = index),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UploadRow(upload: SoundCloudModule.Upload, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = upload.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
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
    }
}

/** the queue's copy of an upload: its own id, so the service opens that upload and not a title match */
private fun SoundCloudModule.Upload.toMediaItem() = MediaMetadata(
    id = "$SOUNDCLOUD_PREFIX$id",
    title = title,
    artists = listOf(MediaMetadata.Artist(null, artist)),
    duration = ((durationMs ?: 0L) / 1000L).toInt(),
    thumbnailUrl = artworkUrl,
).toMediaItem()

/** media ids the app gives its own SoundCloud uploads */
const val SOUNDCLOUD_PREFIX = "SC_"
