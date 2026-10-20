package ase.gradle

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import tapmoc.configureJavaCompatibility

internal fun Project.configureKotlin() {
    configureJavaCompatibility(ase.versions.java.get().toInt())

    extensions.configure<KotlinBaseExtension> {
        compilerOptions {
            optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
            freeCompilerArgs.add("-Xcontext-parameters")
        }
    }
}

private fun KotlinBaseExtension.compilerOptions(block: KotlinCommonCompilerOptions.() -> Unit) {
    if (this is HasConfigurableKotlinCompilerOptions<*>) compilerOptions(block)
}
