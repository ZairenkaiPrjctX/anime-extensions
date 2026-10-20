import helpers  # noqa: F401  (puts tools/ on sys.path)
import json
import unittest
from pathlib import Path

from generate_catalog import ApkInfo, CatalogError, build_entries, check_signers, write_catalog
from helpers import RepoTestCase
from validate_catalog import validate_entries, validate_repo

PKG = "eu.kanade.tachiyomi.animeextension.en.mysite"
SIGNER = "a" * 64
INSPECTOR = {PKG: [{"name": "My Source", "lang": "en", "id": "123456789", "baseUrl": "https://mysite.example", "versionId": 1}]}
META = {"name": "Test Repo", "shortName": "TR", "website": "https://example.org"}
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16


def apk(path: Path, **overrides) -> ApkInfo:
    values = dict(path=path, pkg=PKG, code=3, version="16.3", label="Aniyomi: My Source", icon="res/icon.png",
                  nsfw=0, torrent=0, signer_sha256=SIGNER, signer_dn="CN=Release")
    values.update(overrides)
    return ApkInfo(**values)


class CatalogTest(RepoTestCase):
    def setUp(self):
        super().setUp()
        self.add_extension("en", "mysite")
        import zipfile
        self.apk_path = self.root / "built" / "aniyomi-en.mysite-v16.3-release.apk"
        self.apk_path.parent.mkdir()
        with zipfile.ZipFile(self.apk_path, "w") as z:
            z.writestr("res/icon.png", PNG)

    def generate(self, infos=None, inspector=None):
        infos = infos or [apk(self.apk_path)]
        entries = build_entries(infos, inspector or INSPECTOR, self.root)
        out = self.root / "repo"
        write_catalog(out, infos, entries, META, check_signers(infos, allow_debug=False), "abc1234")
        return out

    def test_generated_catalog_matches_the_app_schema_and_validates(self):
        out = self.generate()
        index = json.loads((out / "index.min.json").read_text())
        self.assertEqual(index, [{
            "name": "Aniyomi: My Source", "pkg": PKG, "apk": "aniyomi-en.mysite-v16.3.apk", "lang": "en",
            "code": 3, "version": "16.3", "nsfw": 0,
            "sources": [{"name": "My Source", "lang": "en", "id": "123456789", "baseUrl": "https://mysite.example"}],
        }])
        self.assertEqual(json.loads((out / "repo.json").read_text())["meta"]["signingKeyFingerprint"], SIGNER)
        self.assertEqual((out / "icon" / f"{PKG}.png").read_bytes(), PNG)
        self.assertEqual(validate_repo(out, self.root), [])

    def test_output_is_deterministic(self):
        first = (self.generate() / "index.min.json").read_bytes()
        second = (self.generate() / "index.min.json").read_bytes()
        self.assertEqual(first, second)

    def test_stale_or_unknown_artifacts_are_refused(self):
        for info, fragment in (
            (apk(self.apk_path, code=2, version="16.2"), "stale artifact"),
            (apk(self.apk_path, pkg="eu.kanade.tachiyomi.animeextension.en.other"), "no module"),
            (apk(self.apk_path, nsfw=1), "nsfw"),
            (apk(self.apk_path, label="Aniyomi: Renamed"), "label"),
        ):
            with self.subTest(fragment=fragment), self.assertRaisesRegex(CatalogError, fragment):
                build_entries([info], INSPECTOR, self.root)
        with self.assertRaisesRegex(CatalogError, "no sources"):
            build_entries([apk(self.apk_path)], {}, self.root)

    def test_signing_rules(self):
        with self.assertRaisesRegex(CatalogError, "different certificates"):
            check_signers([apk(self.apk_path), apk(self.apk_path, signer_sha256="b" * 64)], allow_debug=False)
        debug = apk(self.apk_path, signer_dn="C=US, O=Android, CN=Android Debug")
        with self.assertRaisesRegex(CatalogError, "debug certificate"):
            check_signers([debug], allow_debug=False)
        self.assertEqual(check_signers([debug], allow_debug=True), SIGNER)

    def test_validator_detects_missing_stale_and_inconsistent_files(self):
        out = self.generate()
        (out / "apk" / "aniyomi-en.mysite-v16.3.apk").unlink()
        (out / "apk" / "leftover.apk").write_bytes(b"x")
        (out / "icon" / f"{PKG}.png").write_bytes(b"not a png")
        (out / "index.json").write_text("[]")
        errors = " | ".join(validate_repo(out))
        for fragment in ("is missing", "stale artifact", "not a PNG", "index.json does not match"):
            self.assertIn(fragment, errors)

    def test_validator_tree_check_catches_version_drift(self):
        out = self.generate()
        build = self.root / "src" / "en" / "mysite" / "build.gradle"
        build.write_text(build.read_text().replace("extVersionCode = 3", "extVersionCode = 4"))
        self.assertTrue(any("source tree version 16.4" in e for e in validate_repo(out, self.root)))


class EntrySchemaTest(unittest.TestCase):
    def entry(self, **overrides):
        value = {"name": "Aniyomi: X", "pkg": PKG, "apk": "aniyomi-en.mysite-v16.3.apk", "lang": "en", "code": 3,
                 "version": "16.3", "nsfw": 0, "sources": [{"name": "X", "lang": "en", "id": "42", "baseUrl": "https://x.org"}]}
        value.update(overrides)
        return value

    def test_valid_entry(self):
        self.assertEqual(validate_entries([self.entry()]), [])

    def test_invalid_values(self):
        cases = {
            "code": dict(code=4),
            "outside the app's supported range": dict(version="17.3", apk="aniyomi-en.mysite-v17.3.apk"),
            "'apk' must be": dict(apk="other.apk"),
            "nsfw": dict(nsfw=2),
            "'name' must be": dict(name="X"),
            "invalid package": dict(pkg="com.example.ext"),
            "positive 64-bit": dict(sources=[{"name": "X", "lang": "en", "id": "-1", "baseUrl": "https://x.org"}]),
            "https URL": dict(sources=[{"name": "X", "lang": "en", "id": "1", "baseUrl": "http://user:pw@x.org"}]),
            "must not be empty": dict(sources=[]),
            "'code' must be int": dict(code=True),
        }
        for fragment, overrides in cases.items():
            with self.subTest(fragment=fragment):
                errors = " | ".join(validate_entries([self.entry(**overrides)]))
                self.assertIn(fragment, errors)

    def test_duplicates_across_entries(self):
        errors = " | ".join(validate_entries([self.entry(), self.entry()]))
        self.assertIn("duplicate package", errors)
        self.assertIn("duplicate source id", errors)

    def test_not_a_list(self):
        self.assertEqual(validate_entries({}), ["index.min.json must contain a JSON array"])


if __name__ == "__main__":
    unittest.main()
