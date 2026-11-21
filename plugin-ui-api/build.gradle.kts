plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
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

    // ShellIcons.kt declares @Composable functions, so this module needs the
    // Compose compiler plugin like every other module with UI declarations.
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":plugin-api"))

    // The colour tokens carry Compose colours and the composition local, so
    // every UI plugin and the shell read the same values (T-0612).
    api("org.jetbrains.compose.ui:ui:1.12.1")
    api("org.jetbrains.compose.runtime:runtime:1.12.1")
    // ShellIcons draws each vector with Image, so every consumer needs foundation.
    api("org.jetbrains.compose.foundation:foundation:1.12.1")

    testImplementation(libs.junit)
}
