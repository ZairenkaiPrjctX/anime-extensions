#!/usr/bin/env python3
"""Scaffolds a new extension module from template/extension/.

    python3 tools/new_source.py <lang> <name> --display-name "My Site" --base-url https://mysite.example

Creates src/<lang>/<name>/ with build.gradle, the source class (extensions-lib 16 skeleton),
a README and a unit test. Identifiers are validated, the target must stay inside src/, and an
existing module is never overwritten unless --force is given. The generated module compiles but
fails tools/validate_extensions.py until its placeholders are implemented.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
from repo_model import LANG_RE, NAME_RE, PACKAGE_PREFIX, REPO_ROOT, repo_lib_version  # noqa: E402

CLASS_NAME_RE = re.compile(r"^[A-Z][A-Za-z0-9]*$")
KOTLIN_KEYWORDS = {"as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while"}
PLACEHOLDER_RE = re.compile(r"\{\{([A-Z_]+)\}\}")


class ScaffoldError(ValueError):
    pass


def class_name_from(display_name: str) -> str:
    words = re.findall(r"[A-Za-z0-9]+", display_name)
    name = "".join(w[:1].upper() + w[1:] for w in words)
    if name[:1].isdigit():
        name = "Source" + name
    return name


def validate(lang: str, name: str, display_name: str, class_name: str, base_url: str) -> str:
    """Returns the host of base_url; raises ScaffoldError on any invalid input."""
    if not LANG_RE.match(lang):
        raise ScaffoldError(f"invalid lang '{lang}': use a 2-3 letter ISO 639 code, 'all' or 'other'")
    if not NAME_RE.match(name):
        raise ScaffoldError(f"invalid module name '{name}': lowercase ASCII letters and digits only")
    if name in KOTLIN_KEYWORDS or lang in KOTLIN_KEYWORDS:
        raise ScaffoldError(f"'{lang}.{name}' would produce a Kotlin keyword in the package name")
    if not display_name.strip() or any(ord(c) < 0x20 or ord(c) >= 0x180 or c in "'\"\\$" for c in display_name):
        raise ScaffoldError("display name must be non-empty, romanized, and free of quotes, backslashes and '$'")
    if not CLASS_NAME_RE.match(class_name):
        raise ScaffoldError(f"invalid class name '{class_name}': PascalCase ASCII identifier expected")
    parts = urlsplit(base_url)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        raise ScaffoldError("base URL must be an https URL without credentials")
    if parts.path not in ("", "/") or parts.query or parts.fragment or base_url.endswith("/"):
        raise ScaffoldError("base URL must be the site root without path, query or trailing slash (e.g. https://example.org)")
    if not re.fullmatch(r"[a-z0-9.-]+(?::\d+)?", parts.netloc.lower()) or parts.netloc != parts.netloc.lower():
        raise ScaffoldError("base URL host must be a lowercase DNS name")
    return parts.hostname


def render(template: str, values: dict[str, str]) -> str:
    def replace(match: re.Match[str]) -> str:
        key = match[1]
        if key not in values:
            raise ScaffoldError(f"template uses unknown placeholder {{{{{key}}}}}")
        return values[key]

    return PLACEHOLDER_RE.sub(replace, template)


def plan(root: Path, lang: str, name: str, display_name: str, class_name: str, base_url: str, nsfw: bool) -> dict[Path, str]:
    host = validate(lang, name, display_name, class_name, base_url)
    src_root = (root / "src").resolve()
    module = (src_root / lang / name).resolve()
    if not module.is_relative_to(src_root) or module.parent.parent != src_root:
        raise ScaffoldError("target directory escapes src/")
    package = f"{PACKAGE_PREFIX}.{lang}.{name}"
    package_path = Path(*package.split("."))
    values = {
        "DISPLAY_NAME": display_name,
        "CLASS_NAME": class_name,
        "LIB_VERSION": str(repo_lib_version(root)),
        "IS_NSFW": "true" if nsfw else "false",
        "PACKAGE": package,
        "LANG": lang,
        "MODULE": name,
        "BASE_URL": base_url,
        "HOST": host,
    }
    templates = root / "template" / "extension"
    return {
        module / "build.gradle": render((templates / "build.gradle.tmpl").read_text("utf-8"), values),
        module / "README.md": render((templates / "README.md.tmpl").read_text("utf-8"), values),
        module / "src" / package_path / f"{class_name}.kt": render((templates / "Source.kt.tmpl").read_text("utf-8"), values),
        module / "test" / "kotlin" / package_path / f"{class_name}Test.kt": render((templates / "SourceTest.kt.tmpl").read_text("utf-8"), values),
    }


def write(files: dict[Path, str], force: bool) -> None:
    module = next(iter(files)).parent
    if module.exists() and not force:
        raise ScaffoldError(f"{module} already exists; pass --force to overwrite the generated files")
    for path, content in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, "utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Scaffold a new extension module.")
    parser.add_argument("lang", help="module language directory, e.g. en, id, all")
    parser.add_argument("name", help="module name: lowercase letters and digits")
    parser.add_argument("--display-name", required=True, help="source name shown in the app (romanized)")
    parser.add_argument("--base-url", required=True, help="https site root, e.g. https://example.org")
    parser.add_argument("--class-name", help="Kotlin class name (default: derived from the display name)")
    parser.add_argument("--nsfw", action="store_true", help="mark the extension as NSFW")
    parser.add_argument("--force", action="store_true", help="overwrite an existing module's generated files")
    parser.add_argument("--dry-run", action="store_true", help="print the files that would be written")
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    try:
        class_name = args.class_name or class_name_from(args.display_name)
        files = plan(args.root, args.lang, args.name, args.display_name, class_name, args.base_url, args.nsfw)
        if not args.dry_run:
            write(files, args.force)
    except (ScaffoldError, OSError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for path in files:
        print(("would write " if args.dry_run else "wrote ") + str(path.relative_to(args.root.resolve())))
    if not args.dry_run:
        print(f"Next: ./gradlew -Pextensions={args.lang}:{args.name} :src:{args.lang}:{args.name}:compileDebugKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
