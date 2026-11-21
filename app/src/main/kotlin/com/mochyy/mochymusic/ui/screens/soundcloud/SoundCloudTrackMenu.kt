package com.mochyy.mochymusic.ui.screens.soundcloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.ui.player.menu.MenuRow
import com.mochyy.mochymusic.ui.player.menu.MenuSheetSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.makeTimeString

private val Muted = Color(0xFF9A9AA1)
private val Artwork = Color(0xFF232327)

/**
 * What a row's three dots open: the upload's own actions instead of jumping straight into its
 * related page. Playback and queue first, then the things that need the network (comments, the
 * related page, the artist search), then the ways out of the app (share, copy, open in SoundCloud).
 */
@Composable
fun SoundCloudTrackMenu(
    upload: SoundCloudModule.Upload,
    playing: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onComments: () -> Unit,
    onSimilar: () -> Unit,
    onArtist: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInSoundCloud: () -> Unit,
) {
    MenuSheetSurface(onDismiss = onDismiss, wrapHeight = true) { _ ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                AsyncImage(
                    model = upload.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Artwork),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        upload.title,
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(
                            upload.artist.takeIf { it.isNotBlank() },
                            upload.durationMs?.let { makeTimeString(it) },
                            upload.likesCount?.let { "$it thích" },
                            upload.commentCount?.let { "$it bình luận" },
                        ).joinToString(" • "),
                        color = Muted,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MenuRow(
                icon = if (playing) R.drawable.pause else R.drawable.play,
                title = if (playing) "Tạm dừng" else "Phát",
                onClick = onPlay,
            )
            MenuRow(icon = R.drawable.skip_next, title = "Phát tiếp theo", onClick = onPlayNext)
            MenuRow(icon = R.drawable.queue_music, title = "Thêm vào hàng đợi", onClick = onAddToQueue)
            MenuRow(
                icon = R.drawable.chat_msg,
                title = "Bình luận",
                onClick = onComments,
                trailing = upload.commentCount?.let { count ->
                    {
                        Text(
                            "$count",
                            color = Muted,
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                        )
                    }
                },
            )
            MenuRow(icon = R.drawable.similar, title = "Bài hát tương tự", onClick = onSimilar)
            MenuRow(icon = R.drawable.artist, title = "Tìm nghệ sĩ", onClick = onArtist)
            if (upload.permalinkUrl != null) {
                MenuRow(icon = R.drawable.share, title = "Chia sẻ", onClick = onShare)
                MenuRow(icon = R.drawable.content_copy, title = "Sao chép liên kết", onClick = onCopyLink)
                MenuRow(icon = R.drawable.link, title = "Mở trên SoundCloud", onClick = onOpenInSoundCloud)
            }
        }
    }
}
