# Development

## Prerequisites

- JDK 17+ (CI uses Temurin 21). The Gradle wrapper downloads Gradle 9.7.1 and verifies its SHA-256.
- Android SDK with platform 34; set `ANDROID_HOME` (or `sdk.dir` in an uncommitted
  `local.properties`). AGP installs the build-tools it needs if the SDK licenses are accepted
  (`sdkmanager --licenses`).
- Python 3.12+ for `tools/` (standard library only).

## Everyday commands

Always scope Gradle to the module you are changing. `-Pextensions=<lang>:<name>[,…]` limits which
extension modules are configured, without editing `settings.gradle.kts`.

```bash
# Compile one extension (fast feedback)
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:compileDebugKotlin

# Unit tests
./gradlew :core:testDebugUnitTest
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:testDebugUnitTest

# Opt-in live API contract test (network required; skipped otherwise)
ASE_LIVE_TESTS=1 ./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:testDebugUnitTest --tests '*LiveApiTest'

# Installable APKs (release falls back to the debug key unless signingkey.jks + env vars exist)
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:assembleDebug
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:assembleRelease

# Formatting (ktlint via Spotless)
./gradlew spotlessCheck
./gradlew spotlessApply          # rewrites files

# Repository tooling
python3 -m unittest discover -s tools/tests -t tools/tests
python3 tools/validate_extensions.py
python3 tools/build_matrix.py --all            # or --modules all:internetarchive, or --base origin/main
```

APKs land in `src/<lang>/<name>/build/outputs/apk/<buildType>/aniyomi-<lang>.<name>-v<version>-<buildType>.apk`.

## Adding a source

```bash
python3 tools/new_source.py en mysite --display-name "My Site" --base-url https://mysite.example
./gradlew -Pextensions=en:mysite :src:en:mysite:compileDebugKotlin :src:en:mysite:testDebugUnitTest
```

The scaffold compiles and has a passing test, but `validate_extensions.py` rejects it until every
`notImplemented()` placeholder is replaced. Then follow [CONTRIBUTING.md](../CONTRIBUTING.md).

## Testing strategy

| Layer                 | What                                                                 | Where                                   |
|-----------------------|----------------------------------------------------------------------|-----------------------------------------|
| Shared library        | Every public helper: retry/limiter timing, error redaction, JSON shapes, URL safety, text | `core/src/test`         |
| Source pure layer     | Query building/escaping, filters, DTO decoding, mapping against recorded fixtures, edge cases (missing fields, restricted items, paging limits) | `src/<lang>/<name>/test` |
| Source live contract  | Same parser against the live API, opt-in via `ASE_LIVE_TESTS=1`      | `LiveApiTest`                           |
| Packaging             | APK manifest/metadata; inspector instantiation; catalog schema       | `generate_catalog.py` + `validate_catalog.py` (CI) |
| Tooling               | Metadata parsing, matrix resolution, catalog rules, scaffolder safety | `tools/tests`                          |

Fixtures are real responses, sanitized (no e-mail addresses, server internals or credentials) and
trimmed; document each one in the module's `test/resources/fixtures/README.md`.

## Troubleshooting (verified)

| Symptom                                                                 | Fix                                                                     |
|-------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `Plugin ... was not found` / HTTP 429 from Maven Central behind a proxy | Transient rate limiting; retry, or use a local `--init-script` adding a Maven Central mirror (do not commit it) |
| `Verification of Gradle distribution failed`                            | The wrapper checksum is pinned; re-download, don't remove the check      |
| `R8: Attribute InnerClasses requires EnclosingMethod`                   | Keep both attributes together in `common/proguard-rules.pro`             |
| ktlint `standard:filename`                                              | A file with one top-level declaration must be named after it             |
| Spotless reports files under `build/`                                   | Targets exclude `**/build/**`; run tasks on the module, not a stale root config |
| Unit test cannot call `SAnime.create()`                                 | Stubs throw `Stub!`; test the pure layer instead                         |
| Inspector reports no sources                                            | Something `android.*` is created eagerly in the source; make it `by lazy` |
