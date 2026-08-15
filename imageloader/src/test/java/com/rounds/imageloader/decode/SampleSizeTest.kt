package com.rounds.imageloader.decode

import com.rounds.imageloader.decode.BitmapFactoryImageDecoder.Companion.DEFAULT_MAX_DIMENSION_PX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subsampling bound exists to stop an oversized image from crashing the host application:
 * a bitmap over 100 MB throws `Canvas: trying to draw too large bitmap` from `ImageView.onDraw`,
 * on the UI thread, where the library cannot catch it.
 *
 * `BitmapFactory` is a stub in JVM unit tests, so the arithmetic that decides the bound is a plain
 * function and is asserted directly here rather than inferred from a decode.
 */
class SampleSizeTest {

    @Test
    fun `an image already within the bound is decoded at full size`() {
        assertEquals(1, sampleSizeFor(1920, 1080, DEFAULT_MAX_DIMENSION_PX))
    }

    @Test
    fun `an image exactly at the bound is decoded at full size`() {
        assertEquals(1, sampleSizeFor(2048, 2048, DEFAULT_MAX_DIMENSION_PX))
    }

    @Test
    fun `one pixel over the bound is halved`() {
        assertEquals(2, sampleSizeFor(2049, 2048, DEFAULT_MAX_DIMENSION_PX))
    }

    @Test
    fun `the bound applies to the taller edge as well as the wider one`() {
        assertEquals(2, sampleSizeFor(100, 4096, DEFAULT_MAX_DIMENSION_PX))
    }

    @Test
    fun `sample size is always a power of two`() {
        val sampleSizes = listOf(2049, 4097, 8193, 11000, 40000)
            .map { sampleSizeFor(it, it, DEFAULT_MAX_DIMENSION_PX) }
        sampleSizes.forEach { size ->
            assertTrue("$size is not a power of two", size > 0 && size and (size - 1) == 0)
        }
    }

    /**
     * The record that actually crashes the sample app without this bound: id 47 of the supplied
     * image list is 11000x7000, which is 308 MB decoded at ARGB_8888.
     */
    @Test
    fun `the oversized record in the supplied payload is brought under the draw limit`() {
        val sampleSize = sampleSizeFor(11000, 7000, DEFAULT_MAX_DIMENSION_PX)

        val decodedBytes = (11000L / sampleSize) * (7000L / sampleSize) * BYTES_PER_PIXEL
        assertTrue(
            "11000x7000 decodes to $decodedBytes bytes, which Canvas refuses to draw",
            decodedBytes < CANVAS_DRAW_LIMIT_BYTES,
        )
    }

    @Test
    fun `every bounded image stays under the draw limit`() {
        val dimensions = listOf(1 to 1, 1920 to 1080, 2048 to 2048, 11000 to 7000, 30000 to 30000)

        dimensions.forEach { (width, height) ->
            val sampleSize = sampleSizeFor(width, height, DEFAULT_MAX_DIMENSION_PX)
            val decodedBytes = (width / sampleSize).toLong() *
                (height / sampleSize).toLong() * BYTES_PER_PIXEL
            assertTrue(
                "${width}x$height decodes to $decodedBytes bytes",
                decodedBytes < CANVAS_DRAW_LIMIT_BYTES,
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive bound is rejected rather than looping forever`() {
        sampleSizeFor(1920, 1080, maxDimensionPx = 0)
    }

    private companion object {
        private const val BYTES_PER_PIXEL = 4L

        /** `RecordingCanvas.throwIfCannotDraw` rejects anything at or above 100 MB. */
        private const val CANVAS_DRAW_LIMIT_BYTES = 100L * 1024L * 1024L
    }
}
