package com.mochyy.mochymusic.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.viewmodels.PlayerCommentsViewModel

/** The song's public SoundCloud comments, opened from the player's ••• menu. */
@Composable
fun PlayerCommentsDialog(
    state: PlayerCommentsViewModel.UiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.error != null -> {
                        Text(state.error.orEmpty())
                        TextButton(onClick = onRetry) { Text("Thử lại") }
                    }
                    state.comments.isEmpty() -> Text("Chưa có bình luận công khai.")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.comments, key = { it.id }) { comment ->
                            Column {
                                Text(
                                    listOfNotNull(
                                        comment.author.takeIf { it.isNotBlank() },
                                        comment.timestampMs?.let(::makeTimeString),
                                    ).joinToString(" • "),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(comment.body)
                            }
                        }
                    }
                }
                state.moreError?.let { Text(it) }
                if (state.loadingMore) CircularProgressIndicator()
                else if (state.nextOffset != null && !state.loading && state.error == null) {
                    TextButton(onClick = onLoadMore) {
                        Text(if (state.moreError != null) "Thử tải thêm lần nữa" else "Tải thêm bình luận")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        },
    )
}
