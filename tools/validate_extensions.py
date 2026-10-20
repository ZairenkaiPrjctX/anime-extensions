#!/usr/bin/env python3
"""Validates extension modules and repository hygiene without running Gradle.

Checks every `src/<lang>/<name>` module for:
  - directory naming, `build.gradle` metadata (same rules as the Gradle plugin);
  - the `extClass` source file existing in the right package;
  - a module README documenting source-specific behaviour;
  - launcher icons (all densities, or none to inherit the default from :core);
  - unfinished scaffolds and risky code patterns (disabled TLS checks, Thread.sleep, plain HTTP);
plus the presence of the repository's licensing and documentation files.

Exit status is 1 if any error is found. Usage:

    python3 tools/validate_extensions.py [--root DIR] [--json]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from repo_model import DENSITIES, REPO_ROOT, Extension, discover_extensions  # noqa: E402

REQUIRED_REPO_FILES = (
    "LICENSE",
    "NOTICE",
    "README.md",
    "CONTRIBUTING.md",
    "AGENTS.md",
    "CLAUDE.md",
    "docs/ARCHITECTURE.md",
    "docs/COMPATIBILITY.md",
    "docs/DEVELOPMENT.md",
    "docs/DISTRIBUTION.md",
)

TEMPLATE_MARKER = "ASE_TEMPLATE_UNIMPLEMENTED"


@dataclass(frozen=True)
class CodeRule:
    pattern: re.Pattern[str]
    message: str


CODE_RULES = (
    CodeRule(re.compile(r"\bThread\.sleep\s*\("), "Thread.sleep blocks a worker thread; use aniext.core.network.rateLimit or coroutine delay"),
    CodeRule(re.compile(r"X509TrustManager|TrustAllCerts|trustAllCerts"), "custom trust managers (disabling TLS verification) are not allowed"),
    CodeRule(re.compile(r"hostnameVerifier\s*[({]"), "overriding hostname verification is not allowed"),
    CodeRule(re.compile(r"\bbaseUrl\s*=\s*\"http://"), "baseUrl must use https"),
    CodeRule(re.compile(r"^import eu\.kanade\.tachiyomi\.network\.interceptor\.rateLimit", re.M), "the extensions-lib rateLimit is a no-op in lib 16; use aniext.core.network.rateLimit"),
    CodeRule(re.compile(TEMPLATE_MARKER), "scaffold is not implemented yet (template placeholder still present)"),
)


@dataclass
class Finding:
    location: str
    message: str


def check_extension(ext: Extension) -> list[Finding]:
    where = f"src/{ext.lang}/{ext.name}"
    findings = [Finding(where, e) for e in ext.errors]
    if not ext.metadata:
        return findings

    ext_class = ext.metadata.get("extClass")
    if isinstance(ext_class, str) and ext_class.startswith("."):
        parts = ext_class[1:].split(".")
        class_file = ext.source_dir.joinpath(*parts[:-1], parts[-1] + ".kt")
        if not class_file.is_file():
            findings.append(Finding(where, f"extClass '{ext_class}' expected at {class_file.relative_to(ext.path)}"))
        else:
            package = ".".join([ext.pkg, *parts[:-1]])
            if not re.search(rf"^package {re.escape(package)}\s*$", class_file.read_text("utf-8"), re.M):
                findings.append(Finding(str(class_file.relative_to(ext.path.parent.parent.parent)), f"package must be '{package}'"))

    if not (ext.path / "README.md").is_file():
        findings.append(Finding(where, "missing README.md documenting source-specific behaviour"))

    res = ext.path / "res"
    icons = [d for d in DENSITIES if (res / f"mipmap-{d}" / "ic_launcher.png").is_file()]
    if icons and len(icons) != len(DENSITIES):
        missing = sorted(set(DENSITIES) - set(icons))
        findings.append(Finding(where, f"incomplete launcher icon set, missing: {', '.join(missing)}"))
    if (ext.path / "web_hi_res_512.png").exists() or (res / "web_hi_res_512.png").exists():
        findings.append(Finding(where, "remove web_hi_res_512.png (Icon Generator leftover)"))

    findings.extend(scan_code(sorted(ext.path.glob("src/**/*.kt")), ext.path.parent.parent.parent, CODE_RULES))
    return findings


def scan_code(files: list[Path], root: Path, rules: tuple[CodeRule, ...]) -> list[Finding]:
    findings = []
    for kotlin in files:
        text = kotlin.read_text("utf-8")
        for rule in rules:
            for match in rule.pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(Finding(f"{kotlin.relative_to(root)}:{line}", rule.message))
    return findings


def check_repository(root: Path) -> list[Finding]:
    findings = [Finding(rel, "required file is missing") for rel in REQUIRED_REPO_FILES if not (root / rel).is_file()]
    for secret in ("signingkey.jks", "local.properties"):
        if (root / secret).exists():
            findings.append(Finding(secret, "must not be committed (listed in .gitignore)"))
    shared = [p for d in ("core", "lib", "lib-multisrc") for p in sorted((root / d).glob("**/src/**/*.kt")) if "/build/" not in p.as_posix()]
    findings.extend(scan_code(shared, root, tuple(r for r in CODE_RULES if r.pattern.pattern != TEMPLATE_MARKER)))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate extension modules and repository hygiene.")
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--json", action="store_true", help="print findings as JSON")
    parser.add_argument("--skip-repo-files", action="store_true", help="only validate extension modules")
    args = parser.parse_args()

    extensions = discover_extensions(args.root)
    findings = [] if args.skip_repo_files else check_repository(args.root)
    for ext in extensions:
        findings.extend(check_extension(ext))

    if args.json:
        print(json.dumps({"extensions": len(extensions), "errors": [f.__dict__ for f in findings]}, indent=2))
    else:
        for f in findings:
            print(f"error: {f.location}: {f.message}")
        print(f"Checked {len(extensions)} extension module(s): {'FAILED' if findings else 'OK'} ({len(findings)} error(s))")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
