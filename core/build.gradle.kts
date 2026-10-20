plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(ase.plugins.android.base)
    alias(ase.plugins.spotless)
}

android {
    namespace = "aniext.core"

    buildFeatures {
        resValues = false
    }
}

dependencies {
    compileOnly(libs.bundles.provided)

    testImplementation(libs.bundles.provided)
    testImplementation(libs.junit)
}
