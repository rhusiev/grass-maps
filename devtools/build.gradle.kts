plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmaps.devtools"
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
    api(project(":core"))
    api(project(":control"))
    api(project(":plugin-api"))
}
