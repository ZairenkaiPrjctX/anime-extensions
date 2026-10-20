import helpers  # noqa: F401  (puts tools/ on sys.path)
import unittest

from helpers import RepoTestCase
from repo_model import MetadataError, discover_extensions, parse_ext_block, source_id


class ParseExtBlockTest(unittest.TestCase):
    def test_literals_and_comments(self):
        meta = parse_ext_block(
            "ext {\n    extName = 'A // B'   // trailing comment\n    extVersionCode = 12\n"
            "    isNsfw = true\n    baseUrl = \"https://x.org\"\n}\napply plugin: 'ase.extension'\n"
        )
        self.assertEqual(meta, {"extName": "A // B", "extVersionCode": 12, "isNsfw": True, "baseUrl": "https://x.org"})

    def test_rejects_expressions_interpolation_and_duplicates(self):
        for body in ("extVersionCode = 1 + 1", 'extName = "${x}"', "extName = 'a'\n    extName = 'b'", "garbage"):
            with self.subTest(body=body), self.assertRaises(MetadataError):
                parse_ext_block(f"ext {{\n    {body}\n}}\n")
        with self.assertRaises(MetadataError):
            parse_ext_block("apply plugin: 'ase.extension'\n")


class SourceIdTest(unittest.TestCase):
    def test_matches_ids_published_by_the_reference_catalog(self):
        # From https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json
        self.assertEqual(source_id("AnimeOnsen", "all"), 8542735178285060053)
        self.assertEqual(source_id("AnimeWorld India", "en"), 8052146309028882026)
        # Reported by aniyomi-extensions-inspector for this repository's release APK.
        self.assertEqual(source_id("Internet Archive", "all"), 1829328537302010998)

    def test_version_id_changes_the_id(self):
        self.assertNotEqual(source_id("X", "en", 1), source_id("X", "en", 2))


class DiscoverTest(RepoTestCase):
    def test_valid_module(self):
        self.add_extension("en", "mysite")
        (ext,) = discover_extensions(self.root)
        self.assertEqual(ext.errors, [])
        self.assertEqual(ext.pkg, "eu.kanade.tachiyomi.animeextension.en.mysite")
        self.assertEqual(ext.version_name, "16.3")
        self.assertEqual(ext.apk_name, "aniyomi-en.mysite-v16.3.apk")

    def test_metadata_errors_are_collected(self):
        self.add_extension("en", "bad", "    extName = 'Ünïcödé ✓'\n    extClass = 'NoDot'\n    extVersionCode = 0\n    libVersion = 17\n")
        (ext,) = discover_extensions(self.root)
        joined = " | ".join(ext.errors)
        for fragment in ("romanized", "extClass", "isNsfw", "libVersion", "extVersionCode"):
            self.assertIn(fragment, joined)

    def test_theme_version_is_base_plus_override(self):
        theme = self.root / "lib-multisrc" / "mytheme"
        theme.mkdir(parents=True)
        (theme / "build.gradle.kts").write_text("plugins { }\nbaseVersionCode = 5\n")
        self.add_extension("en", "themed", "    extName = 'T'\n    extClass = '.MySource'\n    themePkg = 'mytheme'\n    overrideVersionCode = 2\n    isNsfw = false\n")
        (ext,) = discover_extensions(self.root)
        self.assertEqual(ext.errors, [])
        self.assertEqual(ext.version_code, 7)

    def test_invalid_directory_names(self):
        self.add_extension("EN", "My-Site")
        (ext,) = discover_extensions(self.root)
        self.assertTrue(any("language directory" in e for e in ext.errors))
        self.assertTrue(any("module name" in e for e in ext.errors))


if __name__ == "__main__":
    unittest.main()
