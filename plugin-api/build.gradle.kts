plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.plugin.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testFixtures {
        enable = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
