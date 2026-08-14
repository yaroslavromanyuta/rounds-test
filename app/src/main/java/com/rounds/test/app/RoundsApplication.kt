package com.rounds.test.app

import android.app.Application
import com.rounds.imageloader.ImageLoader
import com.rounds.test.app.data.remote.HttpImagesRemoteDataSource
import com.rounds.test.app.data.repository.DefaultImagesRepository
import com.rounds.test.app.data.repository.ImagesRepository

/**
 * Composition root for the sample app.
 *
 * Dependencies are wired by hand — no DI framework is warranted for a single screen. The
 * [ImageLoader] is created once at application scope and handed to the components that need it,
 * which is also what its own documentation asks for: one instance per process, holding no Activity.
 */
class RoundsApplication : Application() {

    val imageLoader: ImageLoader by lazy { ImageLoader.create(this) }

    /**
     * Internal because nothing outside this module composes it, which also keeps the data layer's
     * types from leaking through a public API.
     */
    internal val imagesRepository: ImagesRepository by lazy {
        DefaultImagesRepository(HttpImagesRemoteDataSource())
    }
}
