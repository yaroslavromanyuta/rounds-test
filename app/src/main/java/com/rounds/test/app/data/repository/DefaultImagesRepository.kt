package com.rounds.test.app.data.repository

import com.rounds.test.app.data.remote.ImagesRemoteDataSource
import com.rounds.test.app.presentation.model.ImageItem

/**
 * Serves the image list from the network.
 *
 * The delegation is currently one-to-one: the endpoint's records already carry exactly the two
 * fields the screen needs, so there is nothing to map. This is the seam where caching, merging or a
 * local source would go, and where the ViewModel's dependency stops.
 */
internal class DefaultImagesRepository(
    private val remoteDataSource: ImagesRemoteDataSource,
) : ImagesRepository {

    /** Returns the endpoint's records in the order it supplied them, duplicates included. */
    override suspend fun getImages(): List<ImageItem> = remoteDataSource.getImages()
}
