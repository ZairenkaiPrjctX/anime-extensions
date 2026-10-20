"""Builds minimal synthetic repositories for tool tests."""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
REPO_ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

VERSIONS_TOML = '[versions]\nextension-lib = "16"\n'


class RepoTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="ase-tools-"))
        self.addCleanup(shutil.rmtree, self.root)
        (self.root / "gradle").mkdir()
        (self.root / "gradle" / "ase.versions.toml").write_text(VERSIONS_TOML)

    def add_extension(self, lang: str, name: str, ext_block: str | None = None, *, cls: str = "MySource",
                      source: str | None = None, readme: bool = True, extra_gradle: str = "") -> Path:
        module = self.root / "src" / lang / name
        module.mkdir(parents=True)
        block = ext_block if ext_block is not None else (
            f"    extName = 'My Source'\n    extClass = '.{cls}'\n    extVersionCode = 3\n    isNsfw = false\n"
        )
        (module / "build.gradle").write_text(f"ext {{\n{block}}}\n\napply plugin: 'ase.extension'\n{extra_gradle}")
        pkg_dir = module / "src" / "eu" / "kanade" / "tachiyomi" / "animeextension" / lang / name
        pkg_dir.mkdir(parents=True)
        body = source if source is not None else f"package eu.kanade.tachiyomi.animeextension.{lang}.{name}\n\nclass {cls}\n"
        (pkg_dir / f"{cls}.kt").write_text(body)
        if readme:
            (module / "README.md").write_text("# test\n")
        return module
