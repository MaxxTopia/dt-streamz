package com.dt.streamz.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailsState {
    data object Loading : DetailsState
    data class Loaded(val details: TitleDetails) : DetailsState
    data class Error(val message: String) : DetailsState
}

class DetailsViewModel(
    private val registry: ProviderRegistry,
    private val providerId: String,
    private val titleId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailsState>(DetailsState.Loading)
    val state: StateFlow<DetailsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = DetailsState.Loading
        viewModelScope.launch {
            runCatching { registry.get(providerId).details(titleId) }
                .onSuccess { _state.value = DetailsState.Loaded(it) }
                .onFailure { _state.value = DetailsState.Error(it.message ?: "load failed") }
        }
    }

    class Factory(
        private val registry: ProviderRegistry,
        private val providerId: String,
        private val titleId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailsViewModel(registry, providerId, titleId) as T
    }
}
