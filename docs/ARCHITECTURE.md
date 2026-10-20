# Architecture

## Overview

```
                       ┌──────────────── host app (Anikku) ────────────────┐
 index.min.json ──────►│ ExtensionApi  → install APK → ExtensionLoader     │
 apk/, icon/           │                         │ reflection (extClass)   │
                       │  runtime: extensions-lib impl, OkHttp, Jsoup, ... │
                       └─────────────────────────┼─────────────────────────┘
                                                 ▼
 src/<lang>/<name>  (one APK per module) ── AnimeHttpSource subclass
        │ implementation                         │
        ├── :core            aniext.core.*  (rate limit, retry, errors, JSON, URLs, text)
        ├── :lib:<name>      reusable helpers (none yet)
        └── :lib-multisrc:<theme>  shared CMS implementation (none yet)
```

Everything the host app already ships (extensions-lib, OkHttp, Jsoup, kotlinx.serialization,
coroutines) is `compileOnly` (`libs.bundles.provided`). Only `core`, libs and themes are packaged
into an extension APK.

## Repository layout

| Path                         | Purpose                                                                                    |
|------------------------------|--------------------------------------------------------------------------------------------|
| `gradle/build-logic/`        | Convention plugins: `ase.extension`, `ase.library`, `ase.multisrc`, `ase.android.base`, `ase.spotless` |
| `gradle/libs.versions.toml`  | Third-party versions; `bundles.provided` = runtime libraries the app provides              |
| `gradle/ase.versions.toml`   | SDK levels, Java target, repository lib level, plugin ids                                  |
| `common/`                    | Extension manifest stub and shared R8 rules                                                |
| `core/`                      | `aniext.core` shared library + manifest metadata and default launcher icon                 |
| `src/<lang>/<name>/`         | One extension module: `build.gradle`, `src/`, `test/`, optional `res/`, `README.md`       |
| `lib/`, `lib-multisrc/`      | Reusable libs and multi-source themes (included automatically when present)                |
| `template/extension/`        | Templates rendered by `tools/new_source.py`                                                |
| `tools/`                     | Validation, matrix, catalog and scaffolding scripts (stdlib Python ≥ 3.12) + tests         |
| `catalog/repo-meta.json`     | Repository display metadata for `repo.json`                                                 |
| `sources/registry.json`      | Audit/discovery registry of every candidate source; rendered to `docs/SOURCE_AUDIT.md`      |
| `.github/workflows/`         | `validate`, `build-extensions`, `catalog` (reusable), `release`                             |

## Extension module

`build.gradle` holds only literal metadata (parsed both by Gradle and by `tools/repo_model.py`):

```groovy
ext {
    extName = 'Internet Archive'   // display name, romanized
    extClass = '.InternetArchive'  // relative to eu.kanade.tachiyomi.animeextension.<lang>.<name>
    extVersionCode = 1             // bump once per user-visible change
    libVersion = 16                // extensions-lib level
    isNsfw = false
}
apply plugin: 'ase.extension'
```

`PluginExtension` derives everything else: application id
`eu.kanade.tachiyomi.animeextension.<lang>.<name>`, version name `<libVersion>.<versionCode>`,
APK base name `aniyomi-<lang>.<name>-v<versionName>`, manifest placeholders, a generated R8
keep rule for `extClass`, release signing from CI secrets, reproducible packaging. Invalid metadata
fails configuration with a message naming the module.

Source sets: main code in `src/`, unit tests in `test/kotlin`, fixtures in `test/resources`.
Provided libraries are also on the unit-test classpath so plain data classes (`Video`, `Track`,
`AnimeFilter`) are usable in tests; stubbed classes (`SAnime.create()`, `network`) are not.

### Layering inside a source

Sources keep the API-specific logic in a **pure layer** that does not touch stubbed classes, and a
**thin adapter** (the `AnimeHttpSource` subclass) that maps results to `SAnime`/`SEpisode`/`Hoster`.
In `src/all/internetarchive`:

| File              | Role                                                                     | Tested by          |
|-------------------|--------------------------------------------------------------------------|--------------------|
| `ArchiveApi.kt`   | URLs, query construction and escaping, identifier validation, path codec | `ApiTest`          |
| `Dto.kt`          | `@Serializable` response models (tolerant to string/array fields)        | via parser tests   |
| `ItemParser.kt`   | Search paging, availability checks, details, episode grouping, videos    | `ItemParserTest`   |
| `Filters.kt`      | Filter definitions → `SearchParams`                                       | `FiltersTest`      |
| `InternetArchive.kt` | Adapter: requests, mapping, retry around metadata fetches             | compile + inspector + `LiveApiTest` (opt-in) |

## Shared library: `aniext.core`

| API                                                     | Use                                                                 |
|---------------------------------------------------------|---------------------------------------------------------------------|
| `OkHttpClient.Builder.rateLimit(permits, period) { url -> }` | Per-host sliding-window limiter (lib 16's own is a no-op)        |
| `withRetry(policy) { }`, `RetryPolicy`, `isTransientNetworkFailure()` | Bounded exponential backoff with coroutine `delay`; retries only timeouts, 408/425/429/5xx and I/O errors |
| `SourceException` → `HttpStatusException`, `InvalidResponseException`, `MissingFieldException`, `ContentUnavailableException` | Distinct, user-safe error semantics |
| `Response.requireSuccess()`, `HttpUrl.redacted()`       | Status checks with query strings/credentials removed from messages |
| `String/Response.parseAs<T>()`, `sourceJson`, `StringOrListSerializer` | Tolerant JSON decoding with contextual errors             |
| `resolveUrl(base, href)`, `toSafeHttpsUrlOrNull()`      | URL normalization rejecting `javascript:`, `data:`, credentials     |
| `htmlToText`, `naturalOrder`, `SimpleDateFormat.tryParse` | Description cleanup, episode ordering, dates (0 = unknown)        |
| `AnimeSource.sourcePreferences()`                       | The app's per-source `SharedPreferences` (via Injekt; resolve lazily) |

Rules: add to `core` only what at least one source uses; every public function has a unit test.

## Registry and modules

Discovery and implementation are separate. `sources/registry.json` records each candidate with
category (requested site, A official platform, B metadata API, C user media server, D open
collection, E community site), status, dated evidence, permission findings, capabilities and next
action. `tools/source_registry.py validate` enforces that only READY/LIMITED entries name a module,
that every module has such an entry with tests and fixtures and the repository lib level, and (with
`--catalog`) that the published catalog contains exactly those modules.

## Themes and libs

`ase.multisrc` and `ase.library` exist so that shared CMS logic can be added without build changes,
but no theme or lib exists yet: the two sources use unrelated APIs, and sites must be shown to share
markup or API contracts (not just look similar) before a theme is justified.

## Error semantics

| Situation                                  | Behaviour                                                       |
|--------------------------------------------|-----------------------------------------------------------------|
| Non-2xx response                           | App's `awaitSuccess` throws `HttpException(code)`; metadata calls retry transient codes |
| Malformed / unexpected JSON                | `InvalidResponseException("Unexpected data in <redacted url>…")` |
| API-level error object (e.g. deep paging)  | `InvalidResponseException` with the API's message               |
| Required field absent (title, response)    | `MissingFieldException`; list items lacking one are skipped     |
| Removed, dark or restricted item           | `ContentUnavailableException`                                   |
| No results / no playable files             | Empty page / empty list (the app shows its localized message)  |
| Optional field absent (thumbnail, genre…)  | Left `null`; never invented                                     |
