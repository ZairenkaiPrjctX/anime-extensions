import helpers  # noqa: F401  (puts tools/ on sys.path)
import unittest

from helpers import RepoTestCase
from repo_model import discover_extensions
from validate_extensions import check_extension, check_repository


class ValidateExtensionsTest(RepoTestCase):
    def findings(self):
        return [f.message for ext in discover_extensions(self.root) for f in check_extension(ext)]

    def test_clean_module_passes(self):
        self.add_extension("en", "mysite")
        self.assertEqual(self.findings(), [])

    def test_missing_class_wrong_package_and_readme(self):
        self.add_extension("en", "mysite", source="package wrong.pkg\n", readme=False)
        messages = " | ".join(self.findings())
        self.assertIn("package must be 'eu.kanade.tachiyomi.animeextension.en.mysite'", messages)
        self.assertIn("README.md", messages)

        self.add_extension("en", "other", "    extName = 'O'\n    extClass = '.Missing'\n    extVersionCode = 1\n    isNsfw = false\n")
        self.assertTrue(any("expected at" in m for m in self.findings()))

    def test_risky_code_patterns_are_reported_with_line_numbers(self):
        source = (
            "package eu.kanade.tachiyomi.animeextension.en.mysite\n"
            "class MySource {\n"
            "    override val baseUrl = \"http://insecure.example\"\n"
            "    fun wait() = Thread.sleep(1000)\n"
            "    val tm = object : X509TrustManager {}\n"
            "    fun todo(): Nothing = throw UnsupportedOperationException(\"ASE_TEMPLATE_UNIMPLEMENTED\")\n"
            "}\n"
        )
        self.add_extension("en", "mysite", source=source)
        ext = discover_extensions(self.root)[0]
        found = {(f.location.rsplit(":", 1)[1], f.message.split()[0]) for f in check_extension(ext)}
        self.assertEqual(found, {("3", "baseUrl"), ("4", "Thread.sleep"), ("5", "custom"), ("6", "scaffold")})

    def test_partial_icon_set_is_rejected(self):
        module = self.add_extension("en", "mysite")
        (module / "res" / "mipmap-hdpi").mkdir(parents=True)
        (module / "res" / "mipmap-hdpi" / "ic_launcher.png").write_bytes(b"png")
        self.assertTrue(any("incomplete launcher icon set" in m for m in self.findings()))

    def test_repository_files_and_secrets(self):
        (self.root / "signingkey.jks").write_bytes(b"x")
        messages = {f.location: f.message for f in check_repository(self.root)}
        self.assertIn("LICENSE", messages)
        self.assertIn("signingkey.jks", messages)


if __name__ == "__main__":
    unittest.main()
