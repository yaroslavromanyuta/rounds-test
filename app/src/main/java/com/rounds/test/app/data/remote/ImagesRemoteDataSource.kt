package com.rounds.test.app.data.remote

import com.rounds.test.app.presentation.model.ImageItem

/**
 * Retrieves the supplied image list. Implementations do their own thread confinement, so callers
 * may invoke [getImages] from any coroutine context, including the main one.
 *
 * Failures are reported as exceptions — [java.io.IOException] for transport and status problems,
 * [com.rounds.test.app.data.remote.parser.ImageListParseException] for an unusable body.
 */
internal interface ImagesRemoteDataSource {

    suspend fun getImages(): List<ImageItem>
}
