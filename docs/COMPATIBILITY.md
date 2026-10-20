# Compatibility

What the extensions in this repository are built against, and how that was verified.

## Target application

| Item                   | Value                                                                                       |
|------------------------|---------------------------------------------------------------------------------------------|
| Host app               | [Anikku](https://github.com/komikku-app/anikku) (Aniyomi fork); any app accepting lib 16       |
| Extension API          | `com.github.komikku-app:aniyomi-extensions-lib:f5961b5dfb` (JitPack), **extensions-lib 16**    |
| Lib level declared     | `16` (`gradle/ase.versions.toml` → `extension-lib`), version name `16.<versionCode>`           |
| Lib levels app accepts | 12 – 16 (`ExtensionLoader.LIB_VERSION_MIN/MAX` in Anikku)                                     |
| Android SDK            | min 24, compile/target 34                                                                    |
| Toolchain              | Gradle 9.7.1 (checksum-pinned wrapper), AGP 9.3.2, Kotlin 2.3.21, JDK 21 (bytecode target 11) |

Versions mirror those the reference repository builds with today. `kotlinx-serialization` is held
at 1.7.3 on purpose: it must match what the host app ships, and 1.8+ changes how interface
default methods are generated for `compileOnly` consumers.

## How compatibility was verified

All of the following were checked against primary sources, not inferred from names:

- **API surface** — read from the published `-sources.jar` of the pinned extensions-lib. In lib 16
  `AnimeHttpSource` declares `seasonListParse` and `hosterListParse` as abstract, the episode→video
  flow is `getHosterList(episode)` → `getVideoList(hoster)` → optional `resolveVideo(video)`, and
  `Video` uses the named constructor (`videoUrl`, `videoTitle`, `resolution`, `subtitleTracks`,
  `initialized`, …). All lib classes are stubs that throw `Stub!` except plain data classes
  (`Video`, `Track`, `Hoster`, `AnimesPage`, `AnimeFilter*`, `HttpException`).
- **Loader contract** — from Anikku's `ExtensionLoader.kt`: the APK must declare the
  `tachiyomi.animeextension` feature and the `tachiyomi.animeextension.class` and
  `.nsfw` meta-data; the lib level is parsed from `versionName.substringBeforeLast('.')`.
  `core/src/main/AndroidManifest.xml` provides these to every extension.
- **Catalog contract** — from Anikku's `ExtensionApi.kt` (see [DISTRIBUTION.md](DISTRIBUTION.md)).
- **Built artifact** — `aapt dump badging` of the release APK shows package
  `eu.kanade.tachiyomi.animeextension.all.internetarchive`, `versionName='16.1'`, the feature and
  both meta-data entries.
- **Instantiation outside the app** — the pinned extensions inspector (v1.5.0.2) loaded the
  R8-minified release APK, constructed the source and reported its id. That id equals the value
  computed by `tools/repo_model.py:source_id`, which also reproduces ids published in the reference
  catalog.

Not verified here: installing the APK into a running Anikku build on a device (no emulator in the
development environment). This is the first item of the next milestone in
[IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md).

## Known API pitfalls (verified)

| Pitfall                                                                                   | Consequence / rule                                                                   |
|-------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `eu.kanade.tachiyomi.network.interceptor.rateLimit` is a deprecated **no-op** in lib 16     | Use `aniext.core.network.rateLimit`; `validate_extensions.py` rejects the import      |
| `rateLimit` predicates receive an `HttpUrl`                                                 | Compare `it.host`, never a full URL                                                   |
| `android.*` objects created in constructors throw `Stub!` in the inspector                  | Use `by lazy` (the client in `InternetArchive` is lazy)                              |
| `Video.initialized` defaults to `false`                                                     | Set `initialized = true` for URLs that need no `resolveVideo`                         |
| Changing a source's `name` or `lang` changes its generated `id`                             | Override `id` with the old value when renaming (see DISTRIBUTION.md)                  |
