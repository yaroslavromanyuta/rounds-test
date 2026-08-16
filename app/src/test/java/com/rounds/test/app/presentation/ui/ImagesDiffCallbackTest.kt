package com.rounds.test.app.presentation.ui

import com.rounds.test.app.model.ImageItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * List identity is the one place the payload can catch a reasonable-looking implementation out: the
 * endpoint repeats several image urls under different ids, so comparing urls would make two real
 * rows look like one item.
 */
class ImagesDiffCallbackTest {

    @Test
    fun `the same record is the same item`() {
        val item = ImageItem(7, "https://example.test/seven.jpg")

        assertTrue(ImagesDiffCallback.areItemsTheSame(item, item.copy()))
        assertTrue(ImagesDiffCallback.areContentsTheSame(item, item.copy()))
    }

    @Test
    fun `two ids sharing one url are different items`() {
        // Ids 17 and 18 of the live payload both point at 135345870.jpg.
        val seventeen = ImageItem(17, "https://example.test/same.jpg")
        val eighteen = ImageItem(18, "https://example.test/same.jpg")

        assertFalse(ImagesDiffCallback.areItemsTheSame(seventeen, eighteen))
    }

    @Test
    fun `one id whose url changed is the same item with different contents`() {
        val before = ImageItem(7, "https://example.test/seven.jpg")
        val after = ImageItem(7, "https://example.test/seven-v2.jpg")

        assertTrue(ImagesDiffCallback.areItemsTheSame(before, after))
        assertFalse(ImagesDiffCallback.areContentsTheSame(before, after))
    }

    @Test
    fun `different ids are different items`() {
        val seven = ImageItem(7, "https://example.test/seven.jpg")
        val zero = ImageItem(0, "https://example.test/zero.jpg")

        assertFalse(ImagesDiffCallback.areItemsTheSame(seven, zero))
    }
}
