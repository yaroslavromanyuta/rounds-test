# rounds-test

Android home assignment: a custom image downloading/caching library plus a sample application that demonstrates it.

The library is written from scratch — no Glide, Coil, Picasso or Fresco. The sample app uses Android Views/XML (no Jetpack Compose) and MVVM.

> **Status — architecture scaffolding only.**
> This repository currently contains the project structure and module boundaries established by MR #1.
> Image downloading, bitmap decoding, caching, the four-hour TTL, cache invalidation, request concurrency
> and the image list screen are **not implemented yet**. They are delivered incrementally in later merge
> requests — see [docs/07-agent-workflow.md](docs/07-agent-workflow.md).

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android application | Sample app: image list retrieval, MVVM presentation, RecyclerView UI, cache invalidation control. Consumes the library through its public API only. |
| `:imageloader` | Android library | Reusable image loading: request API, HTTP download, decoding, memory + disk cache, TTL, invalidation, request lifecycle. |

Dependency direction:

```text
:app
  ↓
:imageloader
```

`:imageloader` must never depend on `:app`. It is a standalone library that happens to be demonstrated by the sample app.

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

`:app` sources live under `com.rounds.test.app`, split by layer — `presentation.ui` today; `data.remote`, `data.repository`, `presentation.model`, `presentation.viewmodel` are added by the merge requests that populate them.

Dependency composition is **manual** — no Hilt, Dagger or Koin. The `ImageLoader` will be constructed once at application scope and passed explicitly to the components that need it.

Design constraints held across every merge request:

- no third-party image-loading framework — `HttpURLConnection` + `BitmapFactory`;
- Android Views/XML, never Compose;
- Kotlin Coroutines for asynchronous work;
- the public library API must be genuinely usable from Java, not coroutine-only.

## Build and run

Requires the Android SDK (`local.properties` → `sdk.dir`) and JDK 21 for the Gradle daemon. Toolchain: AGP 9.3.1, Gradle 9.5, `minSdk 24`, `compileSdk`/`targetSdk` 37.

```powershell
.\gradlew.bat assembleDebug     # build the debug APK
.\gradlew.bat installDebug      # install on a connected device/emulator
```

## Tests and checks

```powershell
.\gradlew.bat test              # JVM unit tests (all modules)
.\gradlew.bat lint              # Android Lint; report at app/build/reports/lint-results-debug.html
.\gradlew.bat connectedAndroidTest   # instrumented tests (needs a device/emulator)
```
