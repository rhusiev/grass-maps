plugins {
    alias(libs.plugins.android.library)
}

android {
    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-data"))

    namespace = "nl.r1a.grassmap.plugins.valhalla"
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
    implementation(project(":plugin-api"))

    // Pinned. valhalla-mobile wraps the C++ engine for Android and iOS; only
    // this module may import it. MIT licence (ATTRIBUTION.md).
    implementation("io.github.rallista:valhalla-mobile:0.6.4")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
