import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.plugins.route.warnings"
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

    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(project(":regions"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The scanner tests read the committed test region by path, so declare it as
// an input or a changed fixture would leave the tests up to date.
val fixturePackage =
    rootProject.file("test-data/regions/fixture-mini/2026-11-03-1")

tasks.withType<Test>().configureEach {
    inputs
        .dir(fixturePackage)
        .withPropertyName("fixtureRegionPackage")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
