package com.rounds.test.app.presentation.viewmodel

import androidx.annotation.StringRes
import com.rounds.test.app.presentation.model.ImageItem

/**
 * Everything the image-list screen needs to render itself, and nothing else — no exceptions, no
 * status codes, no Android types. Exactly one of these is current at any moment.
 */
internal sealed interface ImagesUiState {

    /** A fetch is in flight. Also the state the screen starts in. */
    data object Loading : ImagesUiState

    /** The fetch succeeded and returned at least one record, in endpoint order. */
    data class Content(val items: List<ImageItem>) : ImagesUiState

    /** The fetch succeeded but the endpoint returned no records. Not an error. */
    data object Empty : ImagesUiState

    /**
     * The fetch failed. [messageRes] is presentation-safe by construction — the ViewModel emits a
     * fixed string resource, never an exception's text or class name. A resource id rather than a
     * resolved string keeps the ViewModel free of [android.content.Context] and leaves the wording
     * translatable.
     */
    data class Error(@param:StringRes val messageRes: Int) : ImagesUiState
}
