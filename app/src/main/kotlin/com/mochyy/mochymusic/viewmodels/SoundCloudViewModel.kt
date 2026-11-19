package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.modules.ModuleRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** the SoundCloud screen's state: what was typed, what the catalog answered */
@HiltViewModel
class SoundCloudViewModel @Inject constructor(
    private val moduleRegistry: ModuleRegistry,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SoundCloudModule.Upload>>(emptyList())
    val results = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    /** the module that answers, when it's installed and loaded */
    private val module: SoundCloudModule?
        get() = moduleRegistry.all().filterIsInstance<SoundCloudModule>().firstOrNull()

    fun onQueryChange(text: String) {
        _query.value = text
    }

    fun search() {
        val text = _query.value.trim()
        val module = module ?: return
        if (text.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            _results.value = withContext(Dispatchers.IO) {
                runCatching { module.search(text) }.getOrDefault(emptyList())
            }
            _loading.value = false
        }
    }
}
