package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.media3.common.MediaItem
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule

/**
 * the queue's copy of an upload: its own id, so the service opens that upload by id rather than
 * searching for a title three uploads share.
 */
fun SoundCloudModule.Upload.toMediaItem(): MediaItem = MediaMetadata(
    id = "${SoundCloudModule.MediaIdPrefix}$id",
    title = title,
    artists = listOf(MediaMetadata.Artist(null, artist)),
    duration = ((durationMs ?: 0L) / 1000L).toInt(),
    thumbnailUrl = artworkUrl,
).toMediaItem()
