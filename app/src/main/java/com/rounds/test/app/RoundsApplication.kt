package com.rounds.test.app

import android.app.Application
import com.rounds.imageloader.ImageLoader

/**
 * Composition root for the sample app.
 *
 * Dependencies are wired by hand — no DI framework is warranted for a single screen. The
 * [ImageLoader] is created once at application scope and handed to the components that need it,
 * which is also what its own documentation asks for: one instance per process, holding no Activity.
 */
class RoundsApplication : Application() {

    val imageLoader: ImageLoader by lazy { ImageLoader.create() }
}
