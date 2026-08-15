package com.rounds.test.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rounds.test.app.R
import com.rounds.test.app.data.repository.ImagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the image list for the screen: it starts a fetch when it is created, and exposes the result
 * as [ImagesUiState].
 *
 * It knows nothing about how the list arrives — no connection, no JSON, no [android.content.Context]
 * — and nothing about how the images themselves are downloaded, which stays with the image loader
 * at binding time.
 */
internal class ImagesViewModel(
    private val repository: ImagesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ImagesUiState>(ImagesUiState.Loading)
    val state: StateFlow<ImagesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload()
    }

    /**
     * Fetches the list again, replacing any fetch still in flight — latest request wins, which is
     * what a retry button and a pull-to-refresh both want. The state is moved to
     * [ImagesUiState.Loading] before this returns, so a caller never observes the previous result
     * after asking for a new one.
     */
    fun reload() {
        loadJob?.cancel()
        _state.value = ImagesUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val items = repository.getImages()
                // A superseded fetch may already have been resumed with its result; without this
                // check a slow first request could still overwrite a newer one.
                ensureActive()
                _state.value = if (items.isEmpty()) {
                    ImagesUiState.Empty
                } else {
                    ImagesUiState.Content(items)
                }
            } catch (cancellation: CancellationException) {
                // Being replaced, or the ViewModel being cleared, is normal — not a failure to show.
                throw cancellation
            } catch (failure: Exception) {
                // The resource id, not the exception's text: raw transport messages are neither
                // translatable nor safe to show, and resolving one here would need a Context.
                _state.value = ImagesUiState.Error(R.string.images_error_message)
            }
        }
    }

    companion object {

        /**
         * The screen has no way to build the repository itself, and one dependency does not warrant
         * a dedicated factory class.
         */
        fun factory(repository: ImagesRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImagesViewModel(repository) }
        }
    }
}
