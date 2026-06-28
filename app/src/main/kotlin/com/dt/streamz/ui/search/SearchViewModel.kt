package com.dt.streamz.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.scraper.Provider
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    // Scopes results to one tab's content. The global Search tab passes the
    // default (everything); the Anime/Movies/TV tabs pass their MediaKind so a
    // search on those tabs returns only that kind — no movies under Anime and
    // vice-versa.
    private val kindFilter: (MediaKind) -> Boolean = { true },
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
            // Query every provider in parallel — serial was N round-trips of
            // latency stacked end to end. A provider that throws contributes
            // nothing rather than failing the whole search.
            val outcomes = providers
                .map { p -> async { runCatching { p.search(q) } } }
                .awaitAll()
            val merged = outcomes.mapNotNull { it.getOrNull() }.flatten()
                .filter { kindFilter(it.kind) }
            // Distinguish "nobody had a match" (Loaded, empty -> "No results")
            // from "every source errored" (Error -> tells the user it's a
            // connection problem, not an empty catalog).
            val allFailed = providers.isNotEmpty() && outcomes.all { it.isFailure }
            _state.value = if (allFailed) {
                SearchState.Error("couldn't reach any source — check the box's connection")
            } else {
                SearchState.Loaded(merged)
            }
        }
    }

    class Factory(
        private val registry: ProviderRegistry,
        private val kindFilter: (MediaKind) -> Boolean = { true },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(registry, kindFilter) as T
    }
}
