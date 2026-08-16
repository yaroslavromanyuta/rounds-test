package com.rounds.imageloader.cache

import android.graphics.Bitmap
import com.rounds.imageloader.testing.FakeClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/** Upper bound on any wait here. Reaching it means a hang, never a slow machine. */
private const val HANG_BOUND_SECONDS = 10L

/**
 * The transaction boundary between a load that is storing and an invalidation that is running at
 * the same time. Passing the generation check must not be enough on its own: the check and the
 * store it authorises have to be indivisible, or an invalidation can land between them and the load
 * resurrects exactly what was just dropped.
 *
 * The two tiers are proved differently, because they are transactional for different reasons:
 *
 * - memory is proved with two real threads and a store suspended inside the transaction, since the
 *   guarantee there is mutual exclusion;
 * - disk is proved on one thread with a controllable dispatcher, since the guarantee there is that
 *   authorisation happens inside the dispatched operation. The dispatcher's hook models an
 *   invalidation completing at the exact moment the stale caller hands its write over to the disk
 *   queue — the interleaving that used to resurrect deleted bytes.
 *
 * No test here sleeps or guesses at timing; the waits below are hang bounds, not synchronisation.
 */
class ImageCacheInvalidationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val clock = FakeClock(START_MILLIS)
    private val memory = GatedMemoryImageCache(clock, MEMORY_BYTES)
    private val disk by lazy { DiskImageCache(temporaryFolder.root, clock) }
    private val diskDispatcher = ManualDiskDispatcher()
    private val cache by lazy { ImageCache(memory, disk, diskDispatcher) }

    @Test
    fun `a memory write authorised before invalidate cannot outlive it`() {
        val staleSnapshot = cache.snapshot(URL_A)
        val writer = staleMemoryWriterFor(staleSnapshot)

        val invalidator = startInvalidator("invalidator") { cache.invalidate(URL_A) }
        awaitSettled(invalidator)
        memory.releasePut()
        writer.join()
        invalidator.join()

        assertNull(
            "a load holding a pre-invalidation snapshot resurrected the url in memory",
            cache.getFromMemory(URL_A),
        )
    }

    @Test
    fun `a memory write authorised before clear cannot outlive it`() {
        val staleSnapshot = cache.snapshot(URL_A)
        val writer = staleMemoryWriterFor(staleSnapshot)

        val clearer = startInvalidator("clearer") { cache.clear() }
        awaitSettled(clearer)
        memory.releasePut()
        writer.join()
        clearer.join()

        assertNull(
            "a load holding a pre-clear snapshot resurrected the url in memory",
            cache.getFromMemory(URL_A),
        )
    }

    @Test
    fun `a disk write authorised before invalidate cannot resurrect the deleted bytes`() {
        val staleSnapshot = cache.snapshot(URL_A)
        diskDispatcher.beforeNextDispatch = {
            // The stale caller has begun and is handing its write to the disk queue. The
            // invalidation happens now: the generation is bumped, and its deletion — which
            // `invalidateOnDisk` runs on this very dispatcher — has already taken effect.
            cache.invalidate(URL_A)
            disk.remove(URL_A)
        }

        drainingDisk { cache.putOnDisk(URL_A, BYTES_A, START_MILLIS, staleSnapshot) }

        assertNull(
            "a write authorised before the invalidation resurrected the url on disk",
            disk.peek(URL_A),
        )
    }

    @Test
    fun `a disk write authorised before clear cannot resurrect the deleted bytes`() {
        val staleSnapshot = cache.snapshot(URL_A)
        diskDispatcher.beforeNextDispatch = {
            cache.clear()
            disk.clear()
        }

        drainingDisk { cache.putOnDisk(URL_A, BYTES_A, START_MILLIS, staleSnapshot) }

        assertNull(
            "a write authorised before the clear resurrected the url on disk",
            disk.peek(URL_A),
        )
    }

    @Test
    fun `a snapshot taken after invalidation may fill both tiers again`() {
        cache.invalidate(URL_A)
        drainingDisk { cache.invalidateOnDisk(URL_A) }

        val freshSnapshot = cache.snapshot(URL_A)
        cache.putInMemory(URL_A, bitmapA, START_MILLIS, freshSnapshot)
        drainingDisk { cache.putOnDisk(URL_A, BYTES_A, START_MILLIS, freshSnapshot) }

        assertSame(bitmapA, cache.getFromMemory(URL_A)?.bitmap)
        assertArrayEquals(BYTES_A, disk.peek(URL_A)?.bytes)
    }

    @Test
    fun `a snapshot taken after a clear may fill both tiers again`() {
        cache.clear()
        drainingDisk { cache.clearDisk() }

        val freshSnapshot = cache.snapshot(URL_A)
        cache.putInMemory(URL_A, bitmapA, START_MILLIS, freshSnapshot)
        drainingDisk { cache.putOnDisk(URL_A, BYTES_A, START_MILLIS, freshSnapshot) }

        assertSame(bitmapA, cache.getFromMemory(URL_A)?.bitmap)
        assertArrayEquals(BYTES_A, disk.peek(URL_A)?.bytes)
    }

    @Test
    fun `invalidating one url leaves another one cacheable`() {
        val snapshotB = cache.snapshot(URL_B)

        cache.invalidate(URL_A)
        drainingDisk { cache.invalidateOnDisk(URL_A) }
        cache.putInMemory(URL_B, bitmapB, START_MILLIS, snapshotB)
        drainingDisk { cache.putOnDisk(URL_B, BYTES_B, START_MILLIS, snapshotB) }

        assertSame(bitmapB, cache.getFromMemory(URL_B)?.bitmap)
        assertArrayEquals(BYTES_B, disk.peek(URL_B)?.bytes)
        assertNull(cache.getFromMemory(URL_A))
        assertNull(disk.peek(URL_A))
    }

    @Test
    fun `a clear affects every key`() {
        val snapshotA = cache.snapshot(URL_A)
        val snapshotB = cache.snapshot(URL_B)
        cache.putInMemory(URL_A, bitmapA, START_MILLIS, snapshotA)
        cache.putInMemory(URL_B, bitmapB, START_MILLIS, snapshotB)
        drainingDisk { cache.putOnDisk(URL_A, BYTES_A, START_MILLIS, snapshotA) }
        drainingDisk { cache.putOnDisk(URL_B, BYTES_B, START_MILLIS, snapshotB) }
        assertNotNull(disk.peek(URL_A))

        cache.clear()
        drainingDisk { cache.clearDisk() }

        assertNull(cache.getFromMemory(URL_A))
        assertNull(cache.getFromMemory(URL_B))
        assertNull(disk.peek(URL_A))
        assertNull(disk.peek(URL_B))
        // Both snapshots are stale now, so neither load may write anything back.
        cache.putInMemory(URL_A, bitmapA, START_MILLIS, snapshotA)
        drainingDisk { cache.putOnDisk(URL_B, BYTES_B, START_MILLIS, snapshotB) }
        assertNull(cache.getFromMemory(URL_A))
        assertNull(disk.peek(URL_B))
    }

    /**
     * Starts a store that has already passed the generation check and is suspended inside the
     * memory transaction, and returns once it is definitely there.
     */
    private fun staleMemoryWriterFor(snapshot: CacheSnapshot): Thread {
        memory.gateNextPut()
        val writer = thread(name = "stale-writer") {
            cache.putInMemory(URL_A, bitmapA, START_MILLIS, snapshot)
        }
        assertTrue(
            "the stale store never reached the memory cache",
            memory.awaitGatedPut(HANG_BOUND_SECONDS),
        )
        return writer
    }

    /**
     * Starts an invalidation on its own thread and returns once that thread is running the call, so
     * the state observed afterwards is the state of the invalidation and not of a thread that has
     * not reached it yet.
     */
    private fun startInvalidator(name: String, invalidation: () -> Unit): Thread {
        val started = CountDownLatch(1)
        val invalidator = thread(name = name) {
            started.countDown()
            invalidation()
        }
        assertTrue("$name never started", started.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS))
        return invalidator
    }

    /**
     * Waits until an invalidating thread has either finished or blocked on the memory transaction
     * the stale store is holding.
     *
     * Both are states of the thread rather than moments in time, so nothing here depends on how
     * fast anything runs: an implementation that lets the invalidation through observes the first,
     * one that serialises it observes the second, and the assertion afterwards is the same either
     * way. The deadline only stops a genuinely stuck test from hanging the build.
     */
    private fun awaitSettled(thread: Thread) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(HANG_BOUND_SECONDS)
        while (System.nanoTime() < deadlineNanos) {
            when (thread.state) {
                Thread.State.BLOCKED, Thread.State.TERMINATED -> return
                else -> Thread.onSpinWait()
            }
        }
        fail("${thread.name} neither completed nor blocked on the cache transaction")
    }

    /** Runs a suspending cache call, servicing the disk queue until it finishes. */
    private fun drainingDisk(block: suspend () -> Unit) = runBlocking {
        val job = launch(Dispatchers.Unconfined) { block() }
        while (!job.isCompleted && diskDispatcher.runQueued()) {
            // Draining one dispatched operation can resume the caller and queue the next one.
        }
        assertTrue("the disk queue ran dry with the operation unfinished", job.isCompleted)
        job.join()
    }

    /**
     * A memory cache whose next store can be suspended inside `ImageCache`'s memory transaction.
     *
     * The gate sits before the delegation to the real cache, so a suspended store holds whatever
     * `ImageCache` holds around it and nothing of the `LruCache`'s own — otherwise the `LruCache`'s
     * internal lock, not the code under test, would be what serialises the invalidation.
     */
    private class GatedMemoryImageCache(
        clock: Clock,
        maxSizeBytes: Int,
    ) : MemoryImageCache(clock, maxSizeBytes) {

        private val armed = AtomicBoolean(false)
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun gateNextPut() {
            armed.set(true)
        }

        fun awaitGatedPut(timeoutSeconds: Long): Boolean =
            entered.await(timeoutSeconds, TimeUnit.SECONDS)

        fun releasePut() {
            released.countDown()
        }

        override fun put(url: String, bitmap: Bitmap, cachedAtMillis: Long) {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(released.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS)) {
                    "the gated store was never released"
                }
            }
            super.put(url, bitmap, cachedAtMillis)
        }
    }

    /**
     * Disk dispatcher whose queue the test drains by hand, with a hook that runs on the dispatching
     * thread *before* the operation is queued — the window in which an invalidation used to be able
     * to overtake a write that had already been authorised.
     */
    private class ManualDiskDispatcher : CoroutineDispatcher() {

        private val queue = ArrayDeque<Runnable>()

        /** Invoked once, on the next dispatch, before the block joins the queue. */
        var beforeNextDispatch: (() -> Unit)? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            beforeNextDispatch?.let { hook ->
                beforeNextDispatch = null
                hook()
            }
            synchronized(queue) { queue.addLast(block) }
        }

        /** Runs everything queued so far, including anything those operations queue. Ran any? */
        fun runQueued(): Boolean {
            var ranAny = false
            while (true) {
                val block = synchronized(queue) { queue.removeFirstOrNull() } ?: return ranAny
                ranAny = true
                block.run()
            }
        }
    }

    private companion object {
        private const val START_MILLIS = 1_000_000L
        private const val MEMORY_BYTES = 8 * 1024 * 1024
        private const val URL_A = "https://example.test/a.png"
        private const val URL_B = "https://example.test/b.png"
        private val BYTES_A = "image-a".toByteArray()
        private val BYTES_B = "image-b".toByteArray()
        private val bitmapA: Bitmap = mock(Bitmap::class.java)
        private val bitmapB: Bitmap = mock(Bitmap::class.java)
    }
}
