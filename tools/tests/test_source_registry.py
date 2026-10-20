import helpers  # noqa: F401  (puts tools/ on sys.path)
import copy
import datetime as dt
import json
import unittest

from helpers import REPO_ROOT, RepoTestCase
from source_registry import render, validate

TODAY = dt.date(2026, 10, 20)


def entry(**overrides):
    value = {
        "id": "mysite", "name": "My Site", "url": "https://mysite.example/", "origin": "discovered",
        "category": "D-open-collection", "languages": ["en"], "content": "Films", "nsfw": False,
        "status": "READY", "last_verified": "2026-10-01",
        "integration": {"method": "Public API", "api_docs": "https://mysite.example/docs"},
        "permission": "Public API", "evidence": ["GET / -> 200"], "technical": "REST",
        "restrictions": [], "module": "en.mysite", "next_action": "None",
        "capabilities": {c: "implemented" for c in ("catalog", "search", "details", "episodes", "playback")},
    }
    value.update(overrides)
    return value


BLOCKED = dict(id="blocked", url="https://blocked.example/", status="BLOCKED", module=None,
               capabilities={c: "not-implemented" for c in ("catalog", "search", "details", "episodes", "playback")})


class RegistryTest(RepoTestCase):
    def setUp(self):
        super().setUp()
        module = self.add_extension("en", "mysite")
        test_dir = module / "test" / "kotlin" / "x"
        test_dir.mkdir(parents=True)
        (test_dir / "ParserTest.kt").write_text("class ParserTest")
        (module / "test" / "resources" / "fixtures").mkdir(parents=True)
        (module / "build.gradle").write_text((module / "build.gradle").read_text().replace("isNsfw = false", "isNsfw = false\n    libVersion = 16"))

    def check(self, *sources, catalog=None, max_age=None):
        return validate({"schema_version": 1, "sources": list(sources)}, self.root, TODAY, max_age, catalog)

    def test_valid_registry(self):
        self.assertEqual(self.check(entry(), entry(**BLOCKED)), ([], []))

    def test_duplicate_ids_and_urls(self):
        errors, _ = self.check(entry(), entry(module=None, status="REVIEW_REQUIRED", capabilities=BLOCKED["capabilities"]))
        joined = " | ".join(errors)
        self.assertIn("duplicate id", joined)
        self.assertIn("url duplicates", joined)

    def test_ineligible_status_cannot_have_a_module(self):
        errors, _ = self.check(entry(status="BLOCKED"))
        self.assertTrue(any("only READY/LIMITED" in e for e in errors))

    def test_every_module_needs_an_eligible_entry(self):
        errors, _ = self.check(entry(**BLOCKED))
        self.assertIn("src/en/mysite: extension module has no READY/LIMITED registry entry", errors)

    def test_capability_claims_need_a_module(self):
        errors, _ = self.check(entry(), entry(**{**BLOCKED, "capabilities": {**BLOCKED["capabilities"], "playback": "implemented"}}))
        self.assertTrue(any("claim an implementation" in e for e in errors))

    def test_module_consistency(self):
        cases = {
            "does not exist": entry(module="en.other"),
            "nsfw does not match": entry(nsfw=True),
            "'module' must look like": entry(module="../etc"),
        }
        for fragment, src in cases.items():
            with self.subTest(fragment=fragment):
                self.assertTrue(any(fragment in e for e in self.check(src)[0]), self.check(src)[0])

    def test_tests_and_fixtures_are_required(self):
        (self.root / "src/en/mysite/test/kotlin/x/ParserTest.kt").unlink()
        (self.root / "src/en/mysite/test/resources/fixtures").rmdir()
        joined = " | ".join(self.check(entry())[0])
        self.assertIn("no unit tests", joined)
        self.assertIn("no recorded fixtures", joined)

    def test_field_formats(self):
        cases = {
            "kebab-case": entry(id="My_Site"),
            "https URL": entry(url="http://mysite.example/"),
            "locale codes": entry(languages=["english"]),
            "status must be": entry(status="DONE"),
            "ISO date": entry(last_verified="20/10/2026"),
            "in the future": entry(last_verified="2026-12-01"),
            "api_docs must be": entry(integration={"method": "x", "api_docs": "ftp://x"}),
            "at least one observation": entry(evidence=[]),
            "unknown category": entry(category="pirate"),
            "requested sources use": entry(origin="requested"),
            "missing fields": {k: v for k, v in entry().items() if k != "permission"},
        }
        for fragment, src in cases.items():
            with self.subTest(fragment=fragment):
                errors, _ = self.check(src)
                self.assertTrue(any(fragment in e for e in errors), errors)

    def test_stale_entries_warn(self):
        _, warnings = self.check(entry(last_verified="2026-01-01"), max_age=180)
        self.assertTrue(any("older than 180 days" in w for w in warnings))

    def test_catalog_cross_check(self):
        pkg = "eu.kanade.tachiyomi.animeextension.en.mysite"
        self.assertEqual(self.check(entry(), catalog=[{"pkg": pkg}])[0], [])
        self.assertTrue(any("missing from the catalog" in e for e in self.check(entry(), catalog=[])[0]))
        rogue = self.check(entry(), catalog=[{"pkg": pkg}, {"pkg": "eu.kanade.tachiyomi.animeextension.id.rogue"}])[0]
        self.assertTrue(any("published without an eligible registry entry" in e for e in rogue))

    def test_render_lists_every_source_and_escapes_tables(self):
        doc = render({"schema_version": 1, "sources": [entry(name="A | B"), entry(**BLOCKED)]})
        self.assertIn("A \\| B", doc)
        self.assertIn("### My Site — BLOCKED", doc)
        self.assertIn('<a id="blocked"></a>', doc)


class RealRegistryTest(unittest.TestCase):
    def test_repository_registry_is_valid_and_requested_sites_are_all_present(self):
        registry = json.loads((REPO_ROOT / "sources" / "registry.json").read_text())
        errors, _ = validate(copy.deepcopy(registry), REPO_ROOT, dt.date.today(), None)
        self.assertEqual(errors, [])
        requested = {s["url"] for s in registry["sources"] if s["origin"] == "requested"}
        self.assertEqual(requested, {
            "https://x6.sokuja.uk/", "https://minioppai.org/", "https://v20.kuramanime.ing/",
            "https://animeindo.skin/home", "https://kuronime.sbs/", "https://nimegami.id/",
        })


if __name__ == "__main__":
    unittest.main()
