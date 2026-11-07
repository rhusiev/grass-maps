plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.tracks"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-data"))
}

dependencies {
    api(project(":plugin-api"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
