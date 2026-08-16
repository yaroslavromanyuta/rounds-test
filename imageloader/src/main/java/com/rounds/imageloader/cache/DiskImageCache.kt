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
 * Total size is bounded by [maxSizeBytes]: the four-hour TTL bounds how *old* an entry may be, not
 * how many bytes accumulate, so a long scroll over distinct URLs would otherwise grow the cache
 * directory until Android reclaimed it. Once a write completes, the entries are pruned
 * least-recently-used-first until they fit the budget again.
 *
 * Every method is blocking file I/O and must be called from a background dispatcher — [ImageCache]
 * is what guarantees that. That dispatcher is single-threaded, which is also what serialises the
 * budget bookkeeping below.
 *
 * Caching is best effort: a failure to write, read or delete never propagates to the caller, since
 * a cache problem must not turn a successfully downloaded image into a UI failure. Budget pruning
 * inherits that: if the filesystem refuses a deletion the cache may stay over budget rather than
 * failing a load.
 */
internal class DiskImageCache(
    private val directory: File,
    private val clock: Clock,
    private val maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES,
) {

    init {
        require(maxSizeBytes > 0) { "maxSizeBytes must be positive" }
    }

    /** One canonical entry with the size and access time the current pass decided on. */
    private class Entry(val file: File, val lengthBytes: Long, val lastModifiedMillis: Long)

    /**
     * A cache written by an earlier version — or by a build with a larger budget — can already be
     * over the limit before this instance stores anything. Enforcing that on the first operation
     * rather than in the constructor keeps the directory scan on the disk dispatcher, off whatever
     * thread called `ImageLoader.create()`.
     *
     * Plain fields, not atomics: every method here runs on [ImageCache]'s single-threaded disk
     * dispatcher, which both serialises the updates and publishes them between dispatches. An
     * atomic would suggest a mutual exclusion it could not actually provide across a whole pass.
     */
    private var budgetEnforced = false

    /**
     * Total canonical bytes as of the last full scan, or [UNKNOWN_TOTAL_BYTES] when a deletion this
     * class did not account for makes it stale. Only ever used to *skip* a scan, and this instance's
     * own writes are the only thing that adds bytes to it, so for a single writer a stale value can
     * be too high — costing a needless scan — but never too low.
     *
     * That holds for one instance per directory, which is how the library is built: the loader is
     * created once per process and owns its cache directory. A second writer over the same
     * directory would be invisible between scans, so [WRITES_BEFORE_RESCAN] forces one periodically
     * and keeps any such drift bounded rather than permanent.
     */
    private var totalBytes = UNKNOWN_TOTAL_BYTES

    /** Writes accounted for by arithmetic alone since the last full directory scan. */
    private var writesSinceScan = 0

    /**
     * Returns the stored bytes with their original timestamp, or `null` for a miss.
     *
     * An expired entry is deleted. So is an unreadable one — a file truncated by a crash, or one
     * whose header cannot be read — so a corrupt entry cannot be retried forever.
     *
     * A hit is the other half of the LRU signal: only an entry that was actually served becomes the
     * most recently used one. A miss, an invalid entry or a failed read never does.
     */
    fun read(url: String): CachedBytes? {
        enforceBudgetOnFirstUse()
        val file = fileFor(url)
        if (!file.isFile) return null
        return try {
            val entry = DataInputStream(file.inputStream().buffered()).use { input ->
                val cachedAtMillis = input.readLong()
                CachedBytes(input.readBytes(), cachedAtMillis)
            }
            when {
                entry.bytes.isEmpty() -> {
                    dropEntry(file)
                    null
                }

                !isCacheEntryValid(entry.cachedAtMillis, clock.nowMillis()) -> {
                    dropEntry(file)
                    null
                }

                else -> {
                    touch(file)
                    entry
                }
            }
        } catch (failure: IOException) {
            dropEntry(file)
            null
        }
    }

    /**
     * Reads an entry without making it the most recently used one.
     *
     * Used where the bytes are inspected rather than served — verifying that a corrupt entry is
     * still the one that failed to decode. That inspection must not rescue the entry from eviction.
     *
     * Unlike [read] this never validates and never deletes: the caller is deciding what to do with
     * the entry, and an inspection is not the moment to expire it. Whatever it leaves behind is
     * handled by the next real read.
     */
    fun peek(url: String): CachedBytes? {
        val file = fileFor(url)
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val cachedAtMillis = input.readLong()
                CachedBytes(input.readBytes(), cachedAtMillis)
            }
        } catch (failure: IOException) {
            null
        }
    }

    /**
     * Writes to a temporary file first and renames it into place, so a crash or a full disk cannot
     * leave a half-written file that a later read would accept as a valid image.
     *
     * Recency and the budget are updated only once the rename has actually happened, so a failed
     * write neither promotes nor evicts anything.
     */
    fun write(url: String, bytes: ByteArray, cachedAtMillis: Long) {
        enforceBudgetOnFirstUse()
        if (!directory.isDirectory && !directory.mkdirs()) return
        if (entrySizeOf(bytes) > maxSizeBytes) {
            // Larger than the whole cache is allowed to be. Storing it would either blow the budget
            // or be evicted immediately, and keeping the previous entry for this URL would leave a
            // stale image where the caller believes the new one was cached.
            remove(url)
            return
        }
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
            // Whatever this write replaces stops counting, so the entry is charged once. Only a
            // file was ever counted, so only a file may be discounted here.
            val replacedBytes = if (target.isFile) target.length() else 0L
            if (!renameIntoPlace(temporary, target)) return
            touch(target)
            accountForWrite(addedBytes = entrySizeOf(bytes), replacedBytes = replacedBytes)
        } catch (failure: IOException) {
            temporary.delete()
        }
    }

    fun remove(url: String) {
        enforceBudgetOnFirstUse()
        dropEntry(fileFor(url))
    }

    fun clear() {
        val files = directory.listFiles()
        if (files == null) {
            // Either there is no directory yet — nothing is stored, so the budget is trivially
            // met — or it could not be enumerated, in which case entries may still be there and
            // the total must not be assumed to be zero.
            if (directory.exists()) {
                totalBytes = UNKNOWN_TOTAL_BYTES
            } else {
                budgetEnforced = true
                totalBytes = 0L
            }
            return
        }
        var emptied = true
        // Every file is attempted: a refusal must not stop the ones after it.
        files.forEach { file -> if (!file.delete()) emptied = false }
        if (emptied) {
            // Nothing is left to sweep or prune, so the budget is trivially enforced from here on.
            budgetEnforced = true
            totalBytes = 0L
        } else {
            // Something survived the clear and is still charged to the budget, so the next write
            // has to find out how much rather than assume an empty directory.
            totalBytes = UNKNOWN_TOTAL_BYTES
        }
    }

    private fun renameIntoPlace(temporary: File, target: File): Boolean {
        if (temporary.renameTo(target)) return true
        target.delete()
        if (temporary.renameTo(target)) return true
        temporary.delete()
        return false
    }

    /**
     * Records the access time as ordinary filesystem metadata. Nothing else is written: the 8-byte
     * header keeps the original `cachedAtMillis`, so being used never extends an entry's TTL.
     */
    private fun touch(file: File) {
        file.setLastModified(clock.nowMillis())
    }

    /** A deletion this class did not budget for; the next write has to rescan to be sure. */
    private fun dropEntry(file: File) {
        if (file.delete()) totalBytes = UNKNOWN_TOTAL_BYTES
    }

    private fun enforceBudgetOnFirstUse() {
        if (budgetEnforced) return
        budgetEnforced = true
        pruneToBudget()
    }

    /**
     * Applies a completed write to the running total and prunes only if it no longer fits.
     *
     * Scanning the whole directory after every write would put thousands of `stat` calls on the
     * disk dispatcher ahead of the reads a scrolling list is waiting for, so the total from the
     * last scan is carried forward and a full pass happens only when the budget is actually at
     * risk — or when the total is unknown because something outside this accounting deleted a file.
     */
    private fun accountForWrite(addedBytes: Long, replacedBytes: Long) {
        val known = totalBytes
        if (known != UNKNOWN_TOTAL_BYTES && writesSinceScan < WRITES_BEFORE_RESCAN) {
            totalBytes = known + addedBytes - replacedBytes
            if (totalBytes <= maxSizeBytes) {
                writesSinceScan++
                return
            }
        }
        pruneToBudget()
    }

    /**
     * Deletes canonical entries, least recently used first, until they fit [maxSizeBytes].
     *
     * `lastModified` is the persisted access time and the filename breaks ties, so the order is the
     * same however the filesystem happens to enumerate the directory. Both are read once into
     * [Entry] rather than re-read during the sort: a file disappearing mid-pass would otherwise
     * change the sort key underneath the comparator and make it throw. The same walk sweeps stale
     * write debris, so temporaries too young to touch on one pass are reclaimed by a later one. A
     * deletion the filesystem
     * refuses is skipped rather than aborting the pass; if enough of them are refused the cache
     * stays over budget, which is the best-effort half of the contract.
     */
    private fun pruneToBudget() {
        val entries = scanDirectory()
        if (entries == null) {
            // The directory could not be enumerated. It may still hold entries, so the total stays
            // unknown rather than being reset to zero.
            totalBytes = if (directory.exists()) UNKNOWN_TOTAL_BYTES else 0L
            return
        }
        var remainingBytes = entries.sumOf { entry -> entry.lengthBytes }
        if (remainingBytes > maxSizeBytes) {
            val leastRecentFirst = entries.sortedWith(
                compareBy<Entry> { entry -> entry.lastModifiedMillis }.thenBy { entry -> entry.file.name },
            )
            for (entry in leastRecentFirst) {
                if (remainingBytes <= maxSizeBytes) break
                if (entry.file.delete()) remainingBytes -= entry.lengthBytes
            }
        }
        totalBytes = remainingBytes
        writesSinceScan = 0
    }

    /**
     * One walk of the managed directory, returning the entries the budget applies to and removing
     * write debris on the way past. `null` means the directory could not be enumerated at all.
     *
     * The directory also holds HTTP response spools and this cache's own atomic-write temporaries,
     * which belong to writes that may still be running. Only finished entries — a file whose whole
     * name is a [CacheKey] digest — are counted or evicted, so pruning can never unlink a transfer
     * in progress.
     *
     * A temporary older than [STALE_TEMPORARY_MILLIS] is different: a write of this size takes
     * milliseconds, so at that age it can only be what a process that died mid-write left behind.
     * Nothing counts those bytes, so without this they would sit outside the budget forever;
     * `HttpImageDownloader` sweeps its own response spools the same way.
     */
    private fun scanDirectory(): List<Entry>? {
        val files = directory.listFiles() ?: return null
        val staleBefore = clock.nowMillis() - STALE_TEMPORARY_MILLIS
        val entries = mutableListOf<Entry>()
        for (file in files) {
            if (!file.isFile) continue
            if (CANONICAL_NAME.matches(file.name)) {
                entries += Entry(file, file.length(), file.lastModified())
            } else if (file.name.startsWith(TEMP_PREFIX) && file.lastModified() < staleBefore) {
                file.delete()
            }
        }
        return entries
    }

    /** Complete on-disk cost of an entry: the timestamp header plus the encoded bytes. */
    private fun entrySizeOf(bytes: ByteArray): Long = HEADER_BYTES + bytes.size.toLong()

    private fun fileFor(url: String) = File(directory, CacheKey.of(url))

    internal companion object {

        /**
         * 128 MiB of encoded images — large enough that a realistic session is served from disk,
         * small enough to be a polite share of the application cache directory.
         */
        const val DEFAULT_MAX_SIZE_BYTES: Long = 128L * 1024L * 1024L

        private const val TEMP_PREFIX = "imageloader"

        /** The 8-byte big-endian `cachedAtMillis` every entry file starts with. */
        private const val HEADER_BYTES: Long = 8L

        /** No usable running total: the next write has to scan the directory. */
        private const val UNKNOWN_TOTAL_BYTES: Long = -1L

        /** How many writes may be accounted for arithmetically before a full scan is forced. */
        private const val WRITES_BEFORE_RESCAN = 32

        /** Age at which an atomic-write temporary can only be the debris of a dead write. */
        private const val STALE_TEMPORARY_MILLIS = 60_000L
        private val CANONICAL_NAME = Regex("[0-9a-f]{64}")
    }
}
