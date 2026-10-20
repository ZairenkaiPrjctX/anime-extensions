# Anime Source Extensions

Independent, modular source extensions for [Anikku](https://github.com/komikku-app/anikku) and
other apps that load **Aniyomi extensions-lib 16** APKs. Each source is its own Gradle module and
APK; shared logic lives in a small, tested core library; CI builds only what a change affects and
produces a validated extension catalog.

> Not affiliated with Anikku, Aniyomi, the Internet Archive or any content provider.
> Sources only use public, documented interfaces; no DRM, paywall, login or CAPTCHA circumvention.

**Bahasa Indonesia:** lihat [docs/id/MEMULAI.md](docs/id/MEMULAI.md) untuk panduan memulai.

## Extensions

| Module                                                    | Lang | Version | Data source                                   |
|-----------------------------------------------------------|------|---------|-----------------------------------------------|
| [`all.internetarchive`](src/all/internetarchive/README.md) | all  | 16.1    | Internet Archive public Search + Metadata API |

## Install

Once a signed catalog is published (see [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md)), add this
repository in the app's extension repository settings:

```
https://raw.githubusercontent.com/ZairenkaiPrjctX/anime-extensions/repo/index.min.json
```

Until then, debug APKs are available as artifacts of the **Build extensions** workflow.

## Build

```bash
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:assembleDebug
python3 -m unittest discover -s tools/tests -t tools/tests
python3 tools/validate_extensions.py
```

Requirements, all commands and troubleshooting: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — modules, layering, core library, error semantics
- [Compatibility](docs/COMPATIBILITY.md) — target app and API, how it was verified
- [Development](docs/DEVELOPMENT.md) — commands, tests, troubleshooting
- [Distribution](docs/DISTRIBUTION.md) — catalog schema, release pipeline, versioning, renames
- [Contributing](CONTRIBUTING.md) — adding and maintaining a source
- [Implementation plan](IMPLEMENTATION_PLAN.md) — status, limitations, next milestone

## License

[Apache License 2.0](LICENSE). Provenance of reused material: [NOTICE](NOTICE).
