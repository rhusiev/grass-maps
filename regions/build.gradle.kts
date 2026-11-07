plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.regions"
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

    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
