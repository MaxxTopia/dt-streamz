package com.dt.streamz.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dt.streamz.data.SearchResult
import com.dt.streamz.scraper.Provider
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Loaded(val results: List<SearchResult>) : SearchState
    data class Error(val message: String) : SearchState
}

class SearchViewModel(
    private val registry: ProviderRegistry,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var activeJob: Job? = null

    init {
        observeQuery()
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            _query
                .debounce(400)
                .filter { it.length >= 2 }
                .collect { run(it) }
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        if (q.isBlank()) {
            activeJob?.cancel()
            _state.value = SearchState.Idle
        }
    }

    fun onSubmit() {
        val q = _query.value.trim()
        if (q.length >= 2) run(q)
    }

    private fun run(q: String) {
        activeJob?.cancel()
        _state.value = SearchState.Loading
        activeJob = viewModelScope.launch {
            val providers: List<Provider> = registry.all
            val merged = mutableListOf<SearchResult>()
            for (p in providers) {
                runCatching { p.search(q) }
                    .onSuccess { merged.addAll(it) }
                    .onFailure { _state.value = SearchState.Error(it.message ?: "search failed") }
            }
            _state.value = SearchState.Loaded(merged)
        }
    }

    class Factory(private val registry: ProviderRegistry) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(registry) as T
    }
}
