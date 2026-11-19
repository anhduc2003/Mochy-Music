package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.modules.ModuleRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * the SoundCloud screen: what was typed, what the catalog answered, and the one playlist that is
 * open. Everything that talks to SoundCloud is blocking, so each call runs off the main thread.
 */
@HiltViewModel
class SoundCloudViewModel @Inject constructor(
    private val moduleRegistry: ModuleRegistry,
) : ViewModel() {

    enum class Tab { Tracks, Playlists }

    data class UiState(
        val query: String = "",
        val tab: Tab = Tab.Tracks,
        val loading: Boolean = false,
        val uploads: List<SoundCloudModule.Upload> = emptyList(),
        val playlists: List<SoundCloudModule.Playlist> = emptyList(),
        /** the playlist being looked at; null while browsing or searching */
        val open: SoundCloudModule.Playlist? = null,
        /** the upload whose related tracks are listed; null in every other view */
        val relatedTo: SoundCloudModule.Upload? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** the module that answers, when it's installed and loaded */
    private val module: SoundCloudModule?
        get() = moduleRegistry.all().filterIsInstance<SoundCloudModule>().firstOrNull()

    init {
        loadTrending()
    }

    fun onQueryChange(text: String) = _state.update { it.copy(query = text) }

    fun onTabChange(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun search() {
        val current = _state.value
        val text = current.query.trim()
        val module = module ?: return
        if (text.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, open = null) }
            if (current.tab == Tab.Tracks) {
                val found = withContext(Dispatchers.IO) {
                    runCatching { module.search(text) }.getOrDefault(emptyList())
                }
                _state.update { it.copy(loading = false, uploads = found, playlists = emptyList()) }
            } else {
                val found = withContext(Dispatchers.IO) {
                    runCatching { module.searchPlaylists(text) }.getOrDefault(emptyList())
                }
                _state.update { it.copy(loading = false, playlists = found, uploads = emptyList()) }
            }
        }
    }

    fun openPlaylist(playlist: SoundCloudModule.Playlist) {
        val module = module ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, open = playlist, playlists = emptyList()) }
            val tracks = withContext(Dispatchers.IO) {
                runCatching { module.playlistTracks(playlist.id) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(loading = false, uploads = tracks) }
        }
    }

    /** what the screen showed before related tracks replaced it, so backing out restores it */
    private var beforeRelated: List<SoundCloudModule.Upload> = emptyList()

    fun openRelated(upload: SoundCloudModule.Upload) {
        val module = module ?: return
        beforeRelated = _state.value.uploads
        viewModelScope.launch {
            _state.update { it.copy(loading = true, relatedTo = upload, open = null, playlists = emptyList()) }
            val tracks = withContext(Dispatchers.IO) {
                runCatching { module.related(upload.id) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(loading = false, uploads = tracks) }
        }
    }

    fun closeRelated() {
        _state.update { it.copy(relatedTo = null, uploads = beforeRelated) }
        beforeRelated = emptyList()
    }

    /** back out of a playlist to what the screen showed before: the charts, or the last search */
    fun closePlaylist() {
        _state.update { it.copy(open = null, uploads = emptyList()) }
        loadTrending()
    }

    private fun loadTrending() {
        val module = module ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val found = withContext(Dispatchers.IO) {
                runCatching { module.charts() }.getOrDefault(emptyList())
            }
            _state.update {
                if (it.open == null && it.query.isBlank() && it.uploads.isEmpty()) {
                    it.copy(loading = false, uploads = found)
                } else {
                    it.copy(loading = false)
                }
            }
        }
    }
}
