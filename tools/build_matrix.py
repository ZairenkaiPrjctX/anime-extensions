#!/usr/bin/env python3
"""Computes which extension modules a change affects and emits a GitHub Actions matrix.

Modes (exactly one):
  --base REF        modules affected by `git diff REF...HEAD` (PRs/pushes)
  --all             every extension module (releases)
  --modules LIST    explicit comma-separated `<lang>:<name>` list (manual builds); unknown
                    modules or an empty list are an error, never a silent "nothing to build"

Dependency rules:
  src/<lang>/<name>/**             -> that module
  lib-multisrc/<theme>/**          -> modules whose build.gradle sets themePkg = '<theme>'
  lib/<lib>/**                     -> modules/themes depending on project(':lib:<lib>'), transitively
  core/, common/, gradle/, root build files, tools/repo_model.py -> every module
  *.md and docs/ only              -> nothing

Outputs (also written to $GITHUB_OUTPUT when set):
  matrix   {"include": [{"module", "lang", "name", "artifact"}]}
  count    number of modules
  deleted  modules removed by the change (lang.name), for catalog maintenance
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from repo_model import LANG_RE, NAME_RE, REPO_ROOT  # noqa: E402

GLOBAL_PATHS = re.compile(
    r"^(core/|common/|gradle/|build\.gradle\.kts$|settings\.gradle\.kts$|gradle\.properties$|gradlew(\.bat)?$|\.editorconfig$|tools/repo_model\.py$)"
)
EXTENSION_PATH = re.compile(r"^src/(?P<lang>[^/]+)/(?P<name>[^/]+)/")
THEME_PATH = re.compile(r"^lib-multisrc/(?P<theme>[^/]+)/")
LIB_PATH = re.compile(r"^lib/(?P<lib>[^/]+)/")
LIB_DEP = re.compile(r"""project\(\s*(?:path\s*[:=]\s*)?["']:lib:(?P<lib>[A-Za-z0-9_-]+)["']""")
THEME_DEP = re.compile(r"""themePkg\s*=\s*["'](?P<theme>[A-Za-z0-9_-]+)["']""")


class MatrixError(RuntimeError):
    pass


def all_modules(root: Path) -> set[tuple[str, str]]:
    src = root / "src"
    if not src.is_dir():
        return set()
    return {
        (lang.name, mod.name)
        for lang in src.iterdir() if lang.is_dir()
        for mod in lang.iterdir() if (mod / "build.gradle").is_file()
    }


def _read(path: Path) -> str:
    return path.read_text("utf-8") if path.is_file() else ""


def _lib_deps(text: str) -> set[str]:
    return {m["lib"] for m in LIB_DEP.finditer(text)}


def dependents_of_libs(root: Path, libs: set[str]) -> tuple[set[str], set[tuple[str, str]]]:
    """Themes and extension modules depending (transitively through other libs) on `libs`."""
    affected_libs = set(libs)
    lib_root = root / "lib"
    changed = True
    while changed and lib_root.is_dir():
        changed = False
        for lib in lib_root.iterdir():
            if lib.name not in affected_libs and _lib_deps(_read(lib / "build.gradle.kts")) & affected_libs:
                affected_libs.add(lib.name)
                changed = True
    themes = set()
    theme_root = root / "lib-multisrc"
    if theme_root.is_dir():
        themes = {t.name for t in theme_root.iterdir() if _lib_deps(_read(t / "build.gradle.kts")) & affected_libs}
    modules = {m for m in all_modules(root) if _lib_deps(_read(root / "src" / m[0] / m[1] / "build.gradle")) & affected_libs}
    return themes, modules


def users_of_themes(root: Path, themes: set[str]) -> set[tuple[str, str]]:
    result = set()
    for lang, name in all_modules(root):
        match = THEME_DEP.search(_read(root / "src" / lang / name / "build.gradle"))
        if match and match["theme"] in themes:
            result.add((lang, name))
    return result


def affected_by(root: Path, changed_files: list[str]) -> tuple[set[tuple[str, str]], set[str]]:
    existing = all_modules(root)
    modules: set[tuple[str, str]] = set()
    deleted: set[str] = set()
    themes: set[str] = set()
    libs: set[str] = set()
    for path in changed_files:
        if path.endswith(".md") or path.startswith("docs/"):
            continue
        if GLOBAL_PATHS.match(path):
            return set(existing), deleted
        if m := EXTENSION_PATH.match(path):
            key = (m["lang"], m["name"])
            if key in existing:
                modules.add(key)
            else:
                deleted.add(f"{key[0]}.{key[1]}")
        elif m := THEME_PATH.match(path):
            themes.add(m["theme"])
        elif m := LIB_PATH.match(path):
            libs.add(m["lib"])
    lib_themes, lib_modules = dependents_of_libs(root, libs)
    modules |= lib_modules | users_of_themes(root, themes | lib_themes)
    return modules, deleted


def changed_files(root: Path, base: str) -> list[str]:
    try:
        out = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", f"{base}...HEAD"],
            cwd=root, check=True, capture_output=True, text=True,
        ).stdout
    except subprocess.CalledProcessError as e:
        raise MatrixError(f"git diff against '{base}' failed: {e.stderr.strip()}") from e
    return [line for line in out.splitlines() if line]


def parse_module_list(root: Path, raw: str) -> set[tuple[str, str]]:
    existing = all_modules(root)
    selected = set()
    for entry in (e.strip() for e in raw.replace(" ", ",").split(",")):
        if not entry:
            continue
        lang, sep, name = entry.removeprefix(":src:").replace(":", ".", 1).partition(".")
        if not sep or not LANG_RE.match(lang) or not NAME_RE.match(name):
            raise MatrixError(f"invalid module '{entry}', expected '<lang>:<name>'")
        if (lang, name) not in existing:
            raise MatrixError(f"module '{lang}:{name}' does not exist")
        selected.add((lang, name))
    if not selected:
        raise MatrixError("no modules selected")
    return selected


def to_matrix(modules: set[tuple[str, str]]) -> dict:
    return {
        "include": [
            {"module": f":src:{lang}:{name}", "lang": lang, "name": name, "artifact": f"apk-{lang}-{name}"}
            for lang, name in sorted(modules)
        ]
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--base", help="git ref to diff against (merge-base semantics)")
    mode.add_argument("--all", action="store_true")
    mode.add_argument("--modules", help="comma-separated <lang>:<name> list")
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    args = parser.parse_args()

    try:
        deleted: set[str] = set()
        if args.all:
            modules = all_modules(args.root)
            if not modules:
                raise MatrixError("no extension modules found under src/")
        elif args.modules is not None:
            modules = parse_module_list(args.root, args.modules)
        else:
            modules, deleted = affected_by(args.root, changed_files(args.root, args.base))
    except MatrixError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    matrix = to_matrix(modules)
    print(f"{len(modules)} module(s) to build:", ", ".join(i["module"] for i in matrix["include"]) or "none")
    if deleted:
        print("Deleted modules:", ", ".join(sorted(deleted)))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as fh:
            fh.write(f"matrix={json.dumps(matrix, separators=(',', ':'))}\n")
            fh.write(f"count={len(modules)}\n")
            fh.write(f"deleted={json.dumps(sorted(deleted))}\n")
    else:
        print(json.dumps(matrix, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
