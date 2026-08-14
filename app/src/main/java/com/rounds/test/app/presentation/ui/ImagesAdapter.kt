package com.rounds.test.app.presentation.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rounds.imageloader.ImageLoader
import com.rounds.test.app.R
import com.rounds.test.app.databinding.ItemImageBinding
import com.rounds.test.app.presentation.model.ImageItem

/**
 * Renders the image list. Each row shows the item's id and the image the library loads for it.
 *
 * The adapter is given the application-scoped [ImageLoader] and nothing else — no Activity,
 * ViewModel or repository — so it cannot leak a screen and cannot reach around the library. It
 * never downloads, decodes or caches anything itself: that is the whole point of the exercise.
 */
internal class ImagesAdapter(
    private val imageLoader: ImageLoader,
) : ListAdapter<ImageItem, ImagesAdapter.ImageViewHolder>(ImagesDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder =
        ImageViewHolder(ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = getItem(position)
        val binding = holder.binding
        binding.imageId.text = binding.root.context.getString(R.string.image_id_format, item.id)
        // The placeholder is visible from here until the bitmap arrives, and stays if it never
        // does. Binding a new url also cancels this view's previous request — the loader owns that
        // lifecycle, so the adapter keeps no request tokens of its own.
        imageLoader.load(item.imageUrl, R.drawable.image_placeholder, binding.image)
    }

    /**
     * A recycled row's image is no longer wanted: cancel its request so a late result cannot be
     * painted onto the row's next item, and so a scrolled-past download stops being awaited.
     */
    override fun onViewRecycled(holder: ImageViewHolder) {
        imageLoader.clear(holder.binding.image)
        super.onViewRecycled(holder)
    }

    /**
     * Rebinds every visible row so their images are requested again. Only for the explicit
     * cache-invalidation action — ordinary list updates go through `submitList` and [DiffUtil],
     * which is why this is not `notifyDataSetChanged`.
     */
    fun refreshImages() {
        notifyItemRangeChanged(0, itemCount)
    }

    internal class ImageViewHolder(
        val binding: ItemImageBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}

/**
 * Identity is the `id`, never the url: the endpoint repeats several urls under different ids, so
 * comparing urls would make distinct rows look like the same item to [DiffUtil].
 */
internal object ImagesDiffCallback : DiffUtil.ItemCallback<ImageItem>() {

    override fun areItemsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean =
        oldItem == newItem
}
