package com.rounds.test.app.presentation.viewmodel

import com.rounds.test.app.R
import com.rounds.test.app.model.ImageItem
import com.rounds.test.app.testing.FakeImagesRepository
import com.rounds.test.app.testing.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Every fetch is released by hand through [FakeImagesRepository], and the ViewModel's own coroutines
 * run on the same [StandardTestDispatcher] the test advances, so ordering is decided by the test
 * rather than by timing. No delays, no sleeps.
 *
 * The ViewModel is built inside each test, not in a field: `viewModelScope` touches the main
 * dispatcher on construction, and the rule that installs it has not run yet at field-init time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImagesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val repository = FakeImagesRepository()

    @Test
    fun `starts loading and shows the fetched records`() = runTest(dispatcher) {
        val viewModel = ImagesViewModel(repository)
        assertEquals(ImagesUiState.Loading, viewModel.state.value)

        advanceUntilIdle()
        assertEquals(ImagesUiState.Loading, viewModel.state.value)

        repository.succeed(0, IMAGES)
        advanceUntilIdle()

        assertEquals(ImagesUiState.Content(IMAGES), viewModel.state.value)
    }

    @Test
    fun `reports an empty response as empty rather than content`() = runTest(dispatcher) {
        val viewModel = ImagesViewModel(repository)
        advanceUntilIdle()

        repository.succeed(0, emptyList())
        advanceUntilIdle()

        assertEquals(ImagesUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `turns a failure into a presentation-safe error`() = runTest(dispatcher) {
        val viewModel = ImagesViewModel(repository)
        advanceUntilIdle()

        repository.fail(0, IOException("Unexpected HTTP status 500 for https://internal.test/x"))
        advanceUntilIdle()

        // Neither the exception's message nor its type reaches the state - only a fixed resource id.
        assertEquals(ImagesUiState.Error(R.string.images_error_message), viewModel.state.value)
    }

    @Test
    fun `reload retries after an error`() = runTest(dispatcher) {
        val viewModel = ImagesViewModel(repository)
        advanceUntilIdle()
        repository.fail(0, IOException("offline"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value is ImagesUiState.Error)

        viewModel.reload()
        assertEquals(ImagesUiState.Loading, viewModel.state.value)

        advanceUntilIdle()
        repository.succeed(1, IMAGES)
        advanceUntilIdle()

        assertEquals(ImagesUiState.Content(IMAGES), viewModel.state.value)
    }

    @Test
    fun `a superseded fetch cannot overwrite a newer one`() = runTest(dispatcher) {
        val viewModel = ImagesViewModel(repository)
        val observed = observeStates(viewModel)
        advanceUntilIdle()

        viewModel.reload()
        // The first fetch finishes late, after the reload that replaced it.
        repository.succeed(0, STALE_IMAGES)
        advanceUntilIdle()
        repository.succeed(1, IMAGES)
        advanceUntilIdle()

        assertEquals(2, repository.callCount)
        assertEquals(ImagesUiState.Content(IMAGES), viewModel.state.value)
        assertFalse(observed.contains(ImagesUiState.Content(STALE_IMAGES)))
    }

    @Test
    fun `cancellation is not an error`() = runTest(dispatcher) {
        val viewModel = ImagesViewModel(repository)
        val observed = observeStates(viewModel)
        advanceUntilIdle()

        repository.fail(0, CancellationException("scope cleared"))
        advanceUntilIdle()

        assertEquals(ImagesUiState.Loading, viewModel.state.value)
        assertTrue(observed.none { it is ImagesUiState.Error })
    }

    private fun TestScope.observeStates(viewModel: ImagesViewModel): List<ImagesUiState> {
        val observed = mutableListOf<ImagesUiState>()
        backgroundScope.launch(dispatcher) { viewModel.state.toList(observed) }
        return observed
    }

    private companion object {
        private val IMAGES = listOf(
            ImageItem(7, "https://example.test/seven.jpg"),
            ImageItem(0, "https://example.test/zero.jpg"),
        )
        private val STALE_IMAGES = listOf(ImageItem(99, "https://example.test/stale.jpg"))
    }
}
