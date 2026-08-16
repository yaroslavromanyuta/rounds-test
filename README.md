# Rounds Android Home Assignment

A custom image downloading/caching library plus a sample application that demonstrates it.

The library is written from scratch — no Glide, Coil, Picasso or Fresco, and no Retrofit/OkHttp either. Downloading is `HttpURLConnection`, decoding is `BitmapFactory`. The sample app uses Android Views/XML (no Jetpack Compose) and MVVM.

## Requirements implemented

Every explicit requirement of the assignment, and where it lives.

| Requirement | Where |
|---|---|
| **Library** — accepts a URL, a placeholder and a target `ImageView` | `ImageLoader.load(url, placeholderRes, target)` |
| downloads the remote image | `network/HttpImageDownloader` (`HttpURLConnection`) |
| decodes it | `decode/BitmapFactoryImageDecoder` (`BitmapFactory`) |
| displays it in the target view | `request/ImageViewTarget` |
| caches the image | `cache/MemoryImageCache` + `cache/DiskImageCache` |
| cache valid for exactly 4 hours | `cache/CacheTtl.kt` — `0 <= now - cachedAt < 4h`, strict |
| manual cache invalidation | `ImageLoader.clearCache()` and `ImageLoader.invalidate(url)` |
| uses Kotlin Coroutines | `internal/RealImageLoader`, `internal/InFlightRequestRegistry` |
| usable from Kotlin | [Kotlin example](#kotlin) |
| usable from Java | [Java example](#java) — enforced by `JavaImageLoaderInteropTest`, written in Java |
| no Glide or equivalent | no image-loading dependency anywhere in `gradle/libs.versions.toml` |
| **App** — fetches the supplied JSON endpoint | `data/remote/HttpImagesRemoteDataSource` |
| parses `id` and `imageUrl` | `data/remote/parser/ImageListJsonParser` |
| displays a list | `presentation/ui/ImagesAdapter` + `RecyclerView` |
| displays image and image id per row | `res/layout/item_image.xml` |
| placeholder while loading | `res/drawable/image_placeholder.xml`, applied by the library |
| cache-invalidation button | `MainActivity.clearImageCache()` → `ImageLoader.clearCache()` |
| Android Views, no Jetpack Compose | XML layouts + ViewBinding; no Compose dependency exists |
| MVVM | `MainActivity` → `ImagesViewModel` → `ImagesRepository` → `ImagesRemoteDataSource` |
| Coroutines | `viewModelScope`, `StateFlow`, `withContext(Dispatchers.IO)` |

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android application | Sample app: image list retrieval, MVVM presentation, RecyclerView UI, cache invalidation control. Consumes the library through its public API only. |
| `:imageloader` | Android library | Reusable image loading: request API, HTTP download, decoding, memory + disk cache, four-hour TTL, invalidation, request lifecycle and in-flight deduplication. |

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
    @MainThread fun load(url: String, @DrawableRes placeholderRes: Int, target: ImageView)
    @MainThread fun load(url: String, target: ImageView)
    @MainThread fun clear(target: ImageView)      // one view's request
    @AnyThread  fun clearCache()                  // every cached image
    @AnyThread  fun invalidate(url: String)       // one cached image

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

**Threading is part of the signature, not just the prose.** `load(...)` and `clear(target)` mutate the `ImageView` *before they return* — the placeholder is applied, or the reused target emptied, or the request association dropped, synchronously — so they are ordinary main-thread view calls and are annotated `@MainThread`. Everything after that point is asynchronous, and the decoded bitmap is always delivered back on the main dispatcher. `clearCache()` and `invalidate(url)` touch no view and are annotated `@AnyThread`. Because `androidx.annotation` is an `api` dependency, Java and Kotlin consumers get these annotations on their own compile classpath and Android Lint flags a call from the wrong thread at the call site; nothing throws at runtime, and the synchronous guarantees above are unchanged.

The placeholder is optional. `load(url, target)` loads without one and **empties the target first** — a reused view must not keep showing the previous item's image while the new one arrives. `NO_PLACEHOLDER` is the same thing for callers that compute the resource id and may not have one.

Two explicit overloads rather than a Kotlin default argument: `@JvmOverloads` is illegal on interface methods, and it only drops trailing parameters — so a default would have been omittable from Kotlin but never from Java, which the assignment does not allow.

### Kotlin

```kotlin
private val imageLoader = (application as RoundsApplication).imageLoader

imageLoader.load(item.imageUrl, R.drawable.image_placeholder, holder.imageView)
imageLoader.load(item.imageUrl, holder.imageView)     // no placeholder

// When a view is recycled or detached:
imageLoader.clear(holder.imageView)

imageLoader.invalidate(item.imageUrl)                 // forget one image
imageLoader.clearCache()                              // forget all of them
```

### Java

```java
ImageLoader loader = ImageLoader.create(context);

loader.load(item.getImageUrl(), R.drawable.image_placeholder, imageView);
loader.load(item.getImageUrl(), imageView);           // no placeholder

loader.clear(imageView);

loader.invalidate(item.getImageUrl());
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
join or create the shared load for this URL   <- one per URL + cache generation
        │
        │   ┌──────────── shared load, on the loader's scope ────────────┐
        └──▶│ download bytes   (IO dispatcher, HttpURLConnection)        │
            │ decode Bitmap    (IO dispatcher, BitmapFactory)            │
            │ store in memory + disk with one timestamp                  │
            └────────────────────────────────────────────────────────────┘
        ↓  main thread, once per waiting target
is this still the target's current request?
        ↓ yes                     ↓ no
apply the bitmap            discard the result
```

A valid memory hit is applied before `load()` even returns — the scope runs on `Dispatchers.Main.immediate` — so a warm cache repaints without a placeholder flash.

**Coroutine model.** The loader owns a single `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`. `SupervisorJob` keeps one failed or cancelled request from affecting any other. Network and decode work runs on `Dispatchers.IO`; every view mutation happens on the main dispatcher. `GlobalScope` is not used anywhere, and the scope holds no Activity, Fragment or view — the loader is a process-wide singleton created in `RoundsApplication`.

There are two kinds of coroutine, and keeping them apart is what makes concurrent loading correct. A **target request** is per-`ImageView`: it owns the token, the placeholder and the cancellation, and it only ever *awaits* a result. A **shared load** is per URL, lives on the loader's scope, and owns the download, the decode and the cache write. Several target requests can await one shared load.

**Placeholder.** Applied synchronously inside `load()`, before any coroutine starts — or the target is emptied when no placeholder was supplied. On failure — malformed URL, I/O error, non-2xx status, empty body, undecodable payload — the placeholder simply stays and nothing is thrown; the host app cannot be crashed by a bad image.

**Target safety.** Each request gets a monotonic token, stored together with its `Job` in the target `ImageView`'s own tag (no long-lived map keyed by views). Starting a new load cancels the previous request for that view, and a result is applied only if the target still belongs to the request that produced it — so a slow request that completes after a newer one can never overwrite it, regardless of completion order. This holds for memory, disk and network results alike: no tier has a shortcut past the check. `clear(target)` cancels the pending request and prevents its result from being applied. The view is held through a `WeakReference`, so an in-flight download cannot retain a detached view.

## Concurrency

**In-flight deduplication.** Concurrent loads that all miss the cache for the same URL share one underlying operation:

```text
load(A)  load(A)  load(A)      ->   1 download, 1 decode, 1 pair of cache writes
   │        │        │
   └────────┴────────┴──────── 3 independent targets, each checked before it is painted
```

Only a complete cache miss reaches the registry — a memory hit never consults it, and a disk hit is served without touching the network — so the warm path stays as cheap as it was. The shared unit is the image-producing operation, not the `ImageView` request: every consumer keeps its own token, placeholder, cancellation and stale-result check and simply awaits the result. Because the producer performs the cache write, the image is stored once no matter how many targets were waiting; a storage failure is still swallowed and the image still delivered.

The registry is a `ConcurrentHashMap` keyed by URL *and* observed cache generation. Per-key `compute` makes subscribe-or-create atomic, so two simultaneous misses cannot both start a download, while a consumer count tracks who still needs the result. Entries are removed on success and failure; releasing the final subscription removes and cancels unfinished work. The release transition is performed inside the same per-key atomic map operation as subscription, so a late release cannot cancel a new consumer or evict a newer entry. A failed load leaves no trace: the next request for that URL downloads again.

**Resource bounds.** At most two post-memory pipelines read encoded disk bytes or download, decode and store concurrently; additional URLs suspend on a cancellation-safe coroutine semaphore rather than retaining more `ByteArray` and `Bitmap` allocations. If every consumer of queued network work is recycled, its final subscription cancels that work and removes it from the registry, so a fast scroll cannot retain an unbounded obsolete backlog. Each HTTP response is limited to 32 MiB. A declared oversized `Content-Length` is rejected before reading, while premature EOF and bodies longer than declared are rejected as malformed. Unknown-length bodies are bounded while streaming into a temporary file in the managed `image_loader` cache directory and then read through the same open file descriptor into one exact-size `ByteArray`, avoiding a second full-body heap copy and surviving concurrent cache clearing/startup sweeping on Android. Immediate cleanup runs in `finally`; stale prefixed files are swept when the loader is initialized and `clearCache()` clears the managed directory. The supplied payload's largest valid image is approximately 23.6 MiB, so it remains supported.

**Cancellation.** A shared load is started on the loader's own scope, never as a child of the target request that happened to create it, and awaiting it establishes no parent/child link. So `clear(target)` — or a target being recycled onto another URL — releases only that consumer's subscription and result delivery. Work another target still needs is untouched; when the final consumer leaves, the shared operation is cancelled because nobody can use its result.

**Interaction with invalidation.** The cache generation is part of a shared load's identity, not just the URL. `invalidate(url)` bumps that URL's generation and `clearCache()` bumps the global one, so a load starting after either call observes different state, forms a different key, and starts its own operation rather than joining work the invalidation was meant to discard. The pre-invalidation load may still finish and paint the targets that started it — they asked before the cache was cleared — but the generation check refuses its cache write, exactly as it did before deduplication existed. Two downloads across an invalidation boundary is the correct outcome here, not a deduplication failure.

## Cache

**Memory.** `androidx.collection.LruCache` bounded to an eighth of the process heap, measured by `Bitmap.byteCount` so eviction tracks real memory pressure rather than entry count. Holds the decoded `Bitmap` plus the timestamp of the download it came from. (`android.util.LruCache` is a framework stub in JVM unit tests, which is why the androidx one is used.)

**Disk.** One file per URL under `context.cacheDir/image_loader`, named `SHA-256(url)` in hex — a raw URL is not a legal filename, and a digest is deterministic, fixed-length and collision-free in practice. Each file is an 8-byte big-endian timestamp followed by the **original encoded bytes** as downloaded; storing those instead of re-compressing the decoded bitmap avoids a needless encode, a format change and quality loss. Writes land in a temporary file that is renamed into place, so an interrupted write can never be read back as a valid image.

**Disk budget — 128 MiB, least recently used first.** The four-hour TTL bounds how *old* an entry may be, not how many bytes pile up, so after a completed write the entries are pruned until they fit `134,217,728` bytes again. The total is the sum of the on-disk lengths of the entry files themselves, 8-byte header included. Only *canonical* entries count and only they are ever evicted — a file whose entire name is a 64-character lowercase hex digest; HTTP response spools (`image-loader-response-*`), this cache's own atomic-write temporaries, unrelated files and directories are not counted and are never deleted by pruning, so it cannot unlink a transfer that is still running. Recency is persisted as the entry file's `lastModified`, set when a write completes and when a read actually serves the entry; misses, expired, future-dated, corrupt and failed reads do not promote anything. The value written is a **monotonic access stamp**, not the raw clock: every scan lifts a counter past the newest stamp it saw, so a wall-clock rollback cannot make a fresh write or a just-served hit look older than entries touched before it and get evicted first. Ties are broken by filename, so eviction order does not depend on how the filesystem enumerates the directory. A cache left over budget by an earlier version is pruned on the first disk operation rather than in `create()`, keeping the directory scan on the disk dispatcher instead of the calling thread. Afterwards the total is carried forward and a full directory scan happens only when a write actually threatens the budget, or every 32 writes; the running total is held **per cache directory**, so two loaders over one directory prune against the same number instead of each keeping a private, incomplete view of it. An image whose file would exceed the whole budget is not stored at all, and any previous entry for that URL is dropped rather than left behind pretending to be the new one. Pruning is best effort like the rest of the disk cache: a deletion the filesystem refuses is skipped and the pass continues, so if enough deletions are refused the cache can stay above the budget — it never turns that into a load failure.

Caching is best effort. A write that fails is swallowed and the image is still displayed — a cache problem must not become a UI failure. An entry that is expired, truncated, or whose bytes will not decode is deleted on encounter and treated as a miss.

**TTL — exactly four hours.** An entry is valid while `0 <= now - cachedAt < 4h`. At exactly four hours it is **expired**; the comparison is strict. The age floor matters as much as the ceiling: an entry stamped in the future — after a clock rollback or from restored cache metadata — would otherwise have a negative age and stay valid until real time caught up, so it is treated as invalid and dropped by whichever tier encounters it. The timestamp is taken once, only after a download *and* a decode both succeed, and the same value goes to both tiers. The TTL is fixed, not sliding: reading never rewrites it, and promoting an entry from disk into memory carries the original timestamp, so a disk hit cannot restart the window.

**Invalidation.** `clearCache()` and `invalidate(url)` empty memory and bump a generation counter synchronously — the cache is logically empty the moment the call returns — while the file deletion runs on the disk dispatcher. A load already in flight when the cache was invalidated still displays its image but is refused when it tries to store it, so it cannot resurrect what was just dropped. All disk work is serialised on a single-threaded dispatcher, so a deletion queued by an invalidation always completes before a read queued after it.

Both public calls are documented as callable from any thread, so "refused when it tries to store it" has to hold against an invalidation running *concurrently* with the store, not merely before it. Checking the generation and acting on the answer is therefore one transaction in each tier, and the boundary differs because the tiers are mutated from different places:

- **Memory.** The generation check and the `LruCache` write happen under the same lock that `clearCache()` and `invalidate(url)` hold while they bump the generation and drop entries. An invalidation is therefore either entirely before a store — and the check sees it — or entirely after it, and then removes what the store just put. It cannot land in between. Nothing suspends and no file is touched under that lock; it is only ever held across one `LruCache` operation, so it can neither block the caller on I/O nor deadlock against the disk pipeline.
- **Disk.** The check runs *inside* the operation dispatched to the disk queue, immediately before the write, rather than before the switch to that queue. An invalidation that has already bumped the generation is seen by that check; one that has not bumped it yet cannot have submitted its deletion either, and since the deletion is submitted before the public call returns, it is queued behind the write and still removes it. Either way the invalidation wins, so an already-authorised write can no longer overtake a deletion and resurrect the bytes it had just removed.

## Sample app data flow

The screen is a vertical list of rows, each showing the image the library loaded and the id the endpoint supplied, with a header button that clears the image cache. Android Views and XML throughout — no Compose anywhere in the project.

The app follows MVVM, and nothing above the repository knows how the list arrives:

```text
MainActivity                       (Android Views + ViewBinding)
        ↓ collects StateFlow<ImagesUiState> under repeatOnLifecycle(STARTED)
ImagesViewModel                    (viewModelScope, no Context, no View)
        ↓
ImagesRepository                   (DefaultImagesRepository)
        ↓
ImagesRemoteDataSource             (HttpImagesRemoteDataSource)
        ↓
GET image_list.json                (HttpURLConnection on Dispatchers.IO)
```

Images travel a completely separate path. The Activity hands the application-scoped loader to the adapter, and the adapter talks to nothing else:

```text
ImagesAdapter  ──load(url, placeholder, imageView)──▶  ImageLoader  ──▶  memory / disk / network
               ──clear(imageView) on recycle────────▶
```

**The endpoint.** `https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/image_list.json`, declared in one place — `HttpImagesRemoteDataSource`. It returns a bare JSON array whose records carry exactly two fields:

```json
[ { "id": 0, "imageUrl": "https://…/17_4691_….jpg" } ]
```

The field is `imageUrl`, the `id` is an integer, and the payload was inspected rather than assumed. Records are kept in the order the endpoint supplied them and duplicates are kept as-is: the live payload repeats several `imageUrl` values under different ids, so list identity has to come from `id`.

**Networking.** One GET does not justify Retrofit or OkHttp, so the data source uses `HttpURLConnection` directly: explicit connect/read timeouts, a checked status code, `use`-closed streams and a `disconnect()` in `finally`. The request *and* the parse both run inside `withContext(Dispatchers.IO)`, so nothing here can touch the main thread. It does not reuse the library's `HttpImageDownloader` — that class is internal to `:imageloader` and fetches image bytes; coupling the two modules through their networking internals would trade a real boundary for a few saved lines.

**Parsing** is a separate, pure `ImageListJsonParser` over the platform's `org.json` classes — no serialization stack, no reflection, and directly unit-testable without a server. A record missing `id` or `imageUrl`, or carrying one of the wrong type, **fails the whole response** rather than being skipped or defaulted. `id = 0` is a real record in this payload, so inventing `id = 0` or `imageUrl = ""` would be indistinguishable from real data, and silently dropping records would render a short list that looks complete. Either the response parses, or the screen says so.

**UI state.** The ViewModel exposes an immutable `StateFlow<ImagesUiState>`; the mutable one stays private.

| State | Meaning |
|---|---|
| `Loading` | a fetch is in flight — also the initial state |
| `Content(items)` | at least one record, in endpoint order |
| `Empty` | the fetch succeeded and returned nothing; not an error |
| `Error(messageRes)` | the fetch failed; a fixed string resource, never exception text or type |

Construction starts a fetch immediately: `Loading` → `Content` / `Empty` / `Error`. The state never stays at `Loading` after a failure.

**Reload and cancellation.** `reload()` cancels the fetch still in flight and starts a new one — latest request wins, the same rule the loader applies per target. The state moves to `Loading` synchronously before the new fetch begins, and a superseded fetch is barred from publishing its result, so a slow first request cannot overwrite a newer one whatever the completion order. `CancellationException` is rethrown rather than caught: a replaced request, or a cleared `viewModelScope`, is normal shutdown and must never be shown to the user as a failure.

`MainActivity` collects that flow inside `repeatOnLifecycle(STARTED)`, so collection stops when the screen is not visible and resumes with the current value afterwards. `render(state)` is one exhaustive `when`: it shows exactly one of the progress bar, the list, the empty message or the error block, and enables the cache button only while there is content to reload. The Activity keeps no list of its own — the ViewModel's state is the only source of truth, and `ListAdapter` simply holds what it was last given. Rotation therefore costs nothing: the ViewModel survives, the flow replays, and the list comes back without a refetch.

## The list

`ImagesAdapter` is a `ListAdapter` with a `DiffUtil.ItemCallback`, so ordinary updates are diffed rather than blanket-invalidated. **Identity is the `id`, never the url** — the endpoint repeats several urls under different ids, so comparing urls would fold two real rows into one. Contents compare with `==` on the data class.

The adapter is constructed with the application-scoped `ImageLoader` and nothing else: no Activity, ViewModel or repository, so it cannot leak a screen or reach past the library's public API. Binding a row does exactly two things — set the id label, and call `load(item.imageUrl, R.drawable.image_placeholder, imageView)`. There is no HTTP, no `BitmapFactory`, no cache inspection and no second request-token scheme anywhere in `:app`'s UI.

**Recycling.** `onViewRecycled` calls `clear(holder.binding.image)`, which cancels that view's pending request so a scrolled-past download is no longer awaited and a late result can never be painted onto the row's next item. Rebinding relies on the library's own contract: `load` cancels the target's previous request, and every result is checked against the target's current request before it is applied. Fast scrolling is safe because of those two calls, not because of anything the adapter tracks.

**Cache invalidation.** The header button calls `ImageLoader.clearCache()`, then asks the adapter to rebind its rows, then shows a Snackbar. The rebind is what makes the invalidation visible: bitmaps already attached to an `ImageView` would otherwise stay on screen and prove nothing. Every row drops back to its placeholder and loads again. This is the one place `notifyItemRangeChanged` is used — an explicit, user-triggered refresh — and it deliberately does **not** call `viewModel.reload()`: the JSON list did not become stale, only the images did.

**Two kinds of loading, two kinds of failure.** The progress bar means the *list* has not arrived; a row placeholder means that one *image* has not. Once the list is there the screen shows content immediately and each image fills in independently. A failed image leaves its placeholder in place and never turns `Content` into `Error` — the live payload actually contains one such record (id 4's url answers `403`), and the rest of the screen is unaffected by it.

## Architecture

`:app` sources live under `com.rounds.test.app`, split by layer:

```text
com.rounds.test.app
├── RoundsApplication         composition root
├── data/
│   ├── remote/               ImagesRemoteDataSource, HttpImagesRemoteDataSource
│   │   └── parser/           ImageListJsonParser
│   └── repository/           ImagesRepository, DefaultImagesRepository
├── model/                    ImageItem
└── presentation/
    ├── ui/                   MainActivity, ImagesAdapter, ImagesDiffCallback
    └── viewmodel/            ImagesUiState, ImagesViewModel
```

Nothing in `:app` is exported, so every type in it except `RoundsApplication` and the Activity is `internal`.

There is one model, not a DTO plus a domain class plus a mapper: the endpoint's two fields are exactly the two the screen shows, and an identical second class would add indirection and no meaning. It sits in a top-level `model/` package rather than under `data/` or `presentation/` so that neither layer owns it and the data layer never has to import a presentation type; the package is not called `domain` because there is no business logic or use-case layer to justify the name. There is no use case or interactor above the repository either — the repository *is* the boundary the ViewModel is written and tested against.

Dependency composition is **manual** — no Hilt, Dagger or Koin. `RoundsApplication` creates the single `ImageLoader` and the single `ImagesRepository` and hands them to whatever needs them. The ViewModel is built from `ImagesViewModel.factory(repository)`, a `viewModelFactory { … }` on the ViewModel's companion rather than a class of its own.

`:imageloader` internals:

```text
com.rounds.imageloader
├── ImageLoader            public API (the only exported type)
├── internal/              RealImageLoader (pipeline + coroutine ownership),
│                          InFlightRequestRegistry (shared loads)
├── network/               ImageDownloader, HttpImageDownloader
├── decode/                ImageDecoder, BitmapFactoryImageDecoder
├── cache/                 ImageCache, MemoryImageCache, DiskImageCache,
│                          Clock, CacheKey, TTL rule
└── request/               Target, ImageViewTarget, TargetRequest
```

Everything except `ImageLoader` is `internal`. The downloader, decoder and target abstractions exist because they are genuine external boundaries that must be substitutable in tests — not for symmetry.

## Build and run

Requires JDK 25 and the Android SDK (`local.properties` → `sdk.dir`) with Android API 37 installed. Toolchain: AGP 9.3.1, Gradle 9.7, `minSdk 24`, `compileSdk`/`targetSdk` 37, Java 11 bytecode. The Gradle daemon JVM is pinned in `gradle/gradle-daemon-jvm.properties`.

Linux and macOS:

```shell
./gradlew assembleDebug     # build the debug APK
./gradlew installDebug      # install on a connected device/emulator
```

Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

## Tests and checks

Linux and macOS:

```shell
./gradlew test                            # JVM unit tests (all modules)
./gradlew :imageloader:testDebugUnitTest  # image-loader tests only
./gradlew :app:testDebugUnitTest          # sample app tests only
./gradlew lint                            # Android Lint
```

Windows uses the same tasks through `gradlew.bat`, for example `.\gradlew.bat test`.

Every push to `main` and every pull request targeting `main` runs `test`, `lint`, and `assembleDebug` in GitHub Actions.

Everything is a JVM unit test — there is no instrumented source set. The Android-framework types this project actually needs to exercise (`Bitmap`, `ImageView`) sit behind small internal seams instead, so the interesting logic runs on the JVM in milliseconds; see the trade-offs below.

The app tests never touch the production endpoint:

- `ImageListJsonParser` is driven by literal JSON fixtures — endpoint order, repeated urls, an empty array, unknown fields, and every way a required field can be absent or the wrong type, including one bad record among good ones;
- `HttpImagesRemoteDataSource` runs against a JDK `HttpServer` on an ephemeral loopback port, the same pattern the library's downloader tests use rather than a second networking-test philosophy: valid JSON, an empty list, a malformed body, an empty body, 404, 500, an unreachable port, and that the request really is a GET;
- `ImagesViewModel` runs on a `StandardTestDispatcher` installed as the main dispatcher, with a fake repository whose every call is released by hand — so `Loading → Content`, `Loading → Empty`, `Loading → Error`, retry-after-error, "a superseded fetch cannot overwrite a newer one" and "cancellation is not an error" are all decided by the test, with no delays and nothing timing-sensitive;
- `ImagesDiffCallback` is unit-tested on the JVM, because list identity is the one place this payload can catch a reasonable implementation out: two ids sharing one url must stay two rows;
- `org.json` inside `android.jar` is a stub that throws, so the reference implementation is on the unit-test classpath only; production uses the platform class.

Adapter binding and recycling are not unit-tested. Doing so would mean inflating real views, which is exactly the reason this project avoids Robolectric (see the trade-offs below); they were verified on an emulator instead — startup, placeholder, fast scrolling with no mismatched rows, warm-cache scrolling, cache invalidation and reload, rotation, and error-then-retry with connectivity toggled off and on.

The image-loader tests are deterministic and offline:

- the load pipeline runs on a single `StandardTestDispatcher` with fake downloader/decoder/target, so execution order is controlled by the test rather than by timing — covering placeholder, loading without a placeholder, success, download failure, decode failure, blank URL, request replacement, stale-result rejection, `clear()` and failure isolation between targets;
- cache behaviour is driven by an injectable `Clock`, never by waiting: the four-hour boundary is asserted at 4h−1ms, exactly 4h and 4h+1ms, future-dated entries are asserted to be a miss in both tiers, and repeated reads are shown not to extend it. Tier ordering (memory hit skips disk and network, disk hit skips the network, expired entries re-download), timestamp preservation on disk→memory promotion, generation-checked corrupt-entry cleanup, "failures are never cached", "a disk write failure still displays the image", and invalidation races are covered too;
- concurrency is asserted by download and decode counts rather than by racing threads: three simultaneous loads of one URL produce exactly one download and one decode and paint all three targets; two URLs stay independent; the global limiter admits only its configured number of pipelines and releases permits on cancellation; clearing one consumer mid-flight leaves the others painted; a recycled target still cannot be overwritten by the shared result it used to be waiting for; a failed shared load is retried by the next request; and a load started after `invalidate(url)` or `clearCache()` is shown *not* to join the pre-invalidation work;
- the in-flight registry is also tested directly, which is where entry release after success/failure, one-of-many consumer cancellation, and cancellation of a 20-entry limiter backlog after every final consumer leaves are provable rather than inferred;
- the decode bound that keeps an oversized image from crashing the host is asserted directly — full-size decoding below the limit, halving above it, powers of two only, and the supplied payload's 11000×7000 record proven to land under the platform's 100MB draw limit;
- the disk cache runs against a real temporary directory — real files, real atomic renames, real corrupt-file handling — not a mocked filesystem. The 128 MiB budget is asserted with tiny injected budgets: exactly-full is kept, the least recently used entry is evicted, a read moves an entry out of the firing line, equal access times fall back to filename order, a replacement is counted once, an oversized image is stored not at all, an over-budget directory is pruned on the first operation rather than in the constructor, and spools, temporaries, lookalike names and directories survive every pass;
- `HttpImageDownloader` is exercised against a JDK `HttpServer` on an ephemeral loopback port — real HTTP, no production endpoint, no public image host — including the exact response-size boundary, oversized declared bodies, oversized chunked bodies, and temporary-file cleanup on success and failure;
- three tests are written in Java to verify the API is usable from Java, including the placeholder-free overload and both cache-invalidation calls;
- the thread contract is asserted against the *compiled* `ImageLoader`, by reading the `@MainThread`/`@AnyThread` descriptors out of the class file's annotation attributes. AndroidX thread annotations have `CLASS` retention, so a reflection test would see nothing; reading the class file checks what Android Lint actually consumes, and looking each method up by its exact JVM descriptor makes the same test a binary-compatibility guard.

## Assumptions and trade-offs

The assignment fixes the requirements but leaves the design open. The following are **engineering decisions made here**, not literal requirements from the brief: two cache tiers rather than one, in-flight deduplication, response and concurrency limits, per-URL invalidation alongside the mandatory full clear, the injectable `Clock`, rejecting future-dated cache timestamps, and the split into a separate `:imageloader` module.

- **Placeholder is a `@DrawableRes Int`, optional via a second overload.** A `Drawable` overload adds API surface without demonstrating anything new; the assignment asks for a placeholder resource.
- **No returned request handle.** `clear(target)` covers the cancellation the assignment needs, and keeps the Java API small.
- **Memory + disk caching is an engineering decision.** The assignment requires "cache the image"; two tiers are the choice made here — memory for repeated access in-process, disk to survive a restart.
- **The disk budget is 128 MiB, and it is an engineering decision.** The assignment fixes the TTL, not a size; a TTL alone bounds age rather than bytes, so a long scroll over distinct URLs could fill the application cache directory until Android reclaimed it. Recency rides on the entry file's `lastModified` instead of a journal or a database — no new file format, no extra dependency, and it survives a restart. The cost is that the bound is best effort: the filesystem can refuse a deletion, and only canonical entries are eligible, so bytes owned by in-flight transfers — response spools and atomic-write temporaries — sit outside the budget until the write that owns them finishes or `clearCache()` runs. Age is deliberately not treated as proof that such a file is abandoned: a large transfer can stall for minutes, and unlinking its pathname would break the rename that completes it.
- **In-flight deduplication is an engineering decision.** The assignment asks for downloading and caching; sharing one download between concurrent requests for the same URL is our choice, made because a scrolling list produces exactly that pattern.
- **Encoded-byte work is bounded.** The loader permits two concurrent disk-read/decode or cache-miss pipelines and rejects an encoded response above 32 MiB. The limit includes the supplied 24,784,384-byte source while preventing an arbitrary response or a fast scroll across many distinct URLs from growing transient pipeline memory without bound.
- **Shared work is reference-counted.** Cancelling one target never interrupts work another target still awaits. Cancelling the final target removes and cancels the operation, including work queued behind the global semaphore; an already-blocking socket read may finish before coroutine cancellation is observed, but it cannot proceed to decode or cache afterward.
- **Decoding is bounded, but not resized to the target view.** Images are decoded at full resolution up to 2048px on either edge; beyond that `BitmapFactory.inSampleSize` halves them until they fit. This is a safety bound rather than a resizing pipeline — the 1920×1080 wallpapers that make up most of the payload are decoded untouched — and it is **not** optional: the supplied list contains an 11000×7000 record (id 47) which decodes to 308MB, and any bitmap over 100MB makes `ImageView.onDraw` throw `Canvas: trying to draw too large bitmap` on the UI thread, crashing the host app where no `catch` in this library could intercept it. Scaling each decode down to the exact row size would save more memory still, but the loader decodes once and shares the result between targets that may differ in size, so a per-view scale belongs to a later design, not to this bound.
- **All disk work is serialised on a single-threaded dispatcher.** That is what makes invalidation ordering deterministic — a deletion queued by `clearCache()` always completes before a read queued after it, so a pending delete can never let a stale file be served. It costs parallel disk reads during a fast scroll, which is the cheaper half of the trade: the alternative is a lock protocol between reads, writes and deletions with no ordering guarantee to show for it.
- **`Bitmap`/`ImageView` cannot be instantiated on the JVM.** Rather than adding Robolectric, view mutation sits behind a small internal `Target` seam so the interesting logic is plain-JVM testable; Mockito is used only to fabricate a `Bitmap` instance for identity assertions.
- **No instrumented test source set.** The generated `androidTest` stub asserted only its own package name, so it was removed rather than left in place to imply coverage that does not exist. The behaviour it could have covered — adapter binding and recycling — was verified manually on an emulator instead, as listed above.
- **Cross-protocol redirects are not followed.** `HttpURLConnection` does not follow an HTTP→HTTPS redirect; such a response is treated as a failure, which is safe rather than surprising.
- **Cancellation of a request in flight does not interrupt a blocking socket read.** The result is discarded and the connection is closed when the read returns; interrupting the read would add complexity out of proportion to the benefit here.
