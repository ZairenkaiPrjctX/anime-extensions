import helpers  # noqa: F401  (puts tools/ on sys.path)
import unittest

from build_matrix import MatrixError, affected_by, parse_module_list, to_matrix
from helpers import RepoTestCase


class BuildMatrixTest(RepoTestCase):
    def setUp(self):
        super().setUp()
        self.add_extension("en", "plain")
        self.add_extension("en", "uselib", extra_gradle="dependencies { implementation(project(':lib:wrapper')) }\n")
        self.add_extension("id", "themed", "    extName = 'T'\n    extClass = '.MySource'\n    themePkg = 'cms'\n    overrideVersionCode = 0\n    isNsfw = false\n")
        for lib, deps in (("base", ""), ("wrapper", 'implementation(project(":lib:base"))')):
            (self.root / "lib" / lib).mkdir(parents=True)
            (self.root / "lib" / lib / "build.gradle.kts").write_text(f"dependencies {{ {deps} }}\n")
        (self.root / "lib-multisrc" / "cms").mkdir(parents=True)
        (self.root / "lib-multisrc" / "cms" / "build.gradle.kts").write_text("baseVersionCode = 1\n")

    def test_extension_change_builds_only_that_module(self):
        modules, deleted = affected_by(self.root, ["src/en/plain/src/X.kt", "README.md"])
        self.assertEqual(modules, {("en", "plain")})
        self.assertEqual(deleted, set())

    def test_transitive_lib_change(self):
        modules, _ = affected_by(self.root, ["lib/base/src/Util.kt"])
        self.assertEqual(modules, {("en", "uselib")})

    def test_theme_change(self):
        self.assertEqual(affected_by(self.root, ["lib-multisrc/cms/src/Cms.kt"])[0], {("id", "themed")})

    def test_core_or_build_change_builds_everything(self):
        for path in ("core/src/main/kotlin/A.kt", "gradle/libs.versions.toml", "settings.gradle.kts", "common/proguard-rules.pro"):
            with self.subTest(path=path):
                self.assertEqual(len(affected_by(self.root, [path])[0]), 3)

    def test_docs_only_and_deleted_modules(self):
        self.assertEqual(affected_by(self.root, ["docs/ARCHITECTURE.md", "src/en/plain/README.md"])[0], set())
        modules, deleted = affected_by(self.root, ["src/xx/gone/build.gradle"])
        self.assertEqual((modules, deleted), (set(), {"xx.gone"}))

    def test_explicit_selection_is_strict(self):
        self.assertEqual(parse_module_list(self.root, "en:plain, :src:id:themed"), {("en", "plain"), ("id", "themed")})
        for bad in ("", " , ", "en:missing", "../etc", "en"):
            with self.subTest(bad=bad), self.assertRaises(MatrixError):
                parse_module_list(self.root, bad)

    def test_matrix_shape_is_deterministic(self):
        matrix = to_matrix({("id", "themed"), ("en", "plain")})
        self.assertEqual([i["module"] for i in matrix["include"]], [":src:en:plain", ":src:id:themed"])
        self.assertEqual(matrix["include"][0]["artifact"], "apk-en-plain")


if __name__ == "__main__":
    unittest.main()
