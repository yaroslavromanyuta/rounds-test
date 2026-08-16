package com.rounds.test.app.testing

import com.rounds.test.app.data.repository.ImagesRepository
import com.rounds.test.app.model.ImageItem
import kotlinx.coroutines.CompletableDeferred

/**
 * A repository whose every call hangs until the test releases it, by index.
 *
 * That is what makes overlapping fetches provable without a single delay: a test can start two
 * loads and then finish them in whichever order it wants to exercise.
 */
internal class FakeImagesRepository : ImagesRepository {

    private val calls = mutableListOf<CompletableDeferred<List<ImageItem>>>()

    val callCount: Int get() = calls.size

    override suspend fun getImages(): List<ImageItem> {
        val call = CompletableDeferred<List<ImageItem>>()
        calls += call
        return call.await()
    }

    fun succeed(callIndex: Int, items: List<ImageItem>) {
        calls[callIndex].complete(items)
    }

    fun fail(callIndex: Int, failure: Throwable) {
        calls[callIndex].completeExceptionally(failure)
    }
}
