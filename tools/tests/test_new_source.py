import helpers  # noqa: F401  (puts tools/ on sys.path)
import io
import shutil
import unittest
from contextlib import redirect_stderr, redirect_stdout

from helpers import REPO_ROOT, RepoTestCase
from new_source import ScaffoldError, class_name_from, main, plan, validate
from repo_model import discover_extensions
from validate_extensions import check_extension


class NewSourceTest(RepoTestCase):
    def setUp(self):
        super().setUp()
        shutil.copytree(REPO_ROOT / "template", self.root / "template")

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = main([*args, "--root", str(self.root)])
        return code, out.getvalue() + err.getvalue()

    def test_generates_a_module_the_validator_understands(self):
        code, output = self.run_main("id", "contoh", "--display-name", "Contoh Situs", "--base-url", "https://contoh.example")
        self.assertEqual(code, 0, output)
        (ext,) = discover_extensions(self.root)
        self.assertEqual(ext.errors, [])
        self.assertEqual(ext.metadata["extClass"], ".ContohSitus")
        self.assertEqual(ext.version_name, "16.1")
        source = (ext.source_dir / "ContohSitus.kt").read_text()
        self.assertIn('const val BASE_URL = "https://contoh.example"', source)
        self.assertIn('const val HOST = "contoh.example"', source)
        self.assertIn('GET("$baseUrl/", headers)', source, "Kotlin $ templates must survive rendering")
        self.assertNotIn("{{", source)
        # Unimplemented scaffolds are not publishable.
        self.assertEqual([f.message.split()[0] for f in check_extension(ext)], ["scaffold"])

    def test_refuses_to_overwrite_without_force(self):
        args = ("en", "mysite", "--display-name", "My Site", "--base-url", "https://my.example")
        self.assertEqual(self.run_main(*args)[0], 0)
        code, output = self.run_main(*args)
        self.assertEqual(code, 1)
        self.assertIn("already exists", output)
        self.assertEqual(self.run_main(*args, "--force")[0], 0)

    def test_dry_run_writes_nothing(self):
        code, output = self.run_main("en", "mysite", "--display-name", "My Site", "--base-url", "https://my.example", "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("would write src/en/mysite/build.gradle", output)
        self.assertFalse((self.root / "src").exists())

    def test_rejects_unsafe_input(self):
        cases = [
            ("..", "x", "X", "X", "https://x.org"),
            ("en", "../../etc", "X", "X", "https://x.org"),
            ("en", "My-Site", "X", "X", "https://x.org"),
            ("en", "class", "X", "X", "https://x.org"),
            ("en", "ok", "Bad'Name", "X", "https://x.org"),
            ("en", "ok", "Ok", "bad", "https://x.org"),
            ("en", "ok", "Ok", "Ok", "http://x.org"),
            ("en", "ok", "Ok", "Ok", "https://user:pw@x.org"),
            ("en", "ok", "Ok", "Ok", "https://x.org/path"),
            ("en", "ok", "Ok", "Ok", "https://x.org/"),
        ]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ScaffoldError):
                validate(*case)
        with self.assertRaises(ScaffoldError):
            plan(self.root, "en", "..", "X", "X", "https://x.org", False)

    def test_class_name_derivation(self):
        self.assertEqual(class_name_from("my anime-site 2"), "MyAnimeSite2")
        self.assertEqual(class_name_from("9anime"), "Source9anime")


if __name__ == "__main__":
    unittest.main()
