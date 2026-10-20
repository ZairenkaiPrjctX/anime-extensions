#!/usr/bin/env python3
"""Validates the source registry (sources/registry.json) and renders docs/SOURCE_AUDIT.md.

The registry records every audited or discovered source, implemented or not. Discovery is not
authorization: only READY or LIMITED entries may name an extension module, and every extension
module must be backed by such an entry.

    python3 tools/source_registry.py validate [--catalog repo/index.min.json] [--max-age-days 180]
    python3 tools/source_registry.py render          # rewrite docs/SOURCE_AUDIT.md
    python3 tools/source_registry.py render --check  # fail if the rendered file is out of date
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
from repo_model import REPO_ROOT, discover_extensions, repo_lib_version  # noqa: E402

REGISTRY = Path("sources/registry.json")
AUDIT_DOC = Path("docs/SOURCE_AUDIT.md")

STATUSES = ("READY", "LIMITED", "REVIEW_REQUIRED", "BLOCKED", "UNREACHABLE")
ELIGIBLE = {"READY", "LIMITED"}
CATEGORIES = {
    "requested-site": "Requested website",
    "A-official-platform": "A. Official platform / publisher channel",
    "B-metadata-api": "B. Documented anime API / catalog",
    "C-user-media-server": "C. User-owned media server",
    "D-open-collection": "D. Public-domain / openly licensed collection",
    "E-community-site": "E. Community site",
}
CAPABILITIES = ("catalog", "search", "details", "episodes", "playback")
CAPABILITY_VALUES = {"implemented", "partial", "not-implemented", "not-available"}
REQUIRED = ("id", "name", "url", "origin", "category", "languages", "content", "nsfw", "status", "last_verified",
            "integration", "permission", "evidence", "technical", "restrictions", "capabilities", "module", "next_action")
ID_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
LANG_RE = re.compile(r"^(?:[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*|all)$")
MODULE_RE = re.compile(r"^(?P<lang>[a-z]{2,3}|all|other)\.(?P<name>[a-z0-9]+)$")


def _is_url(value: object, schemes=("https",)) -> bool:
    if not isinstance(value, str):
        return False
    parts = urlsplit(value)
    return parts.scheme in schemes and bool(parts.hostname) and parts.username is None and parts.password is None


def validate(registry: dict, root: Path, today: dt.date, max_age_days: int | None, catalog: list | None = None) -> tuple[list[str], list[str]]:
    """Returns (errors, warnings)."""
    errors: list[str] = []
    warnings: list[str] = []
    if registry.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    sources = registry.get("sources")
    if not isinstance(sources, list) or not sources:
        return errors + ["'sources' must be a non-empty list"], warnings

    extensions = {f"{e.lang}.{e.name}": e for e in discover_extensions(root)}
    lib_level = repo_lib_version(root)
    seen_ids: set[str] = set()
    seen_urls: dict[str, str] = {}
    claimed_modules: dict[str, str] = {}

    for i, src in enumerate(sources):
        where = f"sources[{i}]"
        if not isinstance(src, dict):
            errors.append(f"{where}: must be an object")
            continue
        where = f"sources[{i}] ({src.get('id', '?')})"
        missing = [k for k in REQUIRED if k not in src]
        if missing:
            errors.append(f"{where}: missing fields: {', '.join(missing)}")
            continue

        sid = src["id"]
        if not isinstance(sid, str) or not ID_RE.match(sid):
            errors.append(f"{where}: 'id' must be lowercase kebab-case")
        elif sid in seen_ids:
            errors.append(f"{where}: duplicate id")
        seen_ids.add(str(sid))

        for key in ("name", "content", "permission", "technical", "next_action"):
            if not isinstance(src[key], str) or not src[key].strip():
                errors.append(f"{where}: '{key}' must be a non-empty string")
        if not _is_url(src["url"]):
            errors.append(f"{where}: 'url' must be an https URL without credentials")
        else:
            norm = src["url"].rstrip("/").lower()
            if norm in seen_urls:
                errors.append(f"{where}: url duplicates {seen_urls[norm]}")
            seen_urls[norm] = str(sid)
        if src["origin"] not in ("requested", "discovered"):
            errors.append(f"{where}: 'origin' must be 'requested' or 'discovered'")
        if src["category"] not in CATEGORIES:
            errors.append(f"{where}: unknown category '{src['category']}'")
        if (src["origin"] == "requested") != (src["category"] == "requested-site"):
            errors.append(f"{where}: requested sources use category 'requested-site' and vice versa")
        langs = src["languages"]
        if not isinstance(langs, list) or not langs or not all(isinstance(lang, str) and LANG_RE.match(lang) for lang in langs):
            errors.append(f"{where}: 'languages' must be a non-empty list of locale codes or 'all'")
        if not isinstance(src["nsfw"], bool):
            errors.append(f"{where}: 'nsfw' must be a boolean")

        status = src["status"]
        if status not in STATUSES:
            errors.append(f"{where}: status must be one of {', '.join(STATUSES)}")

        try:
            verified = dt.date.fromisoformat(src["last_verified"])
            if verified > today:
                errors.append(f"{where}: last_verified is in the future")
            elif max_age_days is not None and (today - verified).days > max_age_days:
                warnings.append(f"{where}: last verified {verified} is older than {max_age_days} days; re-audit")
        except (TypeError, ValueError):
            errors.append(f"{where}: last_verified must be an ISO date (YYYY-MM-DD)")

        integration = src["integration"]
        if not isinstance(integration, dict) or not isinstance(integration.get("method"), str) or "api_docs" not in integration:
            errors.append(f"{where}: 'integration' needs 'method' and 'api_docs'")
        elif integration["api_docs"] is not None and not _is_url(integration["api_docs"]):
            errors.append(f"{where}: integration.api_docs must be null or an https URL")

        evidence = src["evidence"]
        if not isinstance(evidence, list) or not evidence or not all(isinstance(e, str) and e.strip() for e in evidence):
            errors.append(f"{where}: 'evidence' must list at least one observation")
        if not isinstance(src["restrictions"], list) or not all(isinstance(r, str) for r in src["restrictions"]):
            errors.append(f"{where}: 'restrictions' must be a list of strings")

        caps = src["capabilities"]
        if not isinstance(caps, dict) or set(caps) != set(CAPABILITIES) or not set(caps.values()) <= CAPABILITY_VALUES:
            errors.append(f"{where}: 'capabilities' needs {', '.join(CAPABILITIES)} with values {sorted(CAPABILITY_VALUES)}")
            caps = {}

        module = src["module"]
        if module is None:
            if any(v in ("implemented", "partial") for v in caps.values()):
                errors.append(f"{where}: capabilities claim an implementation but 'module' is null")
            continue
        if status not in ELIGIBLE:
            errors.append(f"{where}: only READY/LIMITED sources may have a module (status {status})")
        match = MODULE_RE.match(module) if isinstance(module, str) else None
        if not match:
            errors.append(f"{where}: 'module' must look like '<lang>.<name>'")
            continue
        if module in claimed_modules:
            errors.append(f"{where}: module {module} is already claimed by {claimed_modules[module]}")
        claimed_modules[module] = str(sid)
        ext = extensions.get(module)
        if ext is None:
            errors.append(f"{where}: module src/{match['lang']}/{match['name']} does not exist")
            continue
        if ext.errors:
            errors.append(f"{where}: module has metadata errors: {'; '.join(ext.errors)}")
        if ext.lib_version != lib_level:
            errors.append(f"{where}: module targets lib {ext.lib_version}, repository level is {lib_level}")
        if ext.metadata.get("isNsfw") is not src["nsfw"]:
            errors.append(f"{where}: nsfw does not match the module's isNsfw")
        if not any(v == "implemented" for v in caps.values()):
            errors.append(f"{where}: a module must implement at least one capability")
        tests = list((ext.path / "test" / "kotlin").rglob("*Test.kt"))
        if not tests:
            errors.append(f"{where}: module has no unit tests under test/kotlin")
        if not (ext.path / "test" / "resources" / "fixtures").is_dir():
            errors.append(f"{where}: module has no recorded fixtures under test/resources/fixtures")

    for module, ext in extensions.items():
        if module not in claimed_modules:
            errors.append(f"src/{ext.lang}/{ext.name}: extension module has no READY/LIMITED registry entry")

    if catalog is not None:
        listed = {e.get("pkg") for e in catalog if isinstance(e, dict)}
        for module, ext in extensions.items():
            if module in claimed_modules and ext.pkg not in listed:
                errors.append(f"catalog: {ext.pkg} ({module}) is eligible but missing from the catalog")
        allowed = {extensions[m].pkg for m in claimed_modules if m in extensions}
        for pkg in sorted(p for p in listed - allowed if p):
            errors.append(f"catalog: {pkg} is published without an eligible registry entry")
    return errors, warnings


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def render(registry: dict) -> str:
    sources = registry["sources"]
    lines = [
        "# Source audit",
        "",
        "<!-- Generated by `python3 tools/source_registry.py render` from sources/registry.json. Do not edit by hand. -->",
        "",
        "Every audited or discovered source, implemented or not. Statuses: **READY** (verified and",
        "permitted), **LIMITED** (usable with documented limits), **REVIEW_REQUIRED** (licensing, terms or",
        "authorization unresolved), **BLOCKED** (cannot proceed under the applicable restrictions),",
        "**UNREACHABLE** (could not be inspected). Discovery is not authorization.",
        "",
        "## Summary",
        "",
        "| Source | Category | Lang | Status | Module | Catalog | Search | Details | Episodes | Playback | Verified |",
        "|--------|----------|------|--------|--------|---------|--------|---------|----------|----------|----------|",
    ]
    for s in sources:
        caps = s["capabilities"]
        lines.append(
            f"| [{_cell(s['name'])}](#{s['id']}) | {CATEGORIES[s['category']].split('.')[0]} | {', '.join(s['languages'])} | "
            f"**{s['status']}** | {('`' + s['module'] + '`') if s['module'] else '—'} | "
            + " | ".join(caps[c] for c in CAPABILITIES)
            + f" | {s['last_verified']} |"
        )
    lines.append("")
    for category, title in CATEGORIES.items():
        group = [s for s in sources if s["category"] == category]
        if not group:
            continue
        lines += [f"## {title}", ""]
        for s in group:
            docs = s["integration"]["api_docs"]
            lines += [
                f'<a id="{s["id"]}"></a>',
                f"### {s['name']} — {s['status']}",
                "",
                f"- **URL:** {s['url']}",
                f"- **Languages:** {', '.join(s['languages'])}; **NSFW:** {'yes' if s['nsfw'] else 'no'}",
                f"- **Content:** {s['content']}",
                f"- **Integration:** {s['integration']['method']}" + (f" ([docs]({docs}))" if docs else ""),
                f"- **Permission / licensing:** {s['permission']}",
                f"- **Technical:** {s['technical']}",
                f"- **Module:** {('`src/' + s['module'].replace('.', '/', 1) + '`') if s['module'] else 'none'}",
                f"- **Last verified:** {s['last_verified']}",
                "- **Evidence:**",
                *[f"  - {e}" for e in s["evidence"]],
            ]
            if s["restrictions"]:
                lines += ["- **Restrictions:**", *[f"  - {r}" for r in s["restrictions"]]]
            lines += [f"- **Next action:** {s['next_action']}", ""]
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate or render the source registry.")
    sub = parser.add_subparsers(dest="command", required=True)
    v = sub.add_parser("validate")
    v.add_argument("--catalog", type=Path, help="generated index.min.json to cross-check")
    v.add_argument("--max-age-days", type=int, default=180, help="warn when an entry is older than this")
    v.add_argument("--strict", action="store_true", help="treat warnings as errors")
    r = sub.add_parser("render")
    r.add_argument("--check", action="store_true", help="fail if docs/SOURCE_AUDIT.md is out of date")
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    args = parser.parse_args()

    try:
        registry = json.loads((args.root / REGISTRY).read_text("utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        print(f"error: cannot read {REGISTRY}: {e}", file=sys.stderr)
        return 1

    if args.command == "render":
        errors, _ = validate(registry, args.root, dt.date.today(), None)
        if errors:
            print("\n".join(f"error: {e}" for e in errors), file=sys.stderr)
            return 1
        content = render(registry)
        target = args.root / AUDIT_DOC
        if args.check:
            if not target.is_file() or target.read_text("utf-8") != content:
                print(f"error: {AUDIT_DOC} is out of date; run: python3 tools/source_registry.py render", file=sys.stderr)
                return 1
            print(f"{AUDIT_DOC} is up to date")
            return 0
        target.write_text(content, "utf-8")
        print(f"wrote {AUDIT_DOC}")
        return 0

    catalog = json.loads(args.catalog.read_text("utf-8")) if args.catalog else None
    errors, warnings = validate(registry, args.root, dt.date.today(), args.max_age_days, catalog)
    for w in warnings:
        print(f"warning: {w}")
    for e in errors:
        print(f"error: {e}")
    if args.strict:
        errors += warnings
    counts = {s: sum(1 for x in registry["sources"] if isinstance(x, dict) and x.get("status") == s) for s in STATUSES}
    print(f"Registry: {len(registry['sources'])} source(s) — " + ", ".join(f"{k} {v}" for k, v in counts.items()) + f": {'FAILED' if errors else 'OK'}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
