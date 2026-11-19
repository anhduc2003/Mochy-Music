package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.modules.ModuleRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val findModule: () -> SoundCloudModule?,
) : ViewModel() {
    @Inject constructor(registry: ModuleRegistry) : this(
        { registry.isEnabled(SoundCloudModule.Id) },
        registry.observeEnabled(SoundCloudModule.Id),
        { registry.module(SoundCloudModule.Id) as? SoundCloudModule },
    )

    enum class Tab { Tracks, Playlists, Recommendations }

    data class UiState(
        val enabled: Boolean = false,
        val commentsFor: SoundCloudModule.Upload? = null,
        val comments: List<SoundCloudModule.Comment> = emptyList(),
        val commentsLoading: Boolean = false,
        val commentsError: String? = null,
        val query: String = "",
        val tab: Tab = Tab.Tracks,
        val region: SoundCloudModule.ChartRegion = SoundCloudModule.ChartRegion.Global,
        val recommendationSeed: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val uploads: List<SoundCloudModule.Upload> = emptyList(),
        val playlists: List<SoundCloudModule.Playlist> = emptyList(),
        val open: SoundCloudModule.Playlist? = null,
        val relatedTo: SoundCloudModule.Upload? = null,
    )

    private val module: SoundCloudModule?
        get() = if (enabledNow()) findModule() else null

    private val _state = MutableStateFlow(UiState(enabled = enabledNow()))
    val state = _state.asStateFlow()
    private var generation = 0L
    private var requestJob: Job? = null
    private var debounceJob: Job? = null
    private var beforeRelated: List<SoundCloudModule.Upload> = emptyList()
    private var commentsJob: Job? = null
    private var commentsGeneration = 0L

    init {
        viewModelScope.launch {
            enabledChanges.collect { enabled ->
                if (enabled != enabledNow()) return@collect
                val token = beginRequest()
                closeComments()
                beforeRelated = emptyList()
                _state.update {
                    it.copy(enabled = enabled, loading = false, error = null, uploads = emptyList(),
                        playlists = emptyList(), open = null, relatedTo = null)
                }
                if (enabled) {
                    if (_state.value.query.isNotBlank()) launchRequest(token) { searchNow(token) }
                    else refreshForCurrentTab(token)
                }
            }
        }
    }

    fun openComments(upload: SoundCloudModule.Upload) {
        val source = module ?: return
        closeComments()
        val token = commentsGeneration
        _state.update { it.copy(commentsFor = upload, commentsLoading = true) }
        commentsJob = viewModelScope.launch {
            val result = request { source.comments(upload.id) }
            if (token != commentsGeneration || !enabledNow()) return@launch
            _state.update {
                it.copy(comments = result.getOrDefault(emptyList()), commentsLoading = false,
                    commentsError = result.exceptionOrNull()?.let { error -> error.message ?: GenericFailure })
            }
        }
    }

    fun closeComments() {
        commentsGeneration++
        commentsJob?.cancel()
        commentsJob = null
        _state.update { it.copy(commentsFor = null, comments = emptyList(), commentsLoading = false, commentsError = null) }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text, loading = text.isNotBlank(), uploads = emptyList(), playlists = emptyList(), error = null, open = null, relatedTo = null) }
        val token = beginRequest()
        if (text.isBlank()) {
            _state.update { it.copy(error = null, open = null, relatedTo = null) }
            refreshForCurrentTab(token)
            return
        }
        debounceJob = viewModelScope.launch {
            delay(SearchDelayMs)
            launchRequest(token) { searchNow(token) }
        }
    }

    fun onTabChange(tab: Tab, currentTrackSeed: String? = null) {
        if (_state.value.tab == tab && currentTrackSeed == null) return
        val token = beginRequest()
        _state.update {
            it.copy(
                tab = tab,
                recommendationSeed = currentTrackSeed?.takeIf(String::isNotBlank) ?: it.recommendationSeed,
                open = null,
                relatedTo = null,
                error = null,
            )
        }
        if (_state.value.query.isNotBlank()) launchRequest(token) { searchNow(token) }
        else refreshForCurrentTab(token)
    }

    fun onRegionChange(region: SoundCloudModule.ChartRegion) {
        if (_state.value.region == region) return
        val token = beginRequest()
        _state.update { it.copy(region = region, error = null) }
        if (_state.value.open != null || _state.value.relatedTo != null) return
        if (_state.value.query.isNotBlank()) launchRequest(token) { searchNow(token) }
        else refreshForCurrentTab(token)
    }

    fun search() {
        val token = beginRequest()
        launchRequest(token) { searchNow(token) }
    }

    fun retry() {
        val current = _state.value
        when {
            current.open != null -> openPlaylist(current.open)
            current.relatedTo != null -> openRelated(current.relatedTo)
            current.query.isNotBlank() -> search()
            else -> refresh()
        }
    }

    fun refresh() {
        val token = beginRequest()
        _state.update { it.copy(error = null, open = null, relatedTo = null) }
        refreshForCurrentTab(token)
    }

    private fun refreshForCurrentTab(token: Long) {
        when (_state.value.tab) {
            Tab.Tracks -> launchRequest(token) { loadCharts(token) }
            Tab.Playlists -> _state.update { it.copy(loading = false, uploads = emptyList(), playlists = emptyList()) }
            Tab.Recommendations -> launchRequest(token) { loadRecommendations(token) }
        }
    }

    fun openPlaylist(playlist: SoundCloudModule.Playlist) {
        val source = module ?: return showUnavailable()
        val token = beginRequest()
        _state.update { it.copy(loading = true, error = null, open = playlist, relatedTo = null, playlists = emptyList()) }
        launchRequest(token) {
            val result = request { source.playlistTracks(playlist.id) }
            if (!isCurrent(token)) return@launchRequest
            result.fold(
                onSuccess = { tracks ->
                    if (tracks.isEmpty()) fail(token, IllegalStateException(NoResults))
                    else _state.update { it.copy(loading = false, uploads = tracks, error = null) }
                },
                onFailure = { fail(token, it) },
            )
        }
    }

    fun closePlaylist() {
        val token = beginRequest()
        _state.update { it.copy(open = null, uploads = emptyList(), playlists = emptyList(), error = null) }
        if (_state.value.query.isNotBlank()) launchRequest(token) { searchNow(token) }
        else refreshForCurrentTab(token)
    }

    fun openRelated(upload: SoundCloudModule.Upload) {
        val source = module ?: return showUnavailable()
        beforeRelated = _state.value.uploads
        val token = beginRequest()
        _state.update { it.copy(loading = true, error = null, relatedTo = upload, open = null, playlists = emptyList()) }
        launchRequest(token) {
            val result = request { source.related(upload.id) }
            if (!isCurrent(token)) return@launchRequest
            result.fold(
                onSuccess = { tracks ->
                    if (tracks.isEmpty()) fail(token, IllegalStateException(NoRecommendations))
                    else _state.update { it.copy(loading = false, uploads = tracks, error = null) }
                },
                onFailure = { fail(token, it) },
            )
        }
    }

    fun closeRelated() {
        beginRequest()
        _state.update { it.copy(relatedTo = null, uploads = beforeRelated, loading = false, error = null) }
        beforeRelated = emptyList()
    }

    private suspend fun searchNow(token: Long) {
        val source = module ?: return fail(token, IllegalStateException(ModuleUnavailable))
        val current = _state.value
        val query = current.query.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(loading = true, error = null, open = null, relatedTo = null) }
        when (current.tab) {
            Tab.Tracks -> request { source.search(query) }.applyTracks(token)
            Tab.Playlists -> {
                val result = request { source.searchPlaylists(query) }
                if (!isCurrent(token)) return
                result.fold(
                    onSuccess = { playlists ->
                        if (playlists.isEmpty()) fail(token, IllegalStateException(NoResults))
                        else _state.update { it.copy(loading = false, playlists = playlists, uploads = emptyList(), error = null) }
                    },
                    onFailure = { fail(token, it) },
                )
            }
            Tab.Recommendations -> request { source.recommendations(query, region = current.region) }.applyTracks(token)
        }
    }

    private suspend fun loadCharts(token: Long) {
        val source = module ?: return fail(token, IllegalStateException(ModuleUnavailable))
        val region = _state.value.region
        _state.update { it.copy(loading = true, error = null, open = null, relatedTo = null) }
        request { source.charts(region = region) }.applyTracks(token)
    }

    private suspend fun loadRecommendations(token: Long) {
        val source = module ?: return fail(token, IllegalStateException(ModuleUnavailable))
        val current = _state.value
        _state.update { it.copy(loading = true, error = null, open = null, relatedTo = null) }
        request { source.recommendations(current.recommendationSeed, region = current.region) }.applyTracks(token)
    }

    private suspend fun Result<List<SoundCloudModule.Upload>>.applyTracks(token: Long) {
        if (!isCurrent(token)) return
        fold(
            onSuccess = { tracks ->
                if (tracks.isEmpty()) fail(token, IllegalStateException(NoResults))
                else _state.update { it.copy(loading = false, uploads = tracks, playlists = emptyList(), error = null) }
            },
            onFailure = { fail(token, it) },
        )
    }

    private suspend fun <T> request(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!enabledNow()) throw CancellationException("SoundCloud is disabled")
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun beginRequest(): Long {
        generation++
        debounceJob?.cancel()
        debounceJob = null
        requestJob?.cancel()
        requestJob = null
        return generation
    }

    private fun launchRequest(token: Long, block: suspend () -> Unit) {
        requestJob = viewModelScope.launch {
            if (isCurrent(token)) block()
        }
    }

    private fun isCurrent(token: Long) = token == generation

    private fun fail(token: Long, error: Throwable) {
        if (!isCurrent(token)) return
        val message = error.message?.takeIf(String::isNotBlank) ?: GenericFailure
        _state.update { it.copy(loading = false, error = message, uploads = emptyList(), playlists = emptyList()) }
    }

    private fun showUnavailable() {
        _state.update { it.copy(loading = false, error = ModuleUnavailable) }
    }

    private companion object {
        const val SearchDelayMs = 450L
        const val NoResults = "Không tìm thấy kết quả. Thử từ khóa khác."
        const val NoRecommendations = "Chưa có gợi ý tương tự cho bài này."
        const val ModuleUnavailable = "Nguồn SoundCloud chưa sẵn sàng. Hãy thử tải lại."
        const val GenericFailure = "Không thể tải SoundCloud lúc này. Hãy thử lại."
    }
}
