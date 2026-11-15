package com.mochyy.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.desktop.net.InnerTube
import kotlinx.coroutines.launch

/**
 * Online search over YouTube Music.
 *
 * Results are shown and can be resolved to a stream URL, which the player then opens like any
 * other track: the audio engine does not care whether a URI is a file path or an https URL.
 */
@Composable
fun SearchPane(
    onPlay: (videoId: String, title: String, artist: String, durationMs: Long, thumbnail: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tube = remember { InnerTube() }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<InnerTube.SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        if (query.isBlank() || searching) return
        searching = true
        error = null
        scope.launch {
            val found = runCatching { tube.search(query.trim()) }.getOrNull()
            results = found.orEmpty()
            if (found == null) error = "Không tìm được — kiểm tra kết nối mạng"
            else if (found.isEmpty()) error = "Không có kết quả cho “$query”"
            searching = false
        }
    }

    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Tìm bài hát, nghệ sĩ…", fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        when {
            searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFB4BEFE))
                Spacer(Modifier.width(10.dp))
                Text("Đang tìm…", color = Color(0xFF7A7A88), fontSize = 12.sp)
            }
            error != null -> Text(error!!, color = Color(0xFFF38BA8), fontSize = 12.sp)
            results.isNotEmpty() -> LazyColumn {
                items(results, key = { it.videoId }) { result ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onPlay(result.videoId, result.title, result.artist, result.durationMs, result.thumbnailUrl)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                result.title,
                                color = Color(0xFFEDEDF2),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (result.artist.isNotBlank()) {
                                Text(
                                    result.artist,
                                    color = Color(0xFF7A7A88),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(formatTime(result.durationMs), color = Color(0xFF60606C), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
