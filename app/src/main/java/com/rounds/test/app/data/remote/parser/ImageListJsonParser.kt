package com.rounds.test.app.data.remote.parser

import com.rounds.test.app.presentation.model.ImageItem
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns the image-list response body into [ImageItem]s. Pure: no I/O, no threading, no Android
 * dependency beyond the platform's own JSON classes, which keeps it directly unit-testable.
 *
 * The payload is a flat array of `{"id": Int, "imageUrl": String}` records.
 *
 * A record that is missing a required field, or carries one of the wrong type, fails the **whole**
 * response rather than being skipped. Substituting `id = 0` or `imageUrl = ""` would be worse than
 * useless here — `id = 0` is a real record in the live payload — and quietly dropping records would
 * show a short list that looks complete. One consistent rule: either the response parses, or the
 * screen reports an error.
 */
internal class ImageListJsonParser {

    @Throws(ImageListParseException::class)
    fun parse(json: String): List<ImageItem> {
        val records = try {
            JSONArray(json)
        } catch (malformed: JSONException) {
            throw ImageListParseException("Image list response is not a JSON array", malformed)
        }

        return (0 until records.length()).map { index ->
            val record = records.opt(index) as? JSONObject
                ?: throw ImageListParseException("Image list record $index is not an object")
            ImageItem(id = readId(record, index), imageUrl = readImageUrl(record, index))
        }
    }

    /**
     * Read by type rather than through `JSONObject.getInt`, which would happily coerce `"3"` and
     * `3.7` into `3` and make the "reject malformed records" rule a half-truth.
     */
    private fun readId(record: JSONObject, index: Int): Int {
        val value = when (val raw = record.opt(KEY_ID)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        }
        if (value == null || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw ImageListParseException("Image list record $index has no integer \"$KEY_ID\"")
        }
        return value.toInt()
    }

    private fun readImageUrl(record: JSONObject, index: Int): String {
        val raw = record.opt(KEY_IMAGE_URL)
        if (raw !is String || raw.isBlank()) {
            throw ImageListParseException("Image list record $index has no \"$KEY_IMAGE_URL\"")
        }
        // Whether the URL actually resolves is the image loader's problem, not the parser's; a
        // non-empty string is treated as data and never pre-fetched here.
        return raw
    }

    private companion object {
        private const val KEY_ID = "id"
        private const val KEY_IMAGE_URL = "imageUrl"
    }
}

/**
 * Signals an unusable image-list body. Extends [IOException] so that a caller handling "the fetch
 * did not produce a list" needs one catch, not two.
 */
internal class ImageListParseException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
