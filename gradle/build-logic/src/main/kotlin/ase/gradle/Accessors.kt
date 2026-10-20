package ase.gradle

import org.gradle.accessors.dm.LibrariesForAse
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.the
import org.gradle.plugin.use.PluginDependency

internal val Project.libs get() = the<LibrariesForLibs>()
internal val Project.ase get() = the<LibrariesForAse>()

internal fun Project.applyPlugin(plugin: Provider<PluginDependency>) {
    pluginManager.apply(plugin.get().pluginId)
}

/** Adds the runtime-provided host-app libraries as compileOnly, and to the unit test classpath. */
internal fun Project.addProvidedDependencies(dependencies: DependencyHandler) {
    dependencies.add("compileOnly", libs.bundles.provided)
    dependencies.add("testImplementation", libs.bundles.provided)
    dependencies.add("testImplementation", libs.junit)
}

internal fun ExtraPropertiesExtension.getOrNull(name: String): Any? = if (has(name)) get(name) else null

internal inline fun Project.requireConfig(value: Boolean, message: () -> String) {
    if (!value) throw GradleException("[$path] ${message()}")
}

internal fun Project.extraOrNull(name: String): Any? = extra.getOrNull(name)
