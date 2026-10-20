# Implementation plan and status

Status as of 2026-10-20. "Verified" means the command or check was executed and passed in the
development environment; anything else is stated explicitly.

## Decisions

| Decision | Rationale |
|----------|-----------|
| Target Anikku with extensions-lib **16** (`komikku-app/aniyomi-extensions-lib@f5961b5dfb`) | Current API of the reference ecosystem; read from the published sources jar |
| Mirror the reference's module layout and toolchain versions | Proven combination (Gradle 9.7.1, AGP 9.3.2, Kotlin 2.3.21, serialization 1.7.3) |
| Write our own convention plugins, core library and tooling | Independence and clear licensing; reference used for design only (see NOTICE) |
| First source: **Internet Archive** (`src/all/internetarchive`) | Official public APIs, explicit rights metadata, no access controls to respect beyond excluding restricted items; allows real (not fabricated) integration |
| No Indonesian-specific source yet | No candidate with verifiable legitimate access was identified; IA `language` metadata is too sparse for an Indonesian filter (0 tagged items). Indonesian docs are provided instead |
| No theme or `lib/` module yet | Only one source; a theme requires demonstrated shared contracts |
| Catalog source metadata from the pinned inspector, built from source in CI | Ids must come from the source class; release-asset checksums could not be verified |

## Phases

| Phase | Deliverable | Status |
|-------|-------------|--------|
| 1 Audit | Reference repo, lib 16 API, Anikku loader/catalog contracts, IA API contracts | Done |
| 2 Foundation | Wrapper (checksum-pinned), catalogs, `gradle/build-logic`, `core`, selective loading (`-Pextensions`) | Done, verified |
| 3 Source | Internet Archive: popular/latest/search/filters, details, grouped episodes, videos + subtitles, restricted-content exclusion, rate limit, retry | Done, verified (unit + opt-in live test) |
| 4 Catalog tooling | `generate_catalog.py`, `validate_catalog.py`, stale-artifact and signer checks | Done, verified end-to-end with the real inspector |
| 5 CI/CD | `validate`, `build-extensions`, `catalog`, `release` workflows | Written and linted (actionlint + shellcheck); **not yet run on GitHub** |
| 6 Docs | README, docs/*, CONTRIBUTING, AGENTS/CLAUDE, Indonesian guide, NOTICE | Done |

## Definition of done

| Criterion | State |
|-----------|-------|
| Repository architecture documented | ✅ docs/ARCHITECTURE.md |
| Target app and API compatibility verified | ✅ against lib sources, Anikku loader/API sources, APK badging, inspector — ⚠️ not yet installed on a device |
| Minimal valid extension module exists | ✅ `all.internetarchive` 16.1 (debug + release APK built) |
| No placeholder production endpoints | ✅ only documented archive.org endpoints; scaffolds are blocked by the validator |
| Metadata and version conventions documented | ✅ docs/DISTRIBUTION.md |
| Shared utilities tested | ✅ 22 tests in `core` |
| Source template generates valid files | ✅ generated module compiles, passes ktlint and its test (local run; also a CI step) |
| Catalog validation detects missing/invalid artifacts | ✅ missing APK/icon, stale APK, version drift, schema errors, duplicate ids, debug signer (35 tool tests) |
| Catalog follows the real consumer schema | ✅ matches Anikku `ExtensionJsonObject` / `repo.json` |
| Targeted module build succeeds | ✅ `-Pextensions=all:internetarchive … assembleDebug assembleRelease` |
| Formatting and unit tests pass | ✅ `spotlessCheck`; 46 Kotlin tests pass, 1 opt-in live test skipped by default (passes with `ASE_LIVE_TESTS=1`) |
| GitHub Actions build and validate intended modules | ⚠️ matrix logic unit-tested and workflows linted; first real run pending |
| Release publishing gated | ✅ main only, `vars.PUBLISH_CATALOG`, signing secrets required, never on PRs |
| Documentation matches implementation | ✅ all documented commands were executed |
| Licensing and attribution addressed | ✅ Apache-2.0 LICENSE, NOTICE with provenance |
| Known limitations recorded | ✅ below and in the source README |

## Known limitations

- **No on-device test yet.** The APK satisfies every loader requirement we can check statically
  and loads in the inspector, but has not been installed in Anikku.
- **No release key / published catalog.** Until signing secrets and `PUBLISH_CATALOG` are set, the
  release workflow runs in artifact-only mode with debug-signed APKs.
- **Workflows unexecuted on GitHub.** In particular the inspector build (`tools/ci/build_inspector.sh`)
  was verified locally only, with local mirror init scripts for this environment's proxy.
- **Internet Archive specifics**: one episode per master file (e.g. two masters of *Elephants
  Dream*); 10,000-result paging window per query; `latest` reflects upload order and includes
  community uploads; rights are as declared by uploaders.
- `naturalOrder` compares case-insensitively and ASCII digits only.

## Next milestone

**M2 — First published, device-verified release**

1. Push to `main` and confirm `validate`, `build-extensions` and `release` (artifact-only) succeed;
   fix anything environment-specific (e.g. inspector build on the runner).
2. Install the debug APK in a current Anikku build; exercise browse, search with each filter, a
   multi-episode collection, subtitles, and playback; record results in the source README.
3. Create the release keystore, add the four secrets and `PUBLISH_CATALOG=true`; publish and add
   the repo URL in the app; confirm update detection with a version bump.
4. Then evaluate a second source with verifiable legitimate access (preferably one serving the
   Indonesian audience) and, only if justified by shared contracts, a first `lib-multisrc` theme.
