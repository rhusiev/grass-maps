plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.plugins.direct"
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
    // No packed data, no native code and no network: the engine is arithmetic
    // over the plugin-api contract, so contract plus tests is the module.
    implementation(project(":plugin-api"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
