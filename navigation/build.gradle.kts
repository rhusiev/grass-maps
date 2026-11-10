plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.navigation"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The replay fixture and the harness are shared by the desktop test and the
    // instrumented Ferrostar test, so both measure the same replay.
    sourceSets {
        getByName("test") {
            kotlin.srcDir("src/sharedTest/java")
            resources.srcDir(rootProject.file("test-data"))
        }
        getByName("androidTest") {
            kotlin.srcDir("src/sharedTest/java")
            assets.srcDir(rootProject.file("test-data"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":routing"))
    api(project(":plugin-api"))

    // Ferrostar's Rust core, without ui-compose or ui-maplibre. Every type from
    // this AAR stays inside this module; see `FerrostarRouteFollower`.
    implementation(libs.ferrostar.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The native core ships Android ABIs only, so only an instrumented test can
    // run the real follower; see `FerrostarReplayTest`.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // T-0305: the direct engine's own route is the fixture the follower's
    // acceptance test feeds through the mapper. Test only.
    testImplementation(project(":plugins:direct-engine"))
}
