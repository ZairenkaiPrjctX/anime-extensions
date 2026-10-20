import ase.gradle.ase
import ase.gradle.configureKotlin
import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryDefaultConfig
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/** Android SDK levels, Kotlin/Java compatibility and unit-test defaults shared by every module. */
@Suppress("UNUSED")
class PluginAndroidBase : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        configureKotlin()

        extensions.configure<CommonExtension> {
            compileSdk = ase.versions.android.sdk.compile.get().toInt()

            defaultConfig.apply {
                minSdk = ase.versions.android.sdk.min.get().toInt()
                if (this is ApplicationDefaultConfig) {
                    targetSdk = ase.versions.android.sdk.target.get().toInt()
                }

                val proguardFile = file("proguard-rules.pro")
                if (proguardFile.exists()) {
                    when (this) {
                        is ApplicationDefaultConfig -> proguardFile(proguardFile)
                        is LibraryDefaultConfig -> consumerProguardFiles(proguardFile)
                    }
                }
            }

            testOptions.unitTests.isReturnDefaultValues = true
        }

        // Opt-in live-network tests read ASE_LIVE_TESTS; make it an input so caching can't skip them.
        tasks.withType<Test>().configureEach {
            inputs.property("liveTests", providers.environmentVariable("ASE_LIVE_TESTS").orElse(""))
        }
    }
}
