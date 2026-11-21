package com.mochyy.mochymusic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.player.menu.MenuSheetSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.viewmodels.PlayerCommentsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Muted = Color(0xFF9A9AA1)
private val ChipSurface = Color(0xFF232327)
private val ComposerSurface = Color(0xFF1B1B1F)

/**
 * The song's SoundCloud comments, as a sheet over the player: the public thread, a load-more page,
 * and a composer. The composer writes as the account signed in from the module page; when there is
 * no account it says so and opens the same sign-in the settings use, instead of failing a send.
 */
@Composable
fun PlayerCommentsDialog(
    state: PlayerCommentsViewModel.UiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The sheet rests on the screen's bottom edge (a shorter detent pushes its last rows, the
    // composer included, below the screen) and the composer is padded out of the keyboard's way.
    MenuSheetSurface(onDismiss = onDismiss, halfDetent = 1f, fullDetent = 1f) { _ ->
        val listState = rememberLazyListState()
        // a posted comment lands on top of the list, so bring the reader there to see it
        var firstId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state.comments.firstOrNull()?.id, state.posting) {
            val top = state.comments.firstOrNull()?.id
            if (!state.posting && top != null && top != firstId) {
                if (firstId != null) listState.animateScrollToItem(0)
                firstId = top
            }
        }

        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bình luận", color = Color.White, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        state.title.ifBlank { "SoundCloud" },
                        color = Muted,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.comments.isNotEmpty()) {
                    Text(
                        "${state.comments.size}",
                        color = Muted,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(ChipSurface)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ChipSurface)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.close), "Đóng", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> LoadingComments()
                    state.error != null -> CommentsProblem(state.error, onRetry)
                    state.comments.isEmpty() -> EmptyComments()
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.comments, key = { it.id }) { comment -> CommentRow(comment) }
                        if (state.loadingMore) {
                            item(key = "more-loading") {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                                }
                            }
                        } else if (state.nextOffset != null) {
                            item(key = "more") {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    state.moreError?.let {
                                        Text(it, color = Muted, fontFamily = InterFontFamily, fontSize = 12.sp)
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    Text(
                                        if (state.moreError != null) "Thử tải thêm lần nữa" else "Tải thêm bình luận",
                                        color = Color.White,
                                        fontFamily = InterFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(ChipSurface)
                                            .clickable(onClick = onLoadMore)
                                            .padding(horizontal = 16.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Composer(
                account = state.account,
                draft = state.draft,
                posting = state.posting,
                error = state.postError,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onSignIn = onSignIn,
            )
        }
    }
}

@Composable
private fun CommentRow(comment: SoundCloudModule.Comment) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(ChipSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (comment.avatarUrl != null) {
                AsyncImage(
                    model = comment.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                )
            } else {
                Icon(painterResource(R.drawable.person), null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    comment.author.ifBlank { "Người nghe" },
                    color = Color.White,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                commentTime(comment.createdAtMs)?.let {
                    Text(it, color = Muted, fontFamily = InterFontFamily, fontSize = 11.sp)
                }
            }
            Text(comment.body, color = Color.White.copy(alpha = 0.92f), fontFamily = InterFontFamily, fontSize = 14.sp, lineHeight = 20.sp)
            comment.timestampMs?.let { position ->
                Text(
                    "tại ${makeTimeString(position)}",
                    color = Muted,
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ChipSurface)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingComments() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.height(10.dp))
        Text("Đang tải bình luận…", color = Muted, fontFamily = InterFontFamily, fontSize = 13.sp)
    }
}

@Composable
private fun CommentsProblem(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(error, color = Color.White.copy(alpha = 0.85f), fontFamily = InterFontFamily, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Thử lại",
            color = Color.Black,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .clickable(onClick = onRetry)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun EmptyComments() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.chat_msg), null, tint = Muted, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text("Chưa có bình luận nào.", color = Color.White, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("Hãy là người đầu tiên bình luận.", color = Muted, fontFamily = InterFontFamily, fontSize = 12.sp)
    }
}

@Composable
private fun Composer(
    account: AccountState,
    draft: String,
    posting: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MenuRowSurface)
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 22.dp),
    ) {
        if (account !is AccountState.SignedIn) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.account), null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (account is AccountState.Expired) "Phiên đăng nhập đã kết thúc" else "Đăng nhập để bình luận",
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Text(
                        "Bình luận sẽ đăng bằng tài khoản SoundCloud của bạn.",
                        color = Muted,
                        fontFamily = InterFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Đăng nhập",
                    color = Color.Black,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable(onClick = onSignIn)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF8A80), fontFamily = InterFontFamily, fontSize = 12.sp)
            }
            return@Column
        }

        val accountName = (account as? AccountState.SignedIn)?.label?.substringAfterLast("·")?.trim().orEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(painterResource(R.drawable.account), null, tint = Muted, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                if (accountName.isNotEmpty()) "Bình luận với $accountName" else "Bình luận với tài khoản SoundCloud",
                color = Muted,
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ComposerSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (draft.isEmpty()) {
                    Text("Viết bình luận…", color = Muted, fontFamily = InterFontFamily, fontSize = 14.sp)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = TextStyle(color = Color.White, fontFamily = InterFontFamily, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color.White),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (draft.isNotBlank() && !posting) Color.White else ChipSurface)
                    .clickable(enabled = draft.isNotBlank() && !posting, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (posting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(
                        painterResource(R.drawable.send_chat),
                        "Gửi bình luận",
                        tint = if (draft.isNotBlank()) Color.Black else Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFF8A80), fontFamily = InterFontFamily, fontSize = 12.sp)
        }
    }
}

/** "3 giờ trước" for a recent comment, the date once it is older than a week */
private fun commentTime(createdAtMs: Long?): String? {
    if (createdAtMs == null) return null
    val age = System.currentTimeMillis() - createdAtMs
    return when {
        age < 60_000L -> "vừa xong"
        age < 3_600_000L -> "${(age / 60_000L).coerceAtLeast(1)} phút trước"
        age < 86_400_000L -> "${(age / 3_600_000L).coerceAtLeast(1)} giờ trước"
        age < 7 * 86_400_000L -> "${(age / 86_400_000L).coerceAtLeast(1)} ngày trước"
        else -> runCatching { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(createdAtMs)) }.getOrNull()
    }
}
