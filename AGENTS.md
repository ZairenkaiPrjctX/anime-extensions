# AGENTS.md

Agent-oriented summary. Source of truth: [CONTRIBUTING.md](CONTRIBUTING.md) and `docs/`.

## Project

Anikku/Aniyomi **extensions-lib 16** anime extensions in Kotlin. One Gradle module and APK per
source at `src/<lang>/<name>`; shared helpers in `core` (`aniext.core.*`); convention plugins in
`gradle/build-logic`; Python tooling in `tools/`.

## Commands (verified)

Scope every Gradle call to the module you touch with `-Pextensions=<lang>:<name>`:

```bash
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:compileDebugKotlin
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:testDebugUnitTest
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:assembleDebug
./gradlew -Pextensions=all:jellyfin :src:all:jellyfin:testDebugUnitTest
./gradlew :core:testDebugUnitTest
./gradlew spotlessCheck            # spotlessApply rewrites files; re-read them afterwards
python3 -m unittest discover -s tools/tests -t tools/tests
python3 tools/validate_extensions.py
python3 tools/new_source.py <lang> <name> --display-name "…" --base-url https://…
python3 tools/source_registry.py validate && python3 tools/source_registry.py render --check
```

## Rules that are easy to get wrong

- Extend `AnimeHttpSource` and implement the lib 16 hoster flow; `libVersion = 16` in `build.gradle`.
- Use `aniext.core.network.rateLimit` — the extensions-lib `rateLimit` is a no-op in lib 16.
- `SAnime.create()`, `SEpisode.create()`, `network`, `headers` throw `Stub!` in unit tests: test the
  pure parsing layer (see `src/all/internetarchive/.../ItemParser.kt`).
- Create the `client` and any `android.*` object `by lazy`, or the CI inspector cannot load the APK.
- Never invent mandatory fields; skip items or throw `MissingFieldException`.
- A module needs a READY/LIMITED entry in `sources/registry.json` backed by dated evidence; do not
  implement REVIEW_REQUIRED/BLOCKED/UNREACHABLE sources, and never delete their entries.
- Bump `extVersionCode` once per PR for user-visible changes; never rename packages; preserve `id`
  when renaming a source.
- Don't edit `settings.gradle.kts` to narrow the build — use `-Pextensions`.
- Never commit `signingkey.jks`, `local.properties` or credentials.
