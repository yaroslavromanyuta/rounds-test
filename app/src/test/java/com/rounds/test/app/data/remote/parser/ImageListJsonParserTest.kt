package com.rounds.test.app.data.remote.parser

import com.rounds.test.app.presentation.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fixtures are written out literally rather than generated, so each one documents the exact shape
 * it stands for. The valid fixtures mirror the real payload: a bare array of `id` / `imageUrl`
 * records, ids that need not be ascending, and the same url appearing under more than one id.
 */
class ImageListJsonParserTest {

    private val parser = ImageListJsonParser()

    @Test
    fun `parses records in the order the endpoint supplied them`() {
        val json = """
            [
              { "id": 7, "imageUrl": "https://example.test/seven.jpg" },
              { "id": 0, "imageUrl": "https://example.test/zero.jpg" },
              { "id": 3, "imageUrl": "https://example.test/three.jpg" }
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                ImageItem(7, "https://example.test/seven.jpg"),
                ImageItem(0, "https://example.test/zero.jpg"),
                ImageItem(3, "https://example.test/three.jpg"),
            ),
            parser.parse(json),
        )
    }

    @Test
    fun `keeps records that repeat an image url`() {
        val json = """
            [
              { "id": 17, "imageUrl": "https://example.test/same.jpg" },
              { "id": 18, "imageUrl": "https://example.test/same.jpg" }
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                ImageItem(17, "https://example.test/same.jpg"),
                ImageItem(18, "https://example.test/same.jpg"),
            ),
            parser.parse(json),
        )
    }

    @Test
    fun `returns an empty list for an empty array`() {
        assertEquals(emptyList<ImageItem>(), parser.parse("[]"))
    }

    @Test
    fun `ignores fields it does not know`() {
        val json = """[{ "id": 1, "imageUrl": "https://example.test/one.jpg", "width": 1920 }]"""

        assertEquals(listOf(ImageItem(1, "https://example.test/one.jpg")), parser.parse(json))
    }

    @Test
    fun `rejects a record with no id`() {
        assertRejects("""[{ "imageUrl": "https://example.test/one.jpg" }]""")
    }

    @Test
    fun `rejects an id that is not an integer`() {
        assertRejects("""[{ "id": "3", "imageUrl": "https://example.test/one.jpg" }]""")
        assertRejects("""[{ "id": "abc", "imageUrl": "https://example.test/one.jpg" }]""")
        assertRejects("""[{ "id": 3.7, "imageUrl": "https://example.test/one.jpg" }]""")
        assertRejects("""[{ "id": true, "imageUrl": "https://example.test/one.jpg" }]""")
        assertRejects("""[{ "id": null, "imageUrl": "https://example.test/one.jpg" }]""")
    }

    @Test
    fun `rejects a record with no image url`() {
        assertRejects("""[{ "id": 1 }]""")
    }

    @Test
    fun `rejects a blank image url`() {
        assertRejects("""[{ "id": 1, "imageUrl": "" }]""")
        assertRejects("""[{ "id": 1, "imageUrl": "   " }]""")
    }

    @Test
    fun `rejects an image url that is not a string`() {
        assertRejects("""[{ "id": 1, "imageUrl": null }]""")
        assertRejects("""[{ "id": 1, "imageUrl": 42 }]""")
    }

    @Test
    fun `rejects one bad record even when the rest are valid`() {
        val json = """
            [
              { "id": 0, "imageUrl": "https://example.test/zero.jpg" },
              { "id": 1 },
              { "id": 2, "imageUrl": "https://example.test/two.jpg" }
            ]
        """.trimIndent()

        // The whole response fails; the two good records are not returned on their own.
        assertRejects(json)
    }

    @Test
    fun `rejects a record that is not an object`() {
        assertRejects("""["https://example.test/one.jpg"]""")
    }

    @Test
    fun `rejects a top-level object`() {
        assertRejects("""{ "images": [] }""")
    }

    @Test
    fun `rejects a body that is not json`() {
        assertRejects("<html><body>502 Bad Gateway</body></html>")
    }

    private fun assertRejects(json: String) {
        assertThrows(ImageListParseException::class.java) { parser.parse(json) }
    }
}
