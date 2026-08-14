package com.rounds.test.app.presentation.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.rounds.test.app.R
import com.rounds.test.app.RoundsApplication
import com.rounds.test.app.databinding.ActivityMainBinding
import com.rounds.test.app.presentation.viewmodel.ImagesUiState
import com.rounds.test.app.presentation.viewmodel.ImagesViewModel
import kotlinx.coroutines.launch

/**
 * The image list screen.
 *
 * It renders whatever [ImagesViewModel] publishes and knows nothing about where the list comes
 * from — no repository, no data source, no HTTP, no JSON. Its only other collaborator is the
 * application-scoped [com.rounds.imageloader.ImageLoader], which it hands to the adapter.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: ImagesViewModel by viewModels {
        ImagesViewModel.factory((application as RoundsApplication).imagesRepository)
    }

    /** One loader per process, created in the composition root — never one per screen or per row. */
    private val imagesAdapter by lazy {
        ImagesAdapter((application as RoundsApplication).imageLoader)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.images.layoutManager = LinearLayoutManager(this)
        binding.images.adapter = imagesAdapter

        binding.retry.setOnClickListener { viewModel.reload() }
        binding.clearCache.setOnClickListener { clearImageCache() }

        observeState()
    }

    /**
     * Collection is tied to the lifecycle: it stops when the screen goes below STARTED and resumes
     * with the current state afterwards, which is also what restores the list after a rotation.
     */
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: ImagesUiState) {
        binding.progress.isVisible = state is ImagesUiState.Loading
        binding.images.isVisible = state is ImagesUiState.Content
        binding.empty.isVisible = state is ImagesUiState.Empty
        binding.error.isVisible = state is ImagesUiState.Error

        // Clearing the image cache only demonstrates anything when there are rows to reload.
        binding.clearCache.isEnabled = state is ImagesUiState.Content

        when (state) {
            is ImagesUiState.Content -> imagesAdapter.submitList(state.items)
            is ImagesUiState.Error -> binding.errorMessage.text = state.message
            ImagesUiState.Loading, ImagesUiState.Empty -> Unit
        }
    }

    /**
     * Discards the cached images and asks the visible rows to load again, so the invalidation is
     * something the user can actually see. It deliberately does not reload the list itself: the
     * JSON came from a different place and did not become stale.
     */
    private fun clearImageCache() {
        (application as RoundsApplication).imageLoader.clearCache()
        imagesAdapter.refreshImages()
        Snackbar.make(binding.root, R.string.image_cache_cleared, Snackbar.LENGTH_SHORT).show()
    }
}
