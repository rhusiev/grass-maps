plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.plugins.route.planner"
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
    // The first-party planner is a compiled-in UI feature over the routing
    // domain, like navigation; it exposes no core internals to plugins.
    implementation(project(":plugin-api"))
    implementation(project(":routing"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
