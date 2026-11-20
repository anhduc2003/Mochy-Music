package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.modules.ModuleRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** SoundCloud browsing state. Blocking module HTTP calls never run on the UI thread. */
@HiltViewModel
class SoundCloudViewModel internal constructor(
    private val enabledNow: () -> Boolean,
    enabledChanges: Flow<Boolean>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val findModule: () -> SoundCloudModule?,
) : ViewModel() {
    @Inject constructor(registry: ModuleRegistry) : this(
        { registry.isEnabled(SoundCloudModule.Id) },
        registry.observeEnabled(SoundCloudModule.Id),
        findModule = { registry.module(SoundCloudModule.Id) as? SoundCloudModule },
    )

    enum class Tab { Tracks, Playlists, Recommendations }

    data class UiState(
        val enabled: Boolean = false,
        val commentsFor: SoundCloudModule.Upload? = null,
        val comments: List<SoundCloudModule.Comment> = emptyList(),
        val commentsLoading: Boolean = false,
        val commentsError: String? = null,
        val commentsNextOffset: Int? = null,
        val commentsLoadingMore: Boolean = false,
        val commentsMoreError: String? = null,
        val query: String = "",
        val tab: Tab = Tab.Tracks,
        val recommendationSeed: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val uploads: List<SoundCloudModule.Upload> = emptyList(),
        val playlists: List<SoundCloudModule.Playlist> = emptyList(),
        val open: SoundCloudModule.Playlist? = null,
        val relatedTo: SoundCloudModule.Upload? = null,
    )

    private val _state = MutableStateFlow(UiState(enabled = enabledNow()))
    val state = _state.asStateFlow()
    private var generation = 0L
    private var browseJob: Job? = null
    private val backStack = ArrayDeque<UiState>()
    private var commentsJob: Job? = null
    private var commentsGeneration = 0L

    init {
        viewModelScope.launch {
            enabledChanges.collect { enabled ->
                if (enabled != enabledNow()) return@collect
                cancelBrowse()
                closeComments()
                backStack.clear()
                _state.update {
                    it.copy(enabled = enabled, loading = false, error = null, uploads = emptyList(),
                        playlists = emptyList(), open = null, relatedTo = null)
                }
                if (enabled) refresh()
            }
        }
    }

    fun openComments(upload: SoundCloudModule.Upload) {
        if (!canBrowse()) return
        closeComments()
        val token = commentsGeneration
        _state.update { it.copy(commentsFor = upload, commentsLoading = true) }
        commentsJob = viewModelScope.launch {
            val result = request { it.commentsPage(upload.id) }
            if (!isCurrentComments(token, upload.id)) return@launch
            result.fold(
                onSuccess = { page -> _state.update {
                    it.copy(comments = page.comments.distinctBy { comment -> comment.id },
                        commentsLoading = false, commentsNextOffset = page.nextOffset?.takeIf { offset -> offset > 0 })
                } },
                onFailure = { error -> _state.update {
                    it.copy(commentsLoading = false, commentsError = error.displayMessage())
                } },
            )
        }
    }

    fun loadMoreComments() {
        if (!canBrowse()) return
        val current = _state.value
        val upload = current.commentsFor ?: return
        val offset = current.commentsNextOffset ?: return
        if (current.commentsLoading || current.commentsLoadingMore) return
        val token = commentsGeneration
        _state.update { it.copy(commentsLoadingMore = true, commentsMoreError = null) }
        commentsJob = viewModelScope.launch {
            val result = request { it.commentsPage(upload.id, offset = offset) }
            if (!isCurrentComments(token, upload.id)) return@launch
            result.fold(
                onSuccess = { page -> _state.update {
                    val merged = (it.comments + page.comments).distinctBy { comment -> comment.id }
                    it.copy(comments = merged, commentsLoadingMore = false,
                        commentsNextOffset = page.nextOffset?.takeIf { next -> next > offset && merged.size > it.comments.size })
                } },
                onFailure = { error -> _state.update {
                    it.copy(commentsLoadingMore = false, commentsMoreError = error.displayMessage())
                } },
            )
        }
    }

    fun closeComments() {
        commentsGeneration++
        commentsJob?.cancel()
        commentsJob = null
        _state.update { it.withoutComments() }
    }

    fun onQueryChange(text: String) {
        if (!canBrowse() || _state.value.query == text) return
        browseRoot(_state.value.copy(query = text), if (text.isBlank()) 0L else SearchDelayMs)
    }

    fun onTabChange(tab: Tab, currentTrackSeed: String? = null) {
        if (!canBrowse()) return
        val current = _state.value
        val seed = if (tab == Tab.Recommendations) currentTrackSeed?.takeIf(String::isNotBlank) else current.recommendationSeed
        if (current.tab == tab && current.recommendationSeed == seed) return
        browseRoot(current.copy(tab = tab, recommendationSeed = seed))
    }

    fun search() = refresh()

    fun retry() = refresh()

    fun refresh() {
        if (canBrowse()) loadBrowse(_state.value)
    }

    fun openPlaylist(playlist: SoundCloudModule.Playlist) {
        if (!canBrowse()) return
        val current = _state.value
        if (current.open?.id == playlist.id) {
            refresh()
            return
        }
        pushBrowse(current)
        loadBrowse(current.emptyResults().copy(open = playlist, relatedTo = null))
    }

    fun closePlaylist() {
        if (canBrowse() && _state.value.open != null) popBrowse()
    }

    fun openRelated(upload: SoundCloudModule.Upload) {
        if (!canBrowse()) return
        val current = _state.value
        if (current.relatedTo?.id == upload.id) {
            refresh()
            return
        }
        pushBrowse(current)
        loadBrowse(current.emptyResults().copy(open = null, relatedTo = upload))
    }

    fun closeRelated() {
        if (canBrowse() && _state.value.relatedTo != null) popBrowse()
    }

    private fun browseRoot(state: UiState, debounceMs: Long = 0L) {
        backStack.clear()
        closeComments()
        loadBrowse(state.withoutComments().emptyResults().copy(open = null, relatedTo = null), debounceMs)
    }

    private fun pushBrowse(current: UiState) {
        backStack.addLast(current.withoutComments())
        closeComments()
    }

    private fun popBrowse() {
        val previous = backStack.removeLastOrNull() ?: return
        closeComments()
        // A pending request was canceled when entering its child, so resume it when returning.
        if (previous.loading) loadBrowse(previous) else {
            cancelBrowse()
            _state.value = previous
        }
    }

    private fun loadBrowse(target: UiState, debounceMs: Long = 0L) {
        val token = cancelBrowse()
        val query = target.query.trim()
        if (target.open == null && target.relatedTo == null && target.tab == Tab.Playlists && query.isEmpty()) {
            _state.value = target.emptyResults().copy(loading = false)
            return
        }
        _state.value = target.copy(loading = true, error = null)
        browseJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            if (!isCurrentBrowse(token)) return@launch
            val playlist = target.open
            val related = target.relatedTo
            if (playlist == null && related == null && target.tab == Tab.Playlists) {
                val result = request { it.searchPlaylists(query) }
                if (!isCurrentBrowse(token)) return@launch
                result.fold(
                    onSuccess = { playlists -> _state.update {
                        it.copy(loading = false, playlists = playlists.distinctBy { playlist -> playlist.id },
                            uploads = emptyList(), error = NoResults.takeIf { playlists.isEmpty() })
                    } },
                    onFailure = { fail(token, it) },
                )
            } else {
                val result = request { source ->
                    when {
                        playlist != null -> source.playlistTracks(playlist.id)
                        related != null -> source.vietnameseRelated(related.id)
                        target.tab == Tab.Recommendations -> source.recommendations(
                            query.takeIf(String::isNotEmpty) ?: target.recommendationSeed,
                        )
                        query.isNotEmpty() -> source.vietnameseSearch(query)
                        else -> source.vietnameseDiscovery()
                    }
                }
                if (!isCurrentBrowse(token)) return@launch
                result.fold(
                    onSuccess = { tracks -> _state.update {
                        it.copy(loading = false, uploads = tracks, playlists = emptyList(),
                            error = if (tracks.isEmpty()) {
                                if (related != null) NoRecommendations else NoResults
                            } else null)
                    } },
                    onFailure = { fail(token, it) },
                )
            }
        }
    }

    private suspend fun <T> request(block: (SoundCloudModule) -> T): Result<T> = withContext(ioDispatcher) {
        if (!enabledNow()) throw CancellationException("SoundCloud is disabled")
        try {
            val source = findModule() ?: return@withContext Result.failure(IllegalStateException(ModuleUnavailable))
            if (!enabledNow()) throw CancellationException("SoundCloud is disabled")
            Result.success(block(source))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun cancelBrowse(): Long {
        generation++
        browseJob?.cancel()
        browseJob = null
        return generation
    }

    private fun canBrowse() = _state.value.enabled && enabledNow()
    private fun isCurrentBrowse(token: Long) = token == generation && canBrowse()
    private fun isCurrentComments(token: Long, id: String) =
        token == commentsGeneration && canBrowse() && _state.value.commentsFor?.id == id

    private fun fail(token: Long, error: Throwable) {
        if (isCurrentBrowse(token)) _state.update { it.copy(loading = false, error = error.displayMessage()) }
    }

    private fun UiState.emptyResults() = copy(uploads = emptyList(), playlists = emptyList(), error = null)
    private fun UiState.withoutComments() = copy(
        commentsFor = null, comments = emptyList(), commentsLoading = false, commentsError = null,
        commentsNextOffset = null, commentsLoadingMore = false, commentsMoreError = null,
    )

    private fun Throwable.displayMessage() = message?.takeIf(String::isNotBlank) ?: GenericFailure

    private companion object {
        const val SearchDelayMs = 450L
        const val NoResults = "Không tìm thấy kết quả. Thử từ khóa khác."
        const val NoRecommendations = "Chưa có gợi ý tương tự cho bài này."
        const val ModuleUnavailable = "Nguồn SoundCloud chưa sẵn sàng. Hãy thử tải lại."
        const val GenericFailure = "Không thể tải SoundCloud lúc này. Hãy thử lại."
    }
}
