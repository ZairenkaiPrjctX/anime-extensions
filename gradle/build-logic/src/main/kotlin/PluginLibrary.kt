import ase.gradle.addProvidedDependencies
import ase.gradle.applyPlugin
import ase.gradle.ase
import ase.gradle.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Reusable helper library under `lib/<name>`; sources in `src/`, unit tests in `test/kotlin`. */
@Suppress("UNUSED")
class PluginLibrary : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.android.library)
        applyPlugin(libs.plugins.kotlin.serialization)
        applyPlugin(ase.plugins.android.base)
        applyPlugin(ase.plugins.spotless)

        extensions.configure<LibraryExtension> {
            namespace = "aniext.lib.${project.name}"

            sourceSets.named("main") {
                kotlin.directories.clear()
                kotlin.directories.add("src")
                assets.directories.clear()
                assets.directories.add("assets")
            }
            sourceSets.named("test") {
                kotlin.directories.clear()
                kotlin.directories.add("test/kotlin")
                resources.directories.clear()
                resources.directories.add("test/resources")
            }

            androidResources.enable = false
        }

        dependencies {
            add("implementation", project(":core"))
            addProvidedDependencies(this)
        }
    }
}
