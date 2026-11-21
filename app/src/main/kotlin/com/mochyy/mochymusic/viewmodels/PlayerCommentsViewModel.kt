package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.MochyModule
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.modules.ModuleRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Public comments for the song in the player. The player has no SoundCloud browsing state of its
 * own, so this owns just the one dialog: which track it is for, the page being read and the errors.
 */
@HiltViewModel
class PlayerCommentsViewModel @Inject constructor(
    private val registry: ModuleRegistry,
) : ViewModel() {

    data class UiState(
        val title: String = "",
        val comments: List<SoundCloudModule.Comment> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val nextOffset: Int? = null,
        val loadingMore: Boolean = false,
        val moreError: String? = null,
        /** the SoundCloud account, so the composer can ask for a sign-in instead of failing a send */
        val account: AccountState = AccountState.SignedOut,
        /** what is typed in the composer; kept here so a rotation or a failed send doesn't lose it */
        val draft: String = "",
        val posting: Boolean = false,
        val postError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0L
    private var trackId: String? = null

    /** [mediaId] is the player's id, so the SoundCloud prefix has to come off before the request. */
    fun open(mediaId: String, title: String) {
        val id = mediaId.removePrefix(SoundCloudModule.MediaIdPrefix)
        if (id == mediaId || id.isBlank()) return
        generation++
        job?.cancel()
        trackId = id
        _state.value = UiState(title = title, loading = true, account = moduleOrNull()?.accountState() ?: AccountState.SignedOut)
        load(id, offset = 0, token = generation, more = false)
    }

    fun loadMore() {
        val id = trackId ?: return
        val offset = _state.value.nextOffset ?: return
        if (_state.value.loading || _state.value.loadingMore) return
        val token = generation
        _state.update { it.copy(loadingMore = true, moreError = null) }
        load(id, offset, token, more = true)
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        trackId = null
        _state.value = UiState()
    }

    /** the module a sign-in sheet signs into, null when SoundCloud isn't loaded */
    fun signInModule(): MochyModule? = moduleOrNull()

    /** after a sign-in sheet closes, so the composer stops asking for one */
    fun refreshAccount() {
        val account = moduleOrNull()?.accountState() ?: AccountState.SignedOut
        _state.update { it.copy(account = account) }
    }

    fun onDraftChange(text: String) {
        _state.update { it.copy(draft = text, postError = null) }
    }

    /** posts what is in the composer as the signed-in account, and puts it on top of the list */
    fun post() {
        val id = trackId ?: return
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.posting) return
        val module = moduleOrNull() ?: return
        val account = module.accountState()
        if (account !is AccountState.SignedIn) {
            _state.update { it.copy(account = account, postError = "Đăng nhập SoundCloud để bình luận bằng tài khoản của bạn.") }
            return
        }
        val token = generation
        _state.update { it.copy(posting = true, postError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { module.postComment(id, text) } }
            if (token != generation) return@launch
            result.fold(
                onSuccess = { comment ->
                    _state.update { current ->
                        current.copy(
                            posting = false,
                            draft = "",
                            comments = listOf(comment) + current.comments.filterNot { it.id == comment.id },
                        )
                    }
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "Không gửi được bình luận. Thử lại sau."
                    _state.update { it.copy(posting = false, postError = message, account = module.accountState()) }
                },
            )
        }
    }

    private fun moduleOrNull(): SoundCloudModule? =
        runCatching { registry.module(SoundCloudModule.Id) as? SoundCloudModule }.getOrNull()

    private fun load(id: String, offset: Int, token: Long, more: Boolean) {
        job = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (!registry.isEnabled(SoundCloudModule.Id)) throw IllegalStateException("SoundCloud is disabled")
                    val module = registry.module(SoundCloudModule.Id) as? SoundCloudModule
                        ?: throw IllegalStateException("Nguồn SoundCloud chưa sẵn sàng. Hãy thử tải lại.")
                    module.commentsPage(id, offset = offset)
                }
            }
            if (token != generation || result.exceptionOrNull() is CancellationException) return@launch
            result.fold(
                onSuccess = { page ->
                    _state.update { current ->
                        if (more) {
                            val merged = (current.comments + page.comments).distinctBy { it.id }
                            current.copy(
                                comments = merged,
                                loadingMore = false,
                                nextOffset = page.nextOffset?.takeIf { it > offset && merged.size > current.comments.size },
                            )
                        } else {
                            current.copy(
                                comments = page.comments.distinctBy { it.id },
                                loading = false,
                                nextOffset = page.nextOffset?.takeIf { it > 0 },
                            )
                        }
                    }
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "Không thể tải bình luận lúc này."
                    _state.update { current ->
                        if (more) current.copy(loadingMore = false, moreError = message)
                        else current.copy(loading = false, error = message)
                    }
                },
            )
        }
    }
}
