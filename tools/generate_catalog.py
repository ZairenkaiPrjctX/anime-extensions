#!/usr/bin/env python3
"""Builds the extension repository (catalog) the host app installs from.

Inputs
  --apk-dir DIR         built APKs (searched recursively; `-release`/`-debug` suffixes are dropped)
  --inspector-json F    output of komikku-app/aniyomi-extensions-inspector: {pkg: [{name, lang, id, baseUrl, versionId}]}
  --repo-meta F         {"name", "shortName", "website"} for repo.json
  --output DIR          catalog directory to (re)create
  --commit SHA          source commit the APKs were built from (recorded in build.json)

Every APK is read with `aapt dump badging` and checked against the source tree: package, version
and file name must match what the checked-out sources build, so an artifact from another revision
can never be published under newer metadata. All APKs must share one signing certificate, whose
SHA-256 becomes repo.json's signingKeyFingerprint; the Android debug certificate is refused
unless --allow-debug-signing is given (local testing only). Output is deterministic and is
validated with validate_catalog.py before the command succeeds.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from repo_model import APP_LABEL_PREFIX, REPO_ROOT, discover_extensions  # noqa: E402
from validate_catalog import validate_repo  # noqa: E402

PACKAGE_RE = re.compile(r"^package: name='(?P<pkg>[^']+)' versionCode='(?P<code>\d+)' versionName='(?P<version>[^']+)'", re.M)
LABEL_RE = re.compile(r"^application-label:'(?P<label>[^']*)'", re.M)
ICON_RE = re.compile(r"^application-icon-320:'(?P<icon>[^']+)'", re.M)
NSFW_RE = re.compile(r"meta-data: name='tachiyomi\.animeextension\.nsfw' value='(?P<nsfw>\d)'")
TORRENT_RE = re.compile(r"meta-data: name='tachiyomi\.animeextension\.torrent' value='(?P<torrent>\d)'")
FEATURE_RE = re.compile(r"uses-feature: name='tachiyomi\.animeextension'")
CERT_SHA256_RE = re.compile(r"^Signer #1 certificate SHA-256 digest: (?P<sha>[0-9a-f]{64})$", re.M)
CERT_DN_RE = re.compile(r"^Signer #1 certificate DN: (?P<dn>.+)$", re.M)


class CatalogError(RuntimeError):
    pass


@dataclass
class ApkInfo:
    path: Path
    pkg: str
    code: int
    version: str
    label: str
    icon: str
    nsfw: int
    torrent: int
    signer_sha256: str
    signer_dn: str


def find_build_tool(name: str) -> str:
    """Newest Android build-tools binary from $ANDROID_HOME (or the PATH)."""
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        tools = sorted((Path(home) / "build-tools").glob(f"*/{name}"), key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"[.-]", p.parent.name)])
        if tools:
            return str(tools[-1])
    if found := shutil.which(name):
        return found
    raise CatalogError(f"'{name}' not found: set ANDROID_HOME to an SDK with build-tools installed")


def run(cmd: list[str]) -> str:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise CatalogError(f"{Path(cmd[0]).name} failed for {cmd[-1]}: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def read_apk(path: Path, aapt: str, apksigner: str) -> ApkInfo:
    badging = run([aapt, "dump", "--include-meta-data", "badging", str(path)])
    package = PACKAGE_RE.search(badging)
    label = LABEL_RE.search(badging)
    icon = ICON_RE.search(badging)
    nsfw = NSFW_RE.search(badging)
    if not (package and label and icon and nsfw) or not FEATURE_RE.search(badging):
        raise CatalogError(f"{path.name}: not an anime extension APK (missing package, label, icon, nsfw meta-data or tachiyomi.animeextension feature)")
    certs = run([apksigner, "verify", "--print-certs", str(path)])
    sha = CERT_SHA256_RE.search(certs)
    if not sha:
        raise CatalogError(f"{path.name}: APK is not signed")
    torrent = TORRENT_RE.search(badging)
    return ApkInfo(
        path=path,
        pkg=package["pkg"],
        code=int(package["code"]),
        version=package["version"],
        label=label["label"],
        icon=icon["icon"],
        nsfw=int(nsfw["nsfw"]),
        torrent=int(torrent["torrent"]) if torrent else 0,
        signer_sha256=sha["sha"],
        signer_dn=(CERT_DN_RE.search(certs) or {"dn": ""})["dn"],
    )


def collect_apks(apk_dir: Path) -> list[Path]:
    apks = sorted(apk_dir.rglob("*.apk"))
    if not apks:
        raise CatalogError(f"no APKs found in {apk_dir}")
    return apks


def build_entries(infos: list[ApkInfo], inspector: dict, tree: Path) -> list[dict]:
    modules = {ext.pkg: ext for ext in discover_extensions(tree)}
    errors: list[str] = []
    entries = []
    seen: set[str] = set()
    for info in infos:
        ext = modules.get(info.pkg)
        if info.pkg in seen:
            errors.append(f"{info.path.name}: duplicate APK for {info.pkg}")
            continue
        seen.add(info.pkg)
        if ext is None:
            errors.append(f"{info.path.name}: package {info.pkg} has no module in the source tree")
            continue
        if ext.errors:
            errors.append(f"{info.pkg}: source module has metadata errors: {'; '.join(ext.errors)}")
            continue
        if (info.code, info.version) != (ext.version_code, ext.version_name):
            errors.append(f"{info.path.name}: built as {info.version} (code {info.code}) but the source tree is at {ext.version_name}; refusing a stale artifact")
            continue
        if info.label != APP_LABEL_PREFIX + str(ext.metadata["extName"]):
            errors.append(f"{info.path.name}: label '{info.label}' does not match extName '{ext.metadata['extName']}'")
        if info.nsfw != (1 if ext.metadata["isNsfw"] else 0):
            errors.append(f"{info.path.name}: nsfw flag does not match build.gradle")
        sources = inspector.get(info.pkg)
        if not sources:
            errors.append(f"{info.pkg}: inspector reported no sources (the source class could not be instantiated)")
            continue
        # A `pt` module whose single source is `pt-BR` is listed as `pt-BR` (same rule as upstream).
        lang = ext.lang
        source_lang = sources[0].get("lang") if len(sources) == 1 else None
        if source_lang and source_lang != lang and source_lang not in ("all", "other") and lang not in ("all", "other"):
            lang = source_lang
        entry = {
            "name": info.label,
            "pkg": info.pkg,
            "apk": ext.apk_name,
            "lang": lang,
            "code": info.code,
            "version": info.version,
            "nsfw": info.nsfw,
            "sources": sorted(
                ({"name": s.get("name"), "lang": s.get("lang"), "id": str(s.get("id")), "baseUrl": s.get("baseUrl")} for s in sources),
                key=lambda s: (str(s["lang"]), str(s["name"]), str(s["id"])),
            ),
        }
        if info.torrent:
            entry["torrent"] = 1
        entries.append(entry)
    if errors:
        raise CatalogError("\n".join(errors))
    return sorted(entries, key=lambda e: e["pkg"])


def check_signers(infos: list[ApkInfo], allow_debug: bool) -> str:
    signers = {i.signer_sha256 for i in infos}
    if len(signers) != 1:
        detail = ", ".join(f"{i.path.name}={i.signer_sha256[:12]}" for i in infos)
        raise CatalogError(f"APKs are signed with different certificates: {detail}")
    if any("CN=Android Debug" in i.signer_dn for i in infos) and not allow_debug:
        raise CatalogError("APKs are signed with the Android debug certificate; configure release signing or pass --allow-debug-signing for local tests")
    return signers.pop()


def write_catalog(output: Path, infos: list[ApkInfo], entries: list[dict], meta: dict, fingerprint: str, commit: str | None) -> None:
    if output.exists():
        shutil.rmtree(output)
    (output / "apk").mkdir(parents=True)
    (output / "icon").mkdir()
    by_pkg = {i.pkg: i for i in infos}
    for entry in entries:
        info = by_pkg[entry["pkg"]]
        shutil.copyfile(info.path, output / "apk" / entry["apk"])
        with zipfile.ZipFile(info.path) as apk:
            (output / "icon" / f"{entry['pkg']}.png").write_bytes(apk.read(info.icon))
    (output / "index.min.json").write_text(json.dumps(entries, ensure_ascii=False, separators=(",", ":")) + "\n", "utf-8")
    (output / "index.json").write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", "utf-8")
    repo_meta = {"meta": {"name": meta["name"], "shortName": meta["shortName"], "website": meta["website"], "signingKeyFingerprint": fingerprint}}
    (output / "repo.json").write_text(json.dumps(repo_meta, ensure_ascii=False, indent=2) + "\n", "utf-8")
    build = {"commit": commit, "extensions": [{"pkg": e["pkg"], "version": e["version"]} for e in entries]}
    (output / "build.json").write_text(json.dumps(build, indent=2) + "\n", "utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate the extension repository from built APKs.")
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--inspector-json", type=Path, required=True)
    parser.add_argument("--repo-meta", type=Path, default=REPO_ROOT / "catalog" / "repo-meta.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-tree", type=Path, default=REPO_ROOT)
    parser.add_argument("--commit", help="source commit SHA recorded in build.json")
    parser.add_argument("--allow-debug-signing", action="store_true")
    args = parser.parse_args()

    try:
        if args.commit is not None and not re.fullmatch(r"[0-9a-f]{7,40}", args.commit):
            raise CatalogError("--commit must be a git commit SHA")
        meta = json.loads(args.repo_meta.read_text("utf-8"))
        inspector = json.loads(args.inspector_json.read_text("utf-8"))
        if not isinstance(inspector, dict):
            raise CatalogError("inspector output must be a JSON object keyed by package name")
        aapt, apksigner = find_build_tool("aapt"), find_build_tool("apksigner")
        infos = [read_apk(p, aapt, apksigner) for p in collect_apks(args.apk_dir)]
        fingerprint = check_signers(infos, args.allow_debug_signing)
        entries = build_entries(infos, inspector, args.source_tree)
        write_catalog(args.output, infos, entries, meta, fingerprint, args.commit)
    except (CatalogError, OSError, json.JSONDecodeError, KeyError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    errors = validate_repo(args.output, args.source_tree)
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"Catalog written to {args.output}: {len(entries)} extension(s), signer {fingerprint}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
