# rounds-test

Android home assignment: a custom image downloading/caching library plus a sample application that demonstrates it.

The library is written from scratch — no Glide, Coil, Picasso or Fresco, and no Retrofit/OkHttp either. Downloading is `HttpURLConnection`, decoding is `BitmapFactory`. The sample app uses Android Views/XML (no Jetpack Compose) and MVVM.

> **Status — image-loader core and cache (MR #3).**
> The library downloads, decodes and displays remote images with placeholder support, basic
> target-request protection, a bounded memory cache, a persistent disk cache, four-hour expiry and
> manual invalidation. **In-flight same-URL request deduplication is not implemented yet**, so two
> concurrent loads of the same uncached URL can still produce two downloads. The image list screen
> is still to come.

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android application | Sample app: image list retrieval, MVVM presentation, RecyclerView UI, cache invalidation control. Consumes the library through its public API only. |
| `:imageloader` | Android library | Reusable image loading: request API, HTTP download, decoding, memory + disk cache, four-hour TTL, invalidation, request lifecycle. |

Dependency direction:

```text
:app
  ↓
:imageloader
```

`:imageloader` must never depend on `:app`. It is a standalone library that happens to be demonstrated by the sample app.

## Public API

```kotlin
interface ImageLoader {
    fun load(url: String, @DrawableRes placeholderRes: Int, target: ImageView)
    fun load(url: String, target: ImageView)
    fun clear(target: ImageView)      // one view's request
    fun clearCache()                  // every cached image
    fun invalidate(url: String)       // one cached image

    companion object {
        const val NO_PLACEHOLDER: Int = 0
        @JvmStatic fun create(context: Context): ImageLoader
    }
}
```

Three operations are easy to confuse, so they are named apart:

| Call | Scope | Effect |
|---|---|---|
| `clear(target)` | one `ImageView` | cancels that view's pending request; cached images untouched |
| `invalidate(url)` | one URL | drops that image from memory and disk |
| `clearCache()` | everything | drops every cached image from memory and disk |

`create(context)` retains only `context.applicationContext` — it needs a cache directory, not an Activity — so passing an Activity is safe.

The placeholder is optional. `load(url, target)` loads without one and **empties the target first** — a reused view must not keep showing the previous item's image while the new one arrives. `NO_PLACEHOLDER` is the same thing for callers that compute the resource id and may not have one.

Two explicit overloads rather than a Kotlin default argument: `@JvmOverloads` is illegal on interface methods, and it only drops trailing parameters — so a default would have been omittable from Kotlin but never from Java, which the assignment does not allow.

### Kotlin

```kotlin
private val imageLoader = (application as RoundsApplication).imageLoader

imageLoader.load(item.url, R.drawable.placeholder, holder.imageView)
imageLoader.load(item.url, holder.imageView)          // no placeholder

// When a view is recycled or detached:
imageLoader.clear(holder.imageView)

imageLoader.invalidate(item.url)                      // forget one image
imageLoader.clearCache()                              // forget all of them
```

### Java

```java
ImageLoader loader = ImageLoader.create(context);

loader.load(item.getUrl(), R.drawable.placeholder, imageView);
loader.load(item.getUrl(), imageView);                // no placeholder

loader.clear(imageView);

loader.invalidate(item.getUrl());
loader.clearCache();
```

A Java caller never creates a `CoroutineScope`, passes a suspending function, unwraps a Kotlin `Result` or touches a `Flow`. `JavaImageLoaderInteropTest` is written in Java precisely so this stays true rather than being assumed.

## How a load works

```text
load(url, placeholderRes, target)
        ↓  main thread, synchronous
cancel the target's previous request
        ↓
apply the placeholder                      <- visible before load() returns
        ↓  coroutine on the loader's scope
memory cache              (main thread, LruCache)
        ↓ miss / expired
disk cache                (disk dispatcher) ──> decode ──> promote to memory
        ↓ miss / expired / undecodable
download bytes            (IO dispatcher, HttpURLConnection)
        ↓
decode Bitmap             (IO dispatcher, BitmapFactory)
        ↓
store in memory + disk with one timestamp
        ↓  main thread
is this still the target's current request?
        ↓ yes                     ↓ no
apply the bitmap            discard the result
```

A valid memory hit is applied before `load()` even returns — the scope runs on `Dispatchers.Main.immediate` — so a warm cache repaints without a placeholder flash.

**Coroutine model.** The loader owns a single `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`. `SupervisorJob` keeps one failed or cancelled request from affecting any other. Network and decode work runs on `Dispatchers.IO`; every view mutation happens on the main dispatcher. `GlobalScope` is not used anywhere, and the scope holds no Activity, Fragment or view — the loader is a process-wide singleton created in `RoundsApplication`.

**Placeholder.** Applied synchronously inside `load()`, before any coroutine starts — or the target is emptied when no placeholder was supplied. On failure — malformed URL, I/O error, non-2xx status, empty body, undecodable payload — the placeholder simply stays and nothing is thrown; the host app cannot be crashed by a bad image.

**Target safety.** Each request gets a monotonic token, stored together with its `Job` in the target `ImageView`'s own tag (no long-lived map keyed by views). Starting a new load cancels the previous request for that view, and a result is applied only if the target still belongs to the request that produced it — so a slow request that completes after a newer one can never overwrite it, regardless of completion order. This holds for memory, disk and network results alike: no tier has a shortcut past the check. `clear(target)` cancels the pending request and prevents its result from being applied. The view is held through a `WeakReference`, so an in-flight download cannot retain a detached view. Full in-flight deduplication and shared request lifecycle are MR #4.

## Cache

**Memory.** `androidx.collection.LruCache` bounded to an eighth of the process heap, measured by `Bitmap.byteCount` so eviction tracks real memory pressure rather than entry count. Holds the decoded `Bitmap` plus the timestamp of the download it came from. (`android.util.LruCache` is a framework stub in JVM unit tests, which is why the androidx one is used.)

**Disk.** One file per URL under `context.cacheDir/image_loader`, named `SHA-256(url)` in hex — a raw URL is not a legal filename, and a digest is deterministic, fixed-length and collision-free in practice. Each file is an 8-byte big-endian timestamp followed by the **original encoded bytes** as downloaded; storing those instead of re-compressing the decoded bitmap avoids a needless encode, a format change and quality loss. Writes land in a temporary file that is renamed into place, so an interrupted write can never be read back as a valid image.

Caching is best effort. A write that fails is swallowed and the image is still displayed — a cache problem must not become a UI failure. An entry that is expired, truncated, or whose bytes will not decode is deleted on encounter and treated as a miss.

**TTL — exactly four hours.** An entry is valid while `now - cachedAt < 4h`. At exactly four hours it is **expired**; the comparison is strict. The timestamp is taken once, only after a download *and* a decode both succeed, and the same value goes to both tiers. The TTL is fixed, not sliding: reading never rewrites it, and promoting an entry from disk into memory carries the original timestamp, so a disk hit cannot restart the window.

**Invalidation.** `clearCache()` and `invalidate(url)` empty memory and bump a generation counter synchronously — the cache is logically empty the moment the call returns — while the file deletion runs on the disk dispatcher. A load already in flight when the cache was invalidated still displays its image but is refused when it tries to store it, so it cannot resurrect what was just dropped. All disk work is serialised on a single-threaded dispatcher, so a deletion queued by an invalidation always completes before a read queued after it.

## Architecture

The sample app follows MVVM:

```text
Activity / Fragment  (Android Views, ViewBinding)
        ↓ observes
    ViewModel
        ↓
    Repository
        ↓
  Remote Data Source
```

`:app` sources live under `com.rounds.test.app`, split by layer — `presentation.ui` and the `RoundsApplication` composition root today; `data.remote`, `data.repository`, `presentation.model`, `presentation.viewmodel` are added by the merge requests that populate them.

Dependency composition is **manual** — no Hilt, Dagger or Koin. `RoundsApplication` creates the single `ImageLoader` and hands it to whatever needs it.

`:imageloader` internals:

```text
com.rounds.imageloader
├── ImageLoader            public API (the only exported type)
├── internal/RealImageLoader   pipeline + coroutine ownership
├── network/               ImageDownloader, HttpImageDownloader
├── decode/                ImageDecoder, BitmapFactoryImageDecoder
├── cache/                 ImageCache, MemoryImageCache, DiskImageCache,
│                          Clock, CacheKey, TTL rule
└── request/               Target, ImageViewTarget, TargetRequest
```

Everything except `ImageLoader` is `internal`. The downloader, decoder and target abstractions exist because they are genuine external boundaries that must be substitutable in tests — not for symmetry.

## Build and run

Requires the Android SDK (`local.properties` → `sdk.dir`). Toolchain: AGP 9.3.1, Gradle 9.5, `minSdk 24`, `compileSdk`/`targetSdk` 37, Java 11 bytecode. The Gradle daemon runs on Java 25 (`gradle/gradle-daemon-jvm.properties`).

```powershell
.\gradlew.bat assembleDebug     # build the debug APK
.\gradlew.bat installDebug      # install on a connected device/emulator
```

## Tests and checks

```powershell
.\gradlew.bat test                          # JVM unit tests (all modules)
.\gradlew.bat :imageloader:testDebugUnitTest  # image-loader tests only
.\gradlew.bat lint                          # Android Lint
.\gradlew.bat connectedAndroidTest          # instrumented tests (needs a device/emulator)
```

The image-loader tests are deterministic and offline:

- the load pipeline runs on a single `StandardTestDispatcher` with fake downloader/decoder/target, so execution order is controlled by the test rather than by timing — covering placeholder, loading without a placeholder, success, download failure, decode failure, blank URL, request replacement, stale-result rejection, `clear()` and failure isolation between targets;
- cache behaviour is driven by an injectable `Clock`, never by waiting: the four-hour boundary is asserted at 4h−1ms, exactly 4h and 4h+1ms, and repeated reads are shown not to extend it. Tier ordering (memory hit skips disk and network, disk hit skips the network, expired entries re-download), timestamp preservation on disk→memory promotion, "failures are never cached", "a disk write failure still displays the image", and both invalidation-versus-in-flight races are covered too;
- the disk cache runs against a real temporary directory — real files, real atomic renames, real corrupt-file handling — not a mocked filesystem;
- `HttpImageDownloader` is exercised against a JDK `HttpServer` on an ephemeral loopback port — real HTTP, no production endpoint, no public image host;
- three tests are written in Java to verify the API is usable from Java, including the placeholder-free overload and both cache-invalidation calls.

## Assumptions and trade-offs

- **Placeholder is a `@DrawableRes Int`, optional via a second overload.** A `Drawable` overload adds API surface without demonstrating anything new; the assignment asks for a placeholder resource.
- **No returned request handle.** `clear(target)` covers the cancellation the assignment needs, and keeps the Java API small.
- **Memory + disk caching is an engineering decision.** The assignment requires "cache the image"; two tiers are the choice made here — memory for repeated access in-process, disk to survive a restart.
- **No disk size limit.** Disk entries are pruned lazily by TTL when encountered, and the four-hour window bounds growth for this use case. A size-capped disk LRU would be the next step in a production library; the memory tier, where an unbounded cache actually crashes an app, *is* bounded.
- **In-flight same-URL deduplication is not implemented.** Two concurrent loads of the same uncached URL can each download it. That is MR #4's subject, not an oversight.
- **`Bitmap`/`ImageView` cannot be instantiated on the JVM.** Rather than adding Robolectric, view mutation sits behind a small internal `Target` seam so the interesting logic is plain-JVM testable; Mockito is used only to fabricate a `Bitmap` instance for identity assertions.
- **Cross-protocol redirects are not followed.** `HttpURLConnection` does not follow an HTTP→HTTPS redirect; such a response is treated as a failure, which is safe rather than surprising.
- **Cancellation of a request in flight does not interrupt a blocking socket read.** The result is discarded and the connection is closed when the read returns; interrupting the read would add complexity out of proportion to the benefit here.
