# Distribution

## Catalog format (consumer contract)

Taken from Anikku's `ExtensionApi.kt`. The app is given a URL ending in `index.min.json`; it fetches
that file, reads repository metadata from `repo.json` next to it, and downloads
`apk/<apk>` and `icon/<pkg>.png` relative to the same base.

`index.min.json` — a JSON array, one object per extension:

| Field     | Type   | Rule (enforced by `tools/validate_catalog.py`)                                  |
|-----------|--------|----------------------------------------------------------------------------------|
| `name`    | string | `"Aniyomi: " + extName` (the app strips the prefix)                              |
| `pkg`     | string | `eu.kanade.tachiyomi.animeextension.<lang>.<name>`, unique                       |
| `apk`     | string | `aniyomi-<lang>.<name>-v<version>.apk`, must exist in `apk/`                     |
| `lang`    | string | module language (or the single source's regional variant, e.g. `pt-BR`)          |
| `code`    | int    | versionCode; equals the part of `version` after the last dot                     |
| `version` | string | `<libVersion>.<versionCode>`; lib level must be within 12..16                    |
| `nsfw`    | int    | `0` or `1`                                                                       |
| `torrent` | int    | optional, `0`/`1`                                                                |
| `sources` | array  | non-empty; each `{name, lang, id, baseUrl}`                                      |

Source `id` is a decimal string of a positive 64-bit integer, unique across the catalog; `baseUrl`
must be `https` without credentials. `repo.json` is
`{"meta": {"name", "shortName", "website", "signingKeyFingerprint"}}`, where the fingerprint is the
SHA-256 of the signing certificate; the app uses it to trust APKs from this repository.

The generator also writes `index.json` (pretty-printed copy) and `build.json` (source commit and
versions) for humans and audits; the app ignores them.

## Pipeline

```
release.yml (push to main / manual)
  prepare   build_matrix.py --all; detect signing secrets
  build     per module, max 4 in parallel: testDebugUnitTest + assembleRelease
            → artifact apk-release-<lang>-<name>
  catalog   catalog.yml (reusable)
              - APK count must equal module count (no partial catalogs)
              - build aniyomi-extensions-inspector v1.5.0.2 from its pinned commit
              - inspector instantiates every source → name/lang/id/baseUrl
              - generate_catalog.py: aapt badging + apksigner, cross-check against the checked-out
                tree (package, version, label, nsfw), one signer, deterministic JSON
              - validate_catalog.py --source-tree .
              → artifact catalog-<sha>
  publish   only if: main branch, vars.PUBLISH_CATALOG == 'true', release key configured
            force-pushes the catalog as a single commit to the `repo` branch
```

Why the inspector: a source id is computed by the source class (`name`, `lang`, `versionId`, or an
explicit `id` override). Executing the class is the only reliable way to get it.
`tools/repo_model.py:source_id` implements the default formula and is used in tests to confirm the
inspector output, not as a substitute for it.

Stale-artifact protection: `generate_catalog.py` refuses any APK whose version differs from what
the checked-out sources build, and `validate_catalog.py` rejects unreferenced files in `apk/`.

## Publishing setup

1. Create a release keystore once and keep it offline. Add repository secrets:
   `SIGNING_KEY` (base64 of the `.jks`), `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`.
2. Set the repository variable `PUBLISH_CATALOG` to `true`.
3. Allow GitHub Actions to write contents (Settings → Actions → Workflow permissions); the
   `publish` job requests `contents: write` only for itself.
4. After the first publish, users add:
   `https://raw.githubusercontent.com/<owner>/<repo>/repo/index.min.json`

Without secrets the workflow runs in **artifact-only mode**: APKs are debug-signed, the catalog is
still generated and validated (with `--allow-debug-signing`) and uploaded as an artifact, and
nothing is published. Pull requests never run the release workflow, so untrusted code never sees
signing secrets.

Never change the signing key of a published repository: installed extensions would stop updating.

## Versioning rules

- Package name and module directory are the extension's identity: **never rename them**.
- `extVersionCode` increases by 1 for every user-visible change, once per pull request.
- Theme-based extensions: `versionCode = baseVersionCode (theme) + overrideVersionCode`. Bump
  `baseVersionCode` when the theme changes; bump `overrideVersionCode` only for extension-specific
  changes.
- `libVersion` is the extensions-lib level the code is written against (16 here). Raising it is an
  update for the app even with the same code.
- `versionName` is generated; never set it by hand.

## Source identity and renames

A source's id defaults to MD5(`"<name lowercase>/<lang>/<versionId>"`) (first 64 bits, sign bit
cleared). Changing `name` or `lang` therefore changes the id and orphans users' libraries. When
renaming, keep the old id:

```kotlin
override val id: Long = 1829328537302010998 // value from index.json before the rename
```

Bump `versionId` only when old stored URLs can no longer work; it forces every user to migrate.

## Removing an extension

Delete the module directory. The next release regenerates the full catalog without it; installed
copies keep working but receive no updates.
