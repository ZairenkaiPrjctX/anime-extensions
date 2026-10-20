import ase.gradle.applyPlugin
import ase.gradle.libs
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** ktlint + whitespace hygiene. Keep in sync with /gradle/build-logic/build.gradle.kts. */
@Suppress("UNUSED")
class PluginSpotless : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.spotless)

        // The root project only owns its own build scripts; every module formats itself.
        val isRoot = this == rootProject
        extensions.configure<SpotlessExtension> {
            kotlin {
                target(if (isRoot) listOf("*.kts") else listOf("src/**/*.kt", "test/**/*.kt", "*.kts"))
                targetExclude("**/build/**")
                ktlint(libs.versions.ktlint.get())
                    .setEditorConfigPath(rootProject.file(".editorconfig"))
                trimTrailingWhitespace()
                endWithNewline()
            }

            format("gradle") {
                target("*.gradle")
                trimTrailingWhitespace()
                endWithNewline()
            }

            format("xml") {
                target(if (isRoot) listOf("common/*.xml") else listOf("*.xml", "src/**/*.xml", "res/**/*.xml"))
                targetExclude("**/build/**")
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }
}
