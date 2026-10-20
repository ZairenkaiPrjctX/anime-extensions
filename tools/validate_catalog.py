#!/usr/bin/env python3
"""Validates a generated extension repository (catalog) directory.

The layout and schema are the ones the host app consumes (Anikku `ExtensionApi`):

    <repo>/index.min.json   list of extensions (see ENTRY_FIELDS / SOURCE_FIELDS)
    <repo>/repo.json        {"meta": {name, shortName, website, signingKeyFingerprint}}
    <repo>/apk/<apk>        every APK referenced by an entry
    <repo>/icon/<pkg>.png   icon of every entry

Checks types and formats, duplicate packages and source ids, lib-version compatibility, that
every referenced APK/icon exists, that no unreferenced (stale) APK is left behind and, with
--source-tree, that each entry's version matches the source tree it was built from.

    python3 tools/validate_catalog.py repo/ [--source-tree .]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
from repo_model import (  # noqa: E402
    APP_LABEL_PREFIX,
    MIN_LIB_VERSION,
    PACKAGE_PREFIX,
    SOURCE_LANG_RE,
    discover_extensions,
    repo_lib_version,
)

# Anikku ExtensionLoader.LIB_VERSION_MAX: the newest lib level the app understands.
APP_MAX_LIB_VERSION = 16

ENTRY_FIELDS = {"name": str, "pkg": str, "apk": str, "lang": str, "code": int, "version": str, "nsfw": int, "sources": list}
SOURCE_FIELDS = {"name": str, "lang": str, "id": str, "baseUrl": str}
META_FIELDS = ("name", "shortName", "website", "signingKeyFingerprint")

PKG_RE = re.compile(rf"^{re.escape(PACKAGE_PREFIX)}\.(?P<lang>[a-z]{{2,3}}|all|other)\.(?P<name>[a-z0-9]+)$")
VERSION_RE = re.compile(r"^(?P<lib>\d+)\.(?P<code>\d+)$")
FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
INT64_MAX = 2**63 - 1


def is_safe_https_url(url: str) -> bool:
    try:
        parts = urlsplit(url)
    except ValueError:
        return False
    return parts.scheme == "https" and bool(parts.hostname) and parts.username is None and parts.password is None


def validate_entries(entries: object) -> list[str]:
    """Schema-level checks of index.min.json content. Returns error messages."""
    if not isinstance(entries, list):
        return ["index.min.json must contain a JSON array"]
    errors: list[str] = []
    seen_pkgs: set[str] = set()
    seen_ids: dict[str, str] = {}
    for i, entry in enumerate(entries):
        where = f"entry[{i}]"
        if not isinstance(entry, dict):
            errors.append(f"{where}: must be an object")
            continue
        where = f"entry[{i}] ({entry.get('pkg', '?')})"
        for key, kind in ENTRY_FIELDS.items():
            value = entry.get(key)
            if not isinstance(value, kind) or (kind is int and isinstance(value, bool)):
                errors.append(f"{where}: '{key}' must be {kind.__name__}")
        if any(not isinstance(entry.get(k), t) for k, t in ENTRY_FIELDS.items()):
            continue

        pkg_match = PKG_RE.match(entry["pkg"])
        if not pkg_match:
            errors.append(f"{where}: invalid package name")
        elif entry["pkg"] in seen_pkgs:
            errors.append(f"{where}: duplicate package")
        seen_pkgs.add(entry["pkg"])

        if not entry["name"].startswith(APP_LABEL_PREFIX) or not entry["name"][len(APP_LABEL_PREFIX):].strip():
            errors.append(f"{where}: 'name' must be '{APP_LABEL_PREFIX}<extension name>'")

        version = VERSION_RE.match(entry["version"])
        if not version:
            errors.append(f"{where}: 'version' must be '<libVersion>.<versionCode>'")
        else:
            lib = int(version["lib"])
            if not MIN_LIB_VERSION <= lib <= APP_MAX_LIB_VERSION:
                errors.append(f"{where}: lib version {lib} is outside the app's supported range {MIN_LIB_VERSION}..{APP_MAX_LIB_VERSION}")
            if int(version["code"]) != entry["code"]:
                errors.append(f"{where}: 'code' {entry['code']} does not match version '{entry['version']}'")
        if entry["code"] < 1:
            errors.append(f"{where}: 'code' must be positive")
        if entry["nsfw"] not in (0, 1):
            errors.append(f"{where}: 'nsfw' must be 0 or 1")
        if "torrent" in entry and entry["torrent"] not in (0, 1):
            errors.append(f"{where}: 'torrent' must be 0 or 1")
        if not SOURCE_LANG_RE.match(entry["lang"]):
            errors.append(f"{where}: invalid 'lang'")

        if pkg_match and version:
            expected_apk = f"aniyomi-{pkg_match['lang']}.{pkg_match['name']}-v{entry['version']}.apk"
            if entry["apk"] != expected_apk:
                errors.append(f"{where}: 'apk' must be '{expected_apk}'")

        if not entry["sources"]:
            errors.append(f"{where}: 'sources' must not be empty")
        for j, source in enumerate(entry["sources"]):
            swhere = f"{where} source[{j}]"
            if not isinstance(source, dict):
                errors.append(f"{swhere}: must be an object")
                continue
            for key, kind in SOURCE_FIELDS.items():
                if not isinstance(source.get(key), kind):
                    errors.append(f"{swhere}: '{key}' must be {kind.__name__}")
            if any(not isinstance(source.get(k), t) for k, t in SOURCE_FIELDS.items()):
                continue
            if not source["name"].strip():
                errors.append(f"{swhere}: empty 'name'")
            if not SOURCE_LANG_RE.match(source["lang"]):
                errors.append(f"{swhere}: invalid 'lang'")
            if not source["id"].isdigit() or not 0 < int(source["id"]) <= INT64_MAX:
                errors.append(f"{swhere}: 'id' must be a positive 64-bit integer string")
            elif source["id"] in seen_ids:
                errors.append(f"{swhere}: duplicate source id (also used by {seen_ids[source['id']]})")
            else:
                seen_ids[source["id"]] = entry["pkg"]
            # "" is the published convention for user-configured servers (e.g. Jellyfin).
            if source["baseUrl"] != "" and not is_safe_https_url(source["baseUrl"]):
                errors.append(f"{swhere}: 'baseUrl' must be empty (user-configured) or an https URL without credentials")
    return errors


def validate_files(repo: Path, entries: list[dict]) -> list[str]:
    errors = []
    referenced = set()
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("apk"), str) or not isinstance(entry.get("pkg"), str):
            continue
        apk = repo / "apk" / entry["apk"]
        referenced.add(entry["apk"])
        if "/" in entry["apk"] or not apk.is_file():
            errors.append(f"{entry['pkg']}: referenced APK apk/{entry['apk']} is missing")
        icon = repo / "icon" / f"{entry['pkg']}.png"
        if not icon.is_file():
            errors.append(f"{entry['pkg']}: icon icon/{entry['pkg']}.png is missing")
        elif icon.read_bytes()[:8] != PNG_MAGIC:
            errors.append(f"{entry['pkg']}: icon is not a PNG file")
    apk_dir = repo / "apk"
    if apk_dir.is_dir():
        for stale in sorted(p.name for p in apk_dir.iterdir() if p.name not in referenced):
            errors.append(f"apk/{stale}: not referenced by index.min.json (stale artifact)")
    return errors


def validate_repo_meta(repo: Path) -> list[str]:
    path = repo / "repo.json"
    if not path.is_file():
        return ["repo.json is missing"]
    try:
        meta = json.loads(path.read_text("utf-8")).get("meta")
    except (json.JSONDecodeError, AttributeError):
        return ["repo.json is not a JSON object"]
    if not isinstance(meta, dict):
        return ["repo.json must contain a 'meta' object"]
    errors = [f"repo.json: 'meta.{k}' must be a non-empty string" for k in META_FIELDS if not isinstance(meta.get(k), str) or not meta[k].strip()]
    if isinstance(meta.get("signingKeyFingerprint"), str) and not FINGERPRINT_RE.match(meta["signingKeyFingerprint"]):
        errors.append("repo.json: 'signingKeyFingerprint' must be a lowercase SHA-256 hex digest")
    if isinstance(meta.get("website"), str) and not is_safe_https_url(meta["website"]):
        errors.append("repo.json: 'website' must be an https URL")
    return errors


def validate_against_tree(entries: list[dict], tree: Path) -> list[str]:
    """Every entry must describe the version the given source tree builds (no stale artifacts)."""
    expected = {ext.pkg: ext for ext in discover_extensions(tree)}
    max_lib = repo_lib_version(tree)
    errors = []
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("pkg"), str):
            continue
        ext = expected.get(entry["pkg"])
        if ext is None:
            errors.append(f"{entry['pkg']}: no matching module in the source tree")
        elif ext.errors:
            errors.append(f"{entry['pkg']}: source module has metadata errors: {'; '.join(ext.errors)}")
        elif entry.get("version") != ext.version_name:
            errors.append(f"{entry['pkg']}: catalog version {entry.get('version')} != source tree version {ext.version_name}")
        elif ext.lib_version is not None and ext.lib_version > max_lib:
            errors.append(f"{entry['pkg']}: lib version {ext.lib_version} exceeds repository level {max_lib}")
    return errors


def validate_repo(repo: Path, tree: Path | None = None) -> list[str]:
    index = repo / "index.min.json"
    if not index.is_file():
        return ["index.min.json is missing"]
    try:
        entries = json.loads(index.read_text("utf-8"))
    except json.JSONDecodeError as e:
        return [f"index.min.json is not valid JSON: {e}"]
    errors = validate_entries(entries)
    if isinstance(entries, list):
        errors += validate_files(repo, entries)
        if tree is not None:
            errors += validate_against_tree(entries, tree)
        full = repo / "index.json"
        if full.is_file() and json.loads(full.read_text("utf-8")) != entries:
            errors.append("index.json does not match index.min.json")
        if isinstance(entries, list) and not entries:
            errors.append("catalog is empty")
    errors += validate_repo_meta(repo)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate a generated extension repository.")
    parser.add_argument("repo", type=Path)
    parser.add_argument("--source-tree", type=Path, help="also check versions against this checkout")
    args = parser.parse_args()
    errors = validate_repo(args.repo, args.source_tree)
    for error in errors:
        print(f"error: {error}")
    print(f"Catalog {args.repo}: {'FAILED' if errors else 'OK'} ({len(errors)} error(s))")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
