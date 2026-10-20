plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.samWithReceiver)
    alias(libs.plugins.spotless)
    `java-gradle-plugin`
}

// Keep in sync with [src/main/kotlin/PluginSpotless.kt].
spotless {
    kotlin {
        target("src/**/*.kt", "*.kts")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file("../../.editorconfig"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    compileOnly(libs.android.gradle)
    compileOnly(libs.kotlin.gradle)
    implementation(libs.spotless.gradle)
    implementation(libs.tapmoc.gradle)

    // Makes the version catalogs' generated accessors usable from the compiled plugins.
    compileOnly(files(libs::class.java.superclass.protectionDomain.codeSource.location))
    compileOnly(files(ase::class.java.superclass.protectionDomain.codeSource.location))
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

gradlePlugin {
    plugins {
        register("android-base") {
            id = ase.plugins.android.base.get().pluginId
            implementationClass = "PluginAndroidBase"
        }
        register("extension") {
            id = ase.plugins.extension.get().pluginId
            implementationClass = "PluginExtension"
        }
        register("library") {
            id = ase.plugins.library.get().pluginId
            implementationClass = "PluginLibrary"
        }
        register("multisrc") {
            id = ase.plugins.multisrc.get().pluginId
            implementationClass = "PluginMultiSrc"
        }
        register("spotless") {
            id = ase.plugins.spotless.get().pluginId
            implementationClass = "PluginSpotless"
        }
    }
}
