package com.rounds.test.app.data.repository

import com.rounds.test.app.data.remote.ImagesRemoteDataSource
import com.rounds.test.app.model.ImageItem
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The repository is a delegation today, so only its two observable promises are worth asserting:
 * the endpoint's order survives, and a failure is not turned into an empty list.
 */
class DefaultImagesRepositoryTest {

    @Test
    fun `serves the remote records in order`() = runTest {
        val images = listOf(
            ImageItem(7, "https://example.test/seven.jpg"),
            ImageItem(0, "https://example.test/zero.jpg"),
        )
        val repository = DefaultImagesRepository(FakeRemoteDataSource(result = Result.success(images)))

        assertEquals(images, repository.getImages())
    }

    @Test
    fun `lets a remote failure through instead of hiding it`() = runTest {
        val failure = IOException("offline")
        val repository = DefaultImagesRepository(FakeRemoteDataSource(result = Result.failure(failure)))

        val thrown = runCatching { repository.getImages() }.exceptionOrNull()

        assertSame(failure, thrown)
    }

    private class FakeRemoteDataSource(
        private val result: Result<List<ImageItem>>,
    ) : ImagesRemoteDataSource {

        override suspend fun getImages(): List<ImageItem> = result.getOrThrow()
    }
}
