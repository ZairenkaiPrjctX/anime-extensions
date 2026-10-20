# Contributing

Thanks for helping. This guide is what you need to add, test and maintain a source without
reverse-engineering the repository. Background: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Ground rules

- **Legitimate access only.** Use official/documented APIs, public feeds or public server-rendered
  pages, in that order of preference, and respect the provider's terms and rate limits. Never
  implement DRM, paywall, login or CAPTCHA circumvention, or anything designed to defeat access
  controls. Open a "Source request" issue first if unsure.
- **No invented data.** Title and URL of an anime, and name and URL of an episode, must come from the
  source. If they are missing, skip the item (lists) or throw `MissingFieldException` (details).
  Optional fields (thumbnail, genres, description) stay `null` when absent.
- **No secrets** in code, fixtures, logs or exception messages. Use `HttpUrl.redacted()` when a URL
  appears in a message.
- **No TLS weakening**, no `Thread.sleep`, no hard-coded User-Agent (`headers` already carries the
  app's). `tools/validate_extensions.py` enforces several of these.

## Source registry (before any code)

Every candidate source gets an entry in [`sources/registry.json`](sources/registry.json) first,
with evidence and a status:

| Status            | Meaning                                                                 | Module allowed |
|-------------------|-------------------------------------------------------------------------|----------------|
| `READY`           | Access method and permission verified                                   | yes            |
| `LIMITED`         | Usable with documented limits                                           | yes            |
| `REVIEW_REQUIRED` | Licensing, terms or authorization unresolved — state the exact question | no             |
| `BLOCKED`         | Cannot proceed under the applicable terms/restrictions                  | no             |
| `UNREACHABLE`     | Could not be inspected (blocked, down)                                  | no             |

Evidence means observations anyone can repeat (HTTP status, quoted terms, robots.txt rules), with
the date. Do not infer licensing from a site's name or look. Entries are never deleted when a source
fails review; they stay visible with their status. After editing:

```bash
python3 tools/source_registry.py validate
python3 tools/source_registry.py render      # regenerates docs/SOURCE_AUDIT.md
```

CI rejects modules without an eligible entry, eligible entries without tests and fixtures, and a
catalog that publishes anything else.

## Writing a source

1. Scaffold it:
   ```bash
   python3 tools/new_source.py <lang> <name> --display-name "Site Name" --base-url https://site.example
   ```
   `<lang>` is an ISO 639 code (`en`, `id`, …), or `all` for multi-language sources. `<name>` is
   lowercase letters and digits. The package is `eu.kanade.tachiyomi.animeextension.<lang>.<name>`.
2. Verify the site's data contract **before** writing selectors or DTOs: fetch real responses,
   confirm pagination, required headers, and how unavailable content is represented.
3. Put request/response logic in pure Kotlin (URLs, DTOs, parsing to plain models), and keep the
   `AnimeHttpSource` subclass a thin adapter. This is what makes it testable — the extensions-lib
   classes `SAnime`, `SEpisode` and `network` are stubs in unit tests.
4. Implement the lib 16 flow:
   - popular / latest (`supportsLatest`) / search with `getFilterList()` whose default state matches
     the popular listing;
   - details (`initialized = true` when overriding `getAnimeDetails`);
   - episodes, **sorted descending**, `date_upload` in epoch millis or `0`;
   - `seasonListParse` throws `UnsupportedOperationException()` unless the site has seasons;
   - `getHosterList` → `getVideoList(hoster)`; use `videos.toHosterList()` when there is a single
     server; build `Video(videoUrl = …, videoTitle = …, resolution = …, headers = …, initialized = true)`.
5. Network: build `client` `by lazy` from `network.client.newBuilder()` and add
   `aniext.core.network.rateLimit(permits) { it.host == "…" }`. Wrap fetches that override suspend
   functions in `withRetry { }` (bounded, transient failures only).
6. Store relative, stable URLs in `SAnime.url`/`SEpisode.url` (ids or paths, not absolute URLs).
7. Tests: record real responses, sanitize them (strip e-mail addresses, tokens, server internals),
   save under `test/resources/fixtures/` with a README listing the source request of each file, and
   assert on mapped output and edge cases. Optional live checks must be gated by `ASE_LIVE_TESTS=1`.
8. Document the module in its `README.md`: access method, pagination, headers, limitations, rights.
9. Icons: keep the default from `:core`, or add all five `res/mipmap-*/ic_launcher.png` densities
   (`python3 tools/make_icon.py src/<lang>/<name>/res --color RRGGBB` makes a neutral one). Do not
   use third-party logos you have no right to redistribute.

## Before opening a pull request

```bash
./gradlew -Pextensions=<lang>:<name> :src:<lang>:<name>:spotlessCheck :src:<lang>:<name>:testDebugUnitTest :src:<lang>:<name>:assembleDebug
python3 tools/validate_extensions.py
python3 -m unittest discover -s tools/tests -t tools/tests   # if you touched tools/
```

Then fill in the PR checklist. Versioning (bump `extVersionCode` once per PR, never rename packages,
preserve ids on rename) is described in [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md#versioning-rules).

## Shared code

- `core` (`aniext.core.*`): add a helper only when a source needs it and it is generic; include unit
  tests and KDoc. Changes rebuild every extension in CI.
- `lib/<name>` (`ase.library`) for reusable helpers used by several sources.
- `lib-multisrc/<theme>` (`ase.multisrc`, with `baseVersionCode` in its `build.gradle.kts`) only when
  two or more sites are shown to share the same CMS markup or API, not just a similar look.
  Extensions then set `themePkg = '<theme>'` and `overrideVersionCode` instead of `extVersionCode`.

## CI

| Workflow           | Runs on                | Does                                                                  |
|--------------------|------------------------|-----------------------------------------------------------------------|
| `validate`         | PRs, pushes to `main`  | tool tests, metadata and registry validation, audit-doc freshness, actionlint, ktlint, core tests, template smoke build |
| `build-extensions` | PRs, pushes, manual    | builds/tests only affected modules (manual: chosen modules or `all`), uploads debug APKs |
| `release`          | pushes to `main`, manual | release APKs of all modules → `catalog` → gated publish to `repo` branch |
| `catalog`          | called by `release`    | inspector + catalog generation + validation                           |
