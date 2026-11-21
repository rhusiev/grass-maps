plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmaps.plugin.ui.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":plugin-api"))

    // The colour tokens carry Compose colours and the composition local, so
    // every UI plugin and the shell read the same values (T-0612).
    api("org.jetbrains.compose.ui:ui:1.12.1")
    api("org.jetbrains.compose.runtime:runtime:1.12.1")

    testImplementation(libs.junit)
}
