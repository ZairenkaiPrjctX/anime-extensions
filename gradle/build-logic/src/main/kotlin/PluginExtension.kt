import ase.gradle.GenerateKeepRulesTask
import ase.gradle.addProvidedDependencies
import ase.gradle.applyPlugin
import ase.gradle.ase
import ase.gradle.extraOrNull
import ase.gradle.libs
import ase.gradle.requireConfig
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.tasks.PackageAndroidArtifact
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Packages one installable extension APK from `src/<lang>/<name>`.
 *
 * Metadata is read from the module's `build.gradle` `ext { }` block:
 *
 * | key                   | required | meaning                                                   |
 * |-----------------------|----------|-----------------------------------------------------------|
 * | `extName`             | yes      | Display name, romanized ASCII                             |
 * | `extClass`            | yes      | `.ClassName` of the `AnimeSource`/`AnimeSourceFactory`    |
 * | `extVersionCode`      | yes*     | Positive version code (*not with `themePkg`)              |
 * | `isNsfw`              | yes      | `true` / `false`                                          |
 * | `libVersion`          | no       | Extensions-lib API level, defaults to the repo level (16) |
 * | `themePkg`            | no       | `lib-multisrc` theme the extension builds on              |
 * | `overrideVersionCode` | with theme | Added to the theme's `baseVersionCode`                  |
 *
 * The app identifies an extension by its package name `eu.kanade.tachiyomi.animeextension.<lang>.<name>`
 * and reads the lib level from the version name `<libVersion>.<versionCode>`.
 */
@Suppress("UNUSED")
class PluginExtension : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.android.application)
        applyPlugin(libs.plugins.kotlin.serialization)
        applyPlugin(ase.plugins.android.base)
        applyPlugin(ase.plugins.spotless)

        val lang = project.parent?.name.orEmpty()
        val moduleName = project.name
        requireConfig(project.parent?.parent?.name == "src" && LANG_REGEX.matches(lang) && NAME_REGEX.matches(moduleName)) {
            "Extensions must live in src/<lang>/<name> with lowercase ASCII names (lang: 2-3 letters, 'all' or 'other')"
        }
        requireConfig(extraOrNull("pkgNameSuffix") == null) { "'pkgNameSuffix' is not supported; the package is derived from the directory" }

        val metadata = readMetadata()
        val theme: Project? = metadata.themePkg?.let { project(":lib-multisrc:$it") }
        if (theme != null) evaluationDependsOn(theme.path)

        val versionCode = if (theme == null) {
            metadata.extVersionCode!!
        } else {
            val base = theme.extraOrNull("baseVersionCode") as? Int
            requireConfig(base != null && base > 0) { "Theme ${theme.path} must declare a positive 'baseVersionCode'" }
            base!! + metadata.overrideVersionCode!!
        }
        val versionName = "${metadata.libVersion}.$versionCode"
        val applicationIdSuffix = "$lang.$moduleName"

        extensions.configure<ApplicationExtension> {
            namespace = "eu.kanade.tachiyomi.animeextension"

            sourceSets.named("main") {
                manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
                java.directories.clear()
                java.directories.add("src")
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

            defaultConfig {
                this.applicationIdSuffix = applicationIdSuffix
                this.versionCode = versionCode
                this.versionName = versionName
                manifestPlaceholders += mapOf(
                    "appName" to "Aniyomi: ${metadata.extName}",
                    "extClass" to metadata.extClass,
                    "nsfw" to if (metadata.isNsfw) 1 else 0,
                )
            }

            lint {
                checkReleaseBuilds = false
            }

            // Release signing is only configured when CI provides the keystore; otherwise release
            // builds fall back to the debug key and are suitable for local testing only.
            val keystore = rootProject.file("signingkey.jks")
            signingConfigs {
                create("release") {
                    storeFile = keystore
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = signingConfigs.getByName(if (keystore.exists()) "release" else "debug")
                    isMinifyEnabled = true
                    proguardFiles(rootProject.file("common/proguard-rules.pro"))
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
                resources.excludes.add("META-INF/*.version")
            }
        }

        extensions.configure<BasePluginExtension> {
            archivesName.set("aniyomi-$applicationIdSuffix-v$versionName")
        }

        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercase() }

                @Suppress("UnstableApiUsage")
                val keepRules = variant.sources.keepRules
                if (keepRules != null) {
                    val task = tasks.register<GenerateKeepRulesTask>("generate${variantName}KeepRules") {
                        applicationId.set(variant.applicationId)
                        extClass.set(metadata.extClass)
                    }
                    keepRules.addGeneratedSourceDirectory(task) { it.outputDir }
                }

                variant.sources.manifests.addStaticManifestFile("AndroidManifest.xml")
            }
        }

        dependencies {
            if (theme != null) add("implementation", theme)
            add("implementation", project(":core"))
            addProvidedDependencies(this)
        }

        // Reproducible APKs: no build-tool fingerprint or app-metadata file.
        afterEvaluate {
            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }

    private class Metadata(
        val extName: String,
        val extClass: String,
        val extVersionCode: Int?,
        val libVersion: Int,
        val isNsfw: Boolean,
        val themePkg: String?,
        val overrideVersionCode: Int?,
    )

    private fun Project.readMetadata(): Metadata {
        val extName = extraOrNull("extName") as? String
        requireConfig(!extName.isNullOrBlank()) { "'extName' is required" }
        requireConfig(extName!!.all { it.code in 0x20 until 0x180 }) { "'extName' must be romanized" }

        val extClass = extraOrNull("extClass") as? String
        requireConfig(extClass != null && CLASS_REGEX.matches(extClass)) { "'extClass' must be a relative class name such as '.MySource'" }

        val isNsfw = extraOrNull("isNsfw")
        requireConfig(isNsfw is Boolean) { "'isNsfw' must be set explicitly to true or false" }

        val repoLibVersion = ase.versions.extension.lib.get().toInt()
        val libVersion = (extraOrNull("libVersion") ?: repoLibVersion).toString().toIntOrNull()
        requireConfig(libVersion != null && libVersion in MIN_LIB_VERSION..repoLibVersion) {
            "'libVersion' must be an integer between $MIN_LIB_VERSION and $repoLibVersion"
        }

        val themePkg = extraOrNull("themePkg") as? String
        val extVersionCode = extraOrNull("extVersionCode") as? Int
        val overrideVersionCode = extraOrNull("overrideVersionCode") as? Int
        if (themePkg == null) {
            requireConfig(extVersionCode != null && extVersionCode > 0) { "'extVersionCode' must be a positive integer" }
        } else {
            requireConfig(extVersionCode == null) { "Use 'overrideVersionCode' instead of 'extVersionCode' with 'themePkg'" }
            requireConfig(overrideVersionCode != null && overrideVersionCode >= 0) { "'overrideVersionCode' must be a non-negative integer" }
        }

        return Metadata(extName, extClass!!, extVersionCode, libVersion!!, isNsfw as Boolean, themePkg, overrideVersionCode)
    }

    private companion object {
        // Oldest lib level the host app (Anikku ExtensionLoader.LIB_VERSION_MIN) still loads.
        const val MIN_LIB_VERSION = 12
        val LANG_REGEX = Regex("[a-z]{2,3}|all|other")
        val NAME_REGEX = Regex("[a-z0-9]+")
        val CLASS_REGEX = Regex("""(\.[A-Za-z_][A-Za-z0-9_]*)+""")
    }
}
