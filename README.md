# rounds-test

Android home assignment: a custom image downloading/caching library plus a sample application that demonstrates it.

The library is written from scratch — no Glide, Coil, Picasso or Fresco, and no Retrofit/OkHttp either. Downloading is `HttpURLConnection`, decoding is `BitmapFactory`. The sample app uses Android Views/XML (no Jetpack Compose) and MVVM.

> **Status — image-loader core (MR #2).**
> The library downloads, decodes and displays remote images with placeholder support and basic
> target-request protection. **Caching, the four-hour TTL and cache invalidation are not implemented
> yet**; neither is in-flight request deduplication, and the image list screen is still to come.
> Every `load()` currently performs a fresh download.

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android application | Sample app: image list retrieval, MVVM presentation, RecyclerView UI, cache invalidation control. Consumes the library through its public API only. |
| `:imageloader` | Android library | Reusable image loading: request API, HTTP download, decoding, and later memory + disk cache, TTL, invalidation, request lifecycle. |

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
    fun clear(target: ImageView)

    companion object {
        const val NO_PLACEHOLDER: Int = 0
        @JvmStatic fun create(): ImageLoader
    }
}
```

The placeholder is optional. `load(url, target)` loads without one and **empties the target first** — a reused view must not keep showing the previous item's image while the new one arrives. `NO_PLACEHOLDER` is the same thing for callers that compute the resource id and may not have one.

Two explicit overloads rather than a Kotlin default argument: `@JvmOverloads` is illegal on interface methods, and it only drops trailing parameters — so a default would have been omittable from Kotlin but never from Java, which the assignment does not allow.

There is deliberately **no `clearCache()` yet** — a method that pretends to clear a cache which does not exist would be worse than its absence. It is added in MR #3 together with the cache itself.

### Kotlin

```kotlin
private val imageLoader = (application as RoundsApplication).imageLoader

imageLoader.load(item.url, R.drawable.placeholder, holder.imageView)
imageLoader.load(item.url, holder.imageView)          // no placeholder

// When a view is recycled or detached:
imageLoader.clear(holder.imageView)
```

### Java

```java
ImageLoader loader = ImageLoader.create();

loader.load(item.getUrl(), R.drawable.placeholder, imageView);
loader.load(item.getUrl(), imageView);                // no placeholder

loader.clear(imageView);
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
download bytes            (IO dispatcher, HttpURLConnection)
        ↓
decode Bitmap             (IO dispatcher, BitmapFactory)
        ↓  main thread
is this still the target's current request?
        ↓ yes                     ↓ no
apply the bitmap            discard the result
```

**Coroutine model.** The loader owns a single `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`. `SupervisorJob` keeps one failed or cancelled request from affecting any other. Network and decode work runs on `Dispatchers.IO`; every view mutation happens on the main dispatcher. `GlobalScope` is not used anywhere, and the scope holds no Activity, Fragment or view — the loader is a process-wide singleton created in `RoundsApplication`.

**Placeholder.** Applied synchronously inside `load()`, before any coroutine starts — or the target is emptied when no placeholder was supplied. On failure — malformed URL, I/O error, non-2xx status, empty body, undecodable payload — the placeholder simply stays and nothing is thrown; the host app cannot be crashed by a bad image.

**Target safety.** Each request gets a monotonic token, stored together with its `Job` in the target `ImageView`'s own tag (no long-lived map keyed by views). Starting a new load cancels the previous request for that view, and a result is applied only if the target still belongs to the request that produced it — so a slow request that completes after a newer one can never overwrite it, regardless of completion order. `clear(target)` cancels the pending request and prevents its result from being applied. The view is held through a `WeakReference`, so an in-flight download cannot retain a detached view. Full in-flight deduplication and shared request lifecycle are MR #4.

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
- `HttpImageDownloader` is exercised against a JDK `HttpServer` on an ephemeral loopback port — real HTTP, no production endpoint, no public image host;
- two tests are written in Java to verify the API is usable from Java, including the placeholder-free overload.

## Assumptions and trade-offs

- **Placeholder is a `@DrawableRes Int`, optional via a second overload.** A `Drawable` overload adds API surface without demonstrating anything new; the assignment asks for a placeholder resource.
- **No returned request handle.** `clear(target)` covers the cancellation the assignment needs, and keeps the Java API to two methods.
- **`Bitmap`/`ImageView` cannot be instantiated on the JVM.** Rather than adding Robolectric, view mutation sits behind a small internal `Target` seam so the interesting logic is plain-JVM testable; Mockito is used only to fabricate a `Bitmap` instance for identity assertions.
- **Cross-protocol redirects are not followed.** `HttpURLConnection` does not follow an HTTP→HTTPS redirect; such a response is treated as a failure, which is safe rather than surprising.
- **Cancellation of a request in flight does not interrupt a blocking socket read.** The result is discarded and the connection is closed when the read returns; interrupting the read would add complexity out of proportion to the benefit here.
