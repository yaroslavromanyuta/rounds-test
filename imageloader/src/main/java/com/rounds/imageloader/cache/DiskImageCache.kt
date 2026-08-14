package com.rounds.imageloader.cache

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Persistent cache of downloaded image bytes.
 *
 * One file per URL, named by [CacheKey], holding an 8-byte big-endian timestamp followed by the
 * **original encoded bytes**. Storing the bytes as downloaded rather than re-compressing the
 * decoded bitmap avoids a needless encode on every save, a format change and the quality loss that
 * would come with it.
 *
 * Every method is blocking file I/O and must be called from a background dispatcher — [ImageCache]
 * is what guarantees that.
 *
 * Caching is best effort: a failure to write, read or delete never propagates to the caller, since
 * a cache problem must not turn a successfully downloaded image into a UI failure.
 */
internal class DiskImageCache(
    private val directory: File,
    private val clock: Clock,
) {

    /**
     * Returns the stored bytes with their original timestamp, or `null` for a miss.
     *
     * An expired entry is deleted. So is an unreadable one — a file truncated by a crash, or one
     * whose header cannot be read — so a corrupt entry cannot be retried forever.
     */
    fun read(url: String): CachedBytes? {
        val file = fileFor(url)
        if (!file.isFile) return null
        return try {
            val entry = DataInputStream(file.inputStream().buffered()).use { input ->
                val cachedAtMillis = input.readLong()
                CachedBytes(input.readBytes(), cachedAtMillis)
            }
            when {
                entry.bytes.isEmpty() -> {
                    file.delete()
                    null
                }

                !isCacheEntryValid(entry.cachedAtMillis, clock.nowMillis()) -> {
                    file.delete()
                    null
                }

                else -> entry
            }
        } catch (failure: IOException) {
            file.delete()
            null
        }
    }

    /**
     * Writes to a temporary file first and renames it into place, so a crash or a full disk cannot
     * leave a half-written file that a later read would accept as a valid image.
     */
    fun write(url: String, bytes: ByteArray, cachedAtMillis: Long) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        val temporary = try {
            File.createTempFile(TEMP_PREFIX, null, directory)
        } catch (failure: IOException) {
            return
        }
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeLong(cachedAtMillis)
                output.write(bytes)
            }
            val target = fileFor(url)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) temporary.delete()
            }
        } catch (failure: IOException) {
            temporary.delete()
        }
    }

    fun remove(url: String) {
        fileFor(url).delete()
    }

    fun clear() {
        directory.listFiles()?.forEach { file -> file.delete() }
    }

    private fun fileFor(url: String) = File(directory, CacheKey.of(url))

    private companion object {
        private const val TEMP_PREFIX = "imageloader"
    }
}
