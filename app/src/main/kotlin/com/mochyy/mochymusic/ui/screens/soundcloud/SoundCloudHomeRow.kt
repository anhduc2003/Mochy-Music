package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.viewmodels.SoundCloudViewModel

/**
 * Home's SoundCloud row uses the same Vietnamese discovery feed as the SoundCloud screen.
 * If the module is disabled or no matching tracks are available, the row renders nothing.
 */
@Composable
fun SoundCloudHomeRow(
    onPlay: (List<SoundCloudModule.Upload>, Int) -> Unit,
    viewModel: SoundCloudViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val uploads = state.uploads
    if (!state.enabled || uploads.isEmpty()) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(uploads, key = { _, upload -> upload.id }) { index, upload ->
            Column(
                modifier = Modifier
                    .width(132.dp)
                    .clickable { onPlay(uploads, index) },
            ) {
                AsyncImage(
                    model = upload.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Text(
                    text = upload.title,
                    style = MaterialTheme.typography.bodyMedium,
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
}
