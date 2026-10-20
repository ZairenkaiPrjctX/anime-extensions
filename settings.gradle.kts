pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("ase") {
            from(files("gradle/ase.versions.toml"))
        }
    }
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") {
            // Only the extension API stubs are resolved from JitPack.
            content { includeGroup("com.github.komikku-app") }
        }
    }
}

rootProject.name = "anime-source-extensions"

/*
 * Extension modules are discovered from `src/<lang>/<name>`.
 *
 * To configure only some extensions (faster local builds, no file edits), pass a comma-separated
 * list of `<lang>:<name>` pairs:
 *
 *     ./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:assembleDebug
 *
 * Shared modules under `core`, `lib/` and `lib-multisrc/` are always included.
 */
val selectedExtensions: List<String>? = providers.gradleProperty("extensions").orNull
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }

if (selectedExtensions == null) {
    File(rootDir, "src").eachDir { lang -> lang.eachDir { include("src:${lang.name}:${it.name}") } }
} else {
    for (entry in selectedExtensions) {
        val parts = entry.split(':')
        require(parts.size == 2 && parts.all { it.matches(Regex("[a-z0-9]+")) }) {
            "Invalid -Pextensions entry '$entry', expected '<lang>:<name>'"
        }
        require(File(rootDir, "src/${parts[0]}/${parts[1]}/build.gradle").isFile) {
            "Extension module 'src/${parts[0]}/${parts[1]}' does not exist"
        }
        include("src:${parts[0]}:${parts[1]}")
    }
}

include(":core")
File(rootDir, "lib").eachDir { include("lib:${it.name}") }
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

fun File.eachDir(block: (File) -> Unit) {
    listFiles()
        ?.filter { it.isDirectory && it.name != "build" && !it.name.startsWith(".") }
        ?.sortedBy { it.name }
        ?.forEach(block)
}
