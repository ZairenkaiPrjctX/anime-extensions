import ase.gradle.addProvidedDependencies
import ase.gradle.applyPlugin
import ase.gradle.ase
import ase.gradle.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Multi-source theme under `lib-multisrc/<theme>`. Declare `baseVersionCode` in the theme's
 * `build.gradle.kts`; extensions using it set `themePkg` and `overrideVersionCode`.
 */
@Suppress("UNUSED")
class PluginMultiSrc : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.android.library)
        applyPlugin(libs.plugins.kotlin.serialization)
        applyPlugin(ase.plugins.android.base)
        applyPlugin(ase.plugins.spotless)

        extensions.configure<LibraryExtension> {
            namespace = "aniext.multisrc.${project.name}"

            sourceSets.named("main") {
                manifest.srcFile("AndroidManifest.xml")
                kotlin.directories.clear()
                kotlin.directories.add("src")
                res.directories.clear()
                res.directories.add("res")
                assets.directories.clear()
                assets.directories.add("assets")
            }
            sourceSets.named("test") {
                kotlin.directories.clear()
                kotlin.directories.add("test/kotlin")
                resources.directories.clear()
                resources.directories.add("test/resources")
            }
        }

        dependencies {
            add("implementation", project(":core"))
            addProvidedDependencies(this)
        }
    }
}
