package com.rounds.imageloader.cache

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
 * is what guarantees that.
 *
 * The directory's lock is the transaction boundary. Reading, judging and then deleting or promoting
 * an entry, or measuring, replacing and charging one, has to be indivisible: otherwise a decision
 * taken from one state of the directory could be applied to another, and two instances over one
 * directory would delete each other's fresh files or lose each other's bytes. So every operation
 * that touches a canonical file holds that lock for its whole duration, including the file I/O.
 * With the single instance the library builds, on a single-threaded disk dispatcher, the lock is
 * never contended; two instances over one directory work correctly but share one disk pipeline.
 *
 * Caching is best effort: a failure to write, read or delete never propagates to the caller, since
 * a cache problem must not turn a successfully downloaded image into a UI failure. Budget pruning
 * inherits that: if the filesystem refuses a deletion the cache may stay over budget rather than
 * failing a load. Bytes belonging to a write in progress — the temporary this class renames into
 * place — are outside the budget and are never deleted by pruning, because age cannot prove that
 * such a file is abandoned rather than owned by a slow write elsewhere. A temporary orphaned by a
 * process that died mid-write is therefore reclaimed by `clearCache()` or by the system reclaiming
 * the cache directory, not here.
 */
internal class DiskImageCache(
    private val directory: File,
    private val clock: Clock,
    private val maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES,
) {

    init {
        require(maxSizeBytes > 0) { "maxSizeBytes must be positive" }
    }

    /** One canonical entry with the size and access stamp the current pass read from disk. */
    private class Entry(val file: File, val lengthBytes: Long, val accessStamp: Long)

    /**
     * The bookkeeping belongs to the *directory*, not to the object holding it.
     *
     * `ImageLoader.create()` is a plain factory, so two loaders can end up over one cache directory,
     * each writing into it. If each kept its own running total, neither would see the other's bytes
     * between scans and the directory could sit well over the budget. Keying the state by the
     * directory means every instance adds to and prunes against the same number.
     */
    private class DirectoryBudget {

        /** Canonical bytes as of the last full scan, or [UNKNOWN_TOTAL_BYTES] when stale. */
        var totalBytes = UNKNOWN_TOTAL_BYTES

        /** Writes accounted for by arithmetic alone since the last full directory scan. */
        var writesSinceScan = 0

        /** Next access stamp to hand out; see [nextAccessStamp]. */
        var nextStamp = 0L
    }

    private val budget = budgetFor(directory)

    /**
     * A cache written by an earlier version — or by a build with a larger budget — can already be
     * over the limit before this instance stores anything. Enforcing that on the first operation
     * rather than in the constructor keeps the directory scan on the disk dispatcher, off whatever
     * thread called `ImageLoader.create()`.
     *
     * Per instance rather than per directory: each instance has its own [maxSizeBytes], so a second
     * one with a smaller budget still has to prune the directory down to *its* limit once.
     */
    private var budgetEnforced = false

    /**
     * Returns the stored bytes with their original timestamp, or `null` for a miss.
     *
     * An expired entry is deleted. So is an unreadable one — a file truncated by a crash, or one
     * whose header cannot be read — so a corrupt entry cannot be retried forever.
     *
     * A hit is the other half of the LRU signal: only an entry that was actually served becomes the
     * most recently used one. A miss, an invalid entry or a failed read never does.
     */
    fun read(url: String): CachedBytes? = synchronized(budget) {
        // Reading, judging and then deleting or promoting is one transaction. Split up, a decision
        // taken here could be applied to a file another instance has meanwhile replaced: the
        // deletion would take the fresh entry, and the promotion would stamp it with a recency
        // allocated before it existed.
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
        synchronized(budget) {
            enforceBudgetOnFirstUse()
            if (!directory.isDirectory && !directory.mkdirs()) return
            if (entrySizeOf(bytes) > maxSizeBytes) {
                // Larger than the whole cache is allowed to be. Storing it would either blow the
                // budget or be evicted immediately, and keeping the previous entry for this URL
                // would leave a stale image where the caller believes the new one was cached.
                // Judging the size and dropping the old entry is one transaction, so a valid entry
                // another instance stored meanwhile is not what gets deleted.
                remove(url)
                return
            }
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
            // Measuring what is replaced, replacing it and charging the difference is one
            // transaction: two instances writing the same URL would otherwise both measure the old
            // file and each discount it, leaving the shared total below what is really on disk.
            synchronized(budget) {
                // Whatever this write replaces stops counting, so the entry is charged once. Only
                // a file was ever counted, so only a file may be discounted here.
                val replacedBytes = if (target.isFile) target.length() else 0L
                if (!renameIntoPlace(temporary, target)) return
                touch(target)
                accountForWrite(addedBytes = entrySizeOf(bytes), replacedBytes = replacedBytes)
            }
        } catch (failure: IOException) {
            temporary.delete()
        }
    }

    /**
     * Deletes the entry for [url] only while it still holds exactly [expected].
     *
     * The comparison and the deletion are one transaction, so a replacement written between them —
     * by another request, or by another instance over the same directory — is never the file that
     * gets removed. Used to drop bytes that turned out not to be a decodable image.
     */
    fun removeIfUnchanged(url: String, expected: CachedBytes) = synchronized(budget) {
        val current = peek(url) ?: return
        if (
            current.cachedAtMillis == expected.cachedAtMillis &&
            current.bytes.contentEquals(expected.bytes)
        ) {
            remove(url)
        }
    }

    fun remove(url: String) = synchronized(budget) {
        enforceBudgetOnFirstUse()
        dropEntry(fileFor(url))
    }

    fun clear() = synchronized(budget) {
        // Enumerating, deleting and resetting the total is one transaction: a write completing in
        // between would be charged to a total this call is about to overwrite with zero, and the
        // directory would then be allowed to grow past the budget by that much.
        val files = directory.listFiles()
        if (files == null) {
            // Either there is no directory yet — nothing is stored, so the budget is trivially
            // met — or it could not be enumerated, in which case entries may still be there and
            // the total must not be assumed to be zero.
            if (directory.exists()) {
                setTotalBytes(UNKNOWN_TOTAL_BYTES)
            } else {
                budgetEnforced = true
                setTotalBytes(0L)
            }
            return
        }
        var emptied = true
        // Every file is attempted: a refusal must not stop the ones after it.
        files.forEach { file -> if (!file.delete()) emptied = false }
        if (emptied) {
            // Nothing is left to prune, so the budget is trivially enforced from here on.
            budgetEnforced = true
            setTotalBytes(0L)
        } else {
            // Something survived the clear and is still charged to the budget, so the next write
            // has to find out how much rather than assume an empty directory.
            setTotalBytes(UNKNOWN_TOTAL_BYTES)
        }
    }

    private fun renameIntoPlace(temporary: File, target: File): Boolean {
        if (temporary.renameTo(target)) return true
        // Windows refuses a rename onto an existing file, so the old one is removed and the rename
        // retried. If that retry fails too, those bytes are gone while the total still counts them,
        // so the total has to be treated as stale.
        if (target.delete()) budget.totalBytes = UNKNOWN_TOTAL_BYTES
        if (temporary.renameTo(target)) return true
        temporary.delete()
        return false
    }

    /**
     * Records the access stamp as ordinary filesystem metadata. Nothing else is written: the 8-byte
     * header keeps the original `cachedAtMillis`, so being used never extends an entry's TTL.
     */
    private fun touch(file: File) {
        file.setLastModified(nextAccessStamp(file.lastModified()))
    }

    /**
     * The next access stamp, which is never below one already stored in the directory.
     *
     * The wall clock alone cannot order accesses: after a rollback a fresh write or a just-served
     * hit would carry a smaller stamp than entries touched before it and would be evicted first,
     * exactly inverting the policy. Every scan lifts the counter past the newest stamp it saw, and
     * so does the entry being touched here — which covers a directory this instance has not managed
     * to scan — so the order recorded on disk only ever moves forward.
     */
    private fun nextAccessStamp(observedStamp: Long): Long = synchronized(budget) {
        liftPast(observedStamp)
        val stamp = maxOf(clock.nowMillis(), budget.nextStamp)
        budget.nextStamp = incremented(stamp)
        stamp
    }

    /** Keeps the counter ahead of a stamp already recorded on disk. Call under the budget lock. */
    private fun liftPast(stamp: Long) {
        budget.nextStamp = maxOf(budget.nextStamp, incremented(stamp))
    }

    /** A deletion this class did not budget for; the next write has to rescan to be sure. */
    private fun dropEntry(file: File) {
        if (file.delete()) setTotalBytes(UNKNOWN_TOTAL_BYTES)
    }

    private fun setTotalBytes(value: Long) = synchronized(budget) {
        budget.totalBytes = value
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
     * The total is shared per directory, so a second cache instance's writes are included rather
     * than invisible.
     */
    private fun accountForWrite(addedBytes: Long, replacedBytes: Long) {
        val fits = synchronized(budget) {
            val known = budget.totalBytes
            if (known == UNKNOWN_TOTAL_BYTES || budget.writesSinceScan >= WRITES_BEFORE_RESCAN) {
                false
            } else {
                val updated = known + addedBytes - replacedBytes
                budget.totalBytes = updated
                if (updated <= maxSizeBytes) {
                    budget.writesSinceScan++
                    true
                } else {
                    false
                }
            }
        }
        if (!fits) pruneToBudget()
    }

    /**
     * Deletes canonical entries, least recently used first, until they fit [maxSizeBytes].
     *
     * The access stamp is the persisted recency and the filename breaks ties, so the order is the
     * same however the filesystem happens to enumerate the directory. Both are read once into
     * [Entry] rather than re-read during the sort: a file disappearing mid-pass would otherwise
     * change the sort key underneath the comparator and make it throw. A deletion the filesystem
     * refuses is skipped rather than aborting the pass; if enough of them are refused the cache
     * stays over budget, which is the best-effort half of the contract.
     *
     * Call under the directory's lock, like every other operation that touches canonical files.
     */
    private fun pruneToBudget() {
        synchronized(budget) {
            val entries = canonicalEntries()
            if (entries == null) {
                // The directory could not be enumerated. It may still hold entries, so the total
                // stays unknown rather than being reset to zero.
                budget.totalBytes = if (directory.exists()) UNKNOWN_TOTAL_BYTES else 0L
                return
            }
            entries.forEach { entry -> liftPast(entry.accessStamp) }
            var remainingBytes = entries.sumOf { entry -> entry.lengthBytes }
            if (remainingBytes > maxSizeBytes) {
                val leastRecentFirst = entries.sortedWith(
                    compareBy<Entry> { entry -> entry.accessStamp }.thenBy { entry -> entry.file.name },
                )
                for (entry in leastRecentFirst) {
                    if (remainingBytes <= maxSizeBytes) break
                    if (entry.file.delete()) remainingBytes -= entry.lengthBytes
                }
            }
            budget.totalBytes = remainingBytes
            budget.writesSinceScan = 0
        }
    }

    /**
     * The entries the budget applies to. `null` means the directory could not be enumerated at all.
     *
     * The managed directory also holds HTTP response spools and this cache's own atomic-write
     * temporaries, which belong to writes that may still be running — possibly another instance's,
     * for however long a large transfer takes. Only finished entries — a file whose whole name is a
     * [CacheKey] digest — are counted or evicted, so nothing here can unlink a write in progress.
     * Temporaries are cleaned up by the write that owns them and by `clearCache()`.
     */
    private fun canonicalEntries(): List<Entry>? =
        directory.listFiles()
            ?.filter { file -> file.isFile && CANONICAL_NAME.matches(file.name) }
            ?.map { file -> Entry(file, file.length(), file.lastModified()) }

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
        private val CANONICAL_NAME = Regex("[0-9a-f]{64}")

        /**
         * One [DirectoryBudget] per cache directory, so instances over the same directory share the
         * accounting instead of each keeping a private, incomplete view of it. One small entry per
         * directory an application caches into — in practice exactly one — kept for the process
         * lifetime, because a directory used once is likely to be used again.
         */
        private val budgets = ConcurrentHashMap<String, DirectoryBudget>()

        /**
         * Keyed by [File.getAbsolutePath], which is a pure string operation. `canonicalPath` would
         * resolve symlinks — and would also hit the filesystem from a constructor that must not,
         * since a loader is built on the caller's thread. Instances are expected to be handed the
         * same directory, which is what `RealImageLoader.create` does.
         */
        private fun budgetFor(directory: File): DirectoryBudget =
            budgets.getOrPut(directory.absolutePath) { DirectoryBudget() }

        /** Increment that stops at [Long.MAX_VALUE] instead of wrapping into the distant past. */
        private fun incremented(value: Long): Long =
            if (value == Long.MAX_VALUE) value else value + 1
    }
}
